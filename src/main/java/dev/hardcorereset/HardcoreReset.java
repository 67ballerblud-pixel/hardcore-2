package dev.hardcorereset;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

public class HardcoreReset extends JavaPlugin implements Listener {

    /** Every world this plugin makes starts with this, so leftovers can be found and deleted. */
    private static final String PREFIX = "hcr_";
    private static final int MAX_LOG_ENTRIES = 200;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("MMM d, HH:mm");

    private NamespacedKey runKey;
    private int runNumber;
    private long seed;
    private World overworld, nether, end;
    private boolean resetting = false;

    private File deathsFile;
    private YamlConfiguration deathsData;

    private Scoreboard board;
    private Objective sidebar;

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        runKey = new NamespacedKey(this, "run");
        runNumber = getConfig().getInt("run", 0);

        deathsFile = new File(getDataFolder(), "deaths.yml");
        deathsData = YamlConfiguration.loadConfiguration(deathsFile);
        setupScoreboard();

        if (runNumber == 0) {
            startNewRun();                       // first ever start
        } else {
            seed = getConfig().getLong("seed");
            loadWorlds();                        // reload the current run's worlds
        }

        cleanupLeftoverWorlds();
        getServer().getPluginManager().registerEvents(this, this);

        // /reload or late enable: put everyone already online on the board
        for (Player online : Bukkit.getOnlinePlayers()) {
            registerPlayer(online);
            online.setScoreboard(board);
        }
        refreshBoard();

        getLogger().info("Hardcore run #" + runNumber + " ready (seed " + seed + ")");
    }

    // ------------------------------------------------------------------ worlds

    /** Bumps the run counter, picks a fresh random seed and creates the 3 dimensions. */
    private void startNewRun() {
        runNumber++;
        seed = ThreadLocalRandom.current().nextLong();
        getConfig().set("run", runNumber);
        getConfig().set("seed", seed);
        saveConfig();
        loadWorlds();
    }

    private void loadWorlds() {
        String base = PREFIX + runNumber;
        overworld = build(base, World.Environment.NORMAL);
        nether = build(base + "_nether", World.Environment.NETHER);
        end = build(base + "_the_end", World.Environment.THE_END);
    }

    private World build(String name, World.Environment env) {
        World w = WorldCreator.name(name)
                .environment(env)
                .seed(seed)
                .hardcore(true)
                .createWorld();
        if (w == null) throw new IllegalStateException("Could not create world " + name);
        w.setDifficulty(Difficulty.HARD);
        return w;
    }

    private boolean isMarathonWorld(World w) {
        return w != null && (w.equals(overworld) || w.equals(nether) || w.equals(end));
    }

    private Location spawnLocation() {
        Location s = overworld.getSpawnLocation();
        int y = overworld.getHighestBlockYAt(s) + 1;
        return new Location(overworld, s.getX() + 0.5, y, s.getZ() + 0.5);
    }

    // ------------------------------------------------------------------ death tracking

    /** Adds one death to the player's total and writes a line to the log. Returns their new total. */
    private int recordDeath(Player p, Component deathMessage) {
        String path = "players." + p.getUniqueId();
        int total = deathsData.getInt(path + ".deaths", 0) + 1;
        deathsData.set(path + ".name", p.getName());
        deathsData.set(path + ".deaths", total);

        String cause = deathMessage == null
                ? p.getName() + " died"
                : PlainTextComponentSerializer.plainText().serialize(deathMessage);
        String line = "Run #" + runNumber + " | " + cause + " | " + LocalDateTime.now().format(TIME_FMT);

        List<String> log = new ArrayList<>(deathsData.getStringList("log"));
        log.add(line);
        while (log.size() > MAX_LOG_ENTRIES) log.remove(0);
        deathsData.set("log", log);

        try {
            deathsData.save(deathsFile);
        } catch (IOException ex) {
            getLogger().warning("Could not save deaths.yml: " + ex.getMessage());
        }
        refreshBoard();
        return total;
    }

    private record Entry(String name, int deaths) {}

    /** Everyone who has ever joined, most deaths first (ties broken alphabetically). */
    private List<Entry> sortedEntries() {
        List<Entry> entries = new ArrayList<>();
        ConfigurationSection players = deathsData.getConfigurationSection("players");
        if (players != null) {
            for (String key : players.getKeys(false)) {
                entries.add(new Entry(players.getString(key + ".name", "?"), players.getInt(key + ".deaths", 0)));
            }
        }
        entries.sort(Comparator.comparingInt(Entry::deaths).reversed()
                .thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER));
        return entries;
    }

    /** Adds a first-time player to the list with 0 deaths, and keeps their name current. */
    private void registerPlayer(Player p) {
        String path = "players." + p.getUniqueId();
        boolean changed = !deathsData.contains(path + ".deaths")
                || !p.getName().equals(deathsData.getString(path + ".name"));
        deathsData.set(path + ".name", p.getName());
        if (!deathsData.contains(path + ".deaths")) deathsData.set(path + ".deaths", 0);
        if (changed) {
            try {
                deathsData.save(deathsFile);
            } catch (IOException ex) {
                getLogger().warning("Could not save deaths.yml: " + ex.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ sidebar scoreboard

    private void setupScoreboard() {
        board = Bukkit.getScoreboardManager().getNewScoreboard();
        sidebar = board.registerNewObjective("hcdeaths", Criteria.DUMMY,
                Component.text("Deaths", NamedTextColor.RED, TextDecoration.BOLD));
        sidebar.setDisplaySlot(DisplaySlot.SIDEBAR);
    }

    /** Rebuilds the sidebar. Minecraft only shows 15 lines, so it's the top 15. */
    private void refreshBoard() {
        for (String entry : new ArrayList<>(board.getEntries())) board.resetScores(entry);
        List<Entry> entries = sortedEntries();
        for (int i = 0; i < Math.min(15, entries.size()); i++) {
            Entry en = entries.get(i);
            sidebar.getScore(en.name()).setScore(en.deaths());
        }
    }

    private void showDeaths(CommandSender sender) {
        ConfigurationSection players = deathsData.getConfigurationSection("players");
        if (players == null || players.getKeys(false).isEmpty()) {
            sender.sendMessage(Component.text("Nobody has joined yet.", NamedTextColor.GRAY));
            return;
        }

        List<Entry> entries = sortedEntries();

        sender.sendMessage(Component.text("Deaths (each one ended a run)", NamedTextColor.RED, TextDecoration.BOLD));
        for (Entry en : entries) {
            sender.sendMessage(Component.text(en.name() + ": ", NamedTextColor.WHITE)
                    .append(Component.text(String.valueOf(en.deaths()), NamedTextColor.YELLOW)));
        }

        List<String> log = deathsData.getStringList("log");
        sender.sendMessage(Component.text("Most recent", NamedTextColor.RED, TextDecoration.BOLD));
        for (int i = Math.max(0, log.size() - 5); i < log.size(); i++) {
            sender.sendMessage(Component.text(log.get(i), NamedTextColor.GRAY));
        }
    }

    // ------------------------------------------------------------------ death -> countdown -> reset

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getPlayer();
        if (!isMarathonWorld(p.getWorld())) return;

        int total = recordDeath(p, e.deathMessage());
        if (resetting) return;               // someone already started the countdown
        resetting = true;

        Bukkit.broadcast(Component.text(p.getName() + " has now died " + total + (total == 1 ? " time" : " times"),
                NamedTextColor.GRAY));
        beginCountdown(Component.text(p.getName() + " died", NamedTextColor.RED, TextDecoration.BOLD));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("hcdeaths")) {
            showDeaths(sender);
            return true;
        }

        // /hcreset
        if (resetting) {
            sender.sendMessage(Component.text("A reset is already in progress.", NamedTextColor.RED));
            return true;
        }
        resetting = true;
        beginCountdown(Component.text("World reset", NamedTextColor.RED, TextDecoration.BOLD));
        return true;
    }

    private void beginCountdown(Component headline) {
        final int[] left = {getConfig().getInt("countdown-seconds", 10)};
        new BukkitRunnable() {
            @Override
            public void run() {
                if (left[0] <= 0) {
                    cancel();
                    performReset();
                    return;
                }
                Title title = Title.title(
                        headline,
                        Component.text("Resetting in " + left[0], NamedTextColor.YELLOW),
                        Title.Times.times(Duration.ZERO, Duration.ofMillis(1100), Duration.ZERO));
                for (Player p : Bukkit.getOnlinePlayers()) p.showTitle(title);
                left[0]--;
            }
        }.runTaskTimer(this, 0L, 20L);
    }

    private void performReset() {
        // Anyone still on the death screen has to be respawned before they can be teleported.
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.isDead()) p.spigot().respawn();
        }
        Bukkit.getScheduler().runTaskLater(this, this::finishReset, 5L);
    }

    private void finishReset() {
        List<World> oldWorlds = List.of(overworld, nether, end);

        startNewRun();                                   // new seed + 3 new dimensions
        for (Player p : Bukkit.getOnlinePlayers()) freshStart(p);

        // Players are gone from the old worlds now, so they can be unloaded and deleted.
        List<File> deletable = new ArrayList<>();
        for (World w : oldWorlds) {
            File folder = w.getWorldFolder();
            if (Bukkit.unloadWorld(w, false)) {
                deletable.add(folder);
            } else {
                getLogger().warning("Could not unload " + w.getName() + "; it will be removed on next restart.");
            }
        }
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> deletable.forEach(this::deleteRecursively));

        resetting = false;
        Bukkit.broadcast(Component.text("New world generated. Good luck!", NamedTextColor.GREEN));
    }

    // ------------------------------------------------------------------ player wipe

    /** Teleports to the new world and resets everything about the player. */
    private void freshStart(Player p) {
        p.teleport(spawnLocation());
        p.setGameMode(GameMode.SURVIVAL);

        p.getInventory().clear();            // includes armor + offhand
        p.getEnderChest().clear();
        p.setLevel(0);
        p.setExp(0f);
        p.setTotalExperience(0);

        p.setHealth(20.0);
        p.setFoodLevel(20);
        p.setSaturation(5f);
        p.setExhaustion(0f);
        p.setFireTicks(0);
        p.setFallDistance(0f);
        for (PotionEffect ef : new ArrayList<>(p.getActivePotionEffects())) {
            p.removePotionEffect(ef.getType());
        }

        Iterator<Advancement> it = Bukkit.advancementIterator();
        while (it.hasNext()) {
            AdvancementProgress progress = p.getAdvancementProgress(it.next());
            for (String criterion : new ArrayList<>(progress.getAwardedCriteria())) {
                progress.revokeCriteria(criterion);
            }
        }

        p.getPersistentDataContainer().set(runKey, PersistentDataType.INTEGER, runNumber);
    }

    /** Anyone who was offline during a reset gets wiped the moment they join. */
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        registerPlayer(p);
        p.setScoreboard(board);
        refreshBoard();

        Integer last = p.getPersistentDataContainer().get(runKey, PersistentDataType.INTEGER);
        if (last == null || last != runNumber) freshStart(p);
    }

    /** Never respawn into a deleted/foreign world. */
    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        if (!isMarathonWorld(e.getRespawnLocation().getWorld())) {
            e.setRespawnLocation(spawnLocation());
        }
    }

    // ------------------------------------------------------------------ portals (custom worlds aren't linked automatically)

    @EventHandler
    public void onPortal(PlayerPortalEvent e) {
        Location from = e.getFrom();
        World fromWorld = from.getWorld();
        if (!isMarathonWorld(fromWorld)) return;
        World.Environment env = fromWorld.getEnvironment();

        if (e.getCause() == TeleportCause.NETHER_PORTAL) {
            Location to;
            if (env == World.Environment.NORMAL) {
                to = new Location(nether, from.getX() / 8, from.getY(), from.getZ() / 8, from.getYaw(), from.getPitch());
                e.setSearchRadius(16);
            } else if (env == World.Environment.NETHER) {
                to = new Location(overworld, from.getX() * 8, from.getY(), from.getZ() * 8, from.getYaw(), from.getPitch());
                e.setSearchRadius(128);
            } else {
                return;
            }
            e.setTo(to);
            e.setCanCreatePortal(true);
            e.setCreationRadius(16);
        } else if (e.getCause() == TeleportCause.END_PORTAL) {
            if (env == World.Environment.NORMAL) {
                buildEndPlatform();
                e.setTo(new Location(end, 100.5, 50, 0.5, 90f, 0f));
            } else if (env == World.Environment.THE_END) {
                e.setTo(spawnLocation());
            }
        }
    }

    private void buildEndPlatform() {
        for (int x = 98; x <= 102; x++) {
            for (int z = -2; z <= 2; z++) {
                end.getBlockAt(x, 49, z).setType(Material.OBSIDIAN);
                for (int y = 50; y <= 52; y++) end.getBlockAt(x, y, z).setType(Material.AIR);
            }
        }
    }

    // ------------------------------------------------------------------ cleanup helpers

    /** Deletes hcr_* folders from older runs (e.g. after a crash mid-reset). */
    private void cleanupLeftoverWorlds() {
        File[] dirs = Bukkit.getWorldContainer().listFiles(File::isDirectory);
        if (dirs == null) return;
        String base = PREFIX + runNumber;
        for (File d : dirs) {
            String n = d.getName();
            boolean ours = n.equals(base) || n.equals(base + "_nether") || n.equals(base + "_the_end");
            if (n.startsWith(PREFIX) && !ours) {
                getLogger().info("Removing leftover world folder " + n);
                Bukkit.getScheduler().runTaskAsynchronously(this, () -> deleteRecursively(d));
            }
        }
    }

    private void deleteRecursively(File folder) {
        try (Stream<Path> walk = Files.walk(folder.toPath())) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException ex) {
            getLogger().warning("Could not delete " + folder + ": " + ex.getMessage());
        }
    }
}
