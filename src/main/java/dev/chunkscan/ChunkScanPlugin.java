package dev.chunkscan;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class ChunkScanPlugin extends JavaPlugin implements Listener {

    private static final String P = "§6[ChunkScan] §r";
    private final AtomicBoolean running = new AtomicBoolean(false);
    /** Latest release if it is newer than the installed version, else null. */
    private volatile UpdateChecker.Release newerRelease;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Bukkit.getPluginManager().registerEvents(this, this);
        if (getConfig().getBoolean("update-check", true)) {
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                try {
                    UpdateChecker.Release latest = UpdateChecker.fetchLatest(version());
                    if (UpdateChecker.compare(latest.version, version()) > 0) {
                        newerRelease = latest;
                        getLogger().warning("A new version is available: " + latest.version
                                + " (installed: " + version() + "). Download: " + latest.url);
                    }
                } catch (Exception e) {
                    getLogger().info("Could not check for updates: " + e.getMessage());
                }
            });
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] rawArgs) {
        if (rawArgs.length == 1 && rawArgs[0].equalsIgnoreCase("version")) return versionCommand(sender);
        boolean export = hasFlag(rawArgs);
        String[] args = stripFlag(rawArgs);
        if (args.length < 1 || args.length > 4) return usage(sender, label);

        long thresholdTicks;
        try {
            thresholdTicks = TimeParse.toTicks(args[0]);
        } catch (NumberFormatException e) {
            sender.sendMessage(P + "§cInvalid time: " + args[0]);
            return true;
        }

        // Center modes: chunky | spawn [world] | origin [world] | here | <x> <z> [world]
        // No mode: Chunky selection if available, else your position (or spawn from console).
        String mode = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "auto";
        World world;
        int cx, cz;
        String source;
        String chunkyShape = null;
        switch (mode) {
            case "auto":
            case "chunky": {
                ChunkyBridge.Selection sel = ChunkyBridge.read();
                World selWorld = sel == null ? null : Bukkit.getWorld(sel.world);
                if (selWorld != null) {
                    if (args.length > 2) return usage(sender, label);
                    world = selWorld;
                    cx = (int) Math.floor(sel.centerX);
                    cz = (int) Math.floor(sel.centerZ);
                    chunkyShape = sel.shape.toLowerCase(Locale.ROOT);
                    source = "Chunky selection (shape " + chunkyShape + ", current radius " + fmt(sel.radiusX) + ")";
                    break;
                }
                if (mode.equals("chunky")) {
                    sender.sendMessage(P + "§cCould not read Chunky's selection (is Chunky installed and enabled?).");
                    return true;
                }
                if (sender instanceof Player) {
                    Location loc = ((Player) sender).getLocation();
                    world = loc.getWorld();
                    cx = loc.getBlockX();
                    cz = loc.getBlockZ();
                    source = "your position (Chunky not found)";
                } else {
                    world = Bukkit.getWorlds().get(0);
                    Location loc = world.getSpawnLocation();
                    cx = loc.getBlockX();
                    cz = loc.getBlockZ();
                    source = "world spawn (Chunky not found)";
                }
                break;
            }
            case "here": {
                if (!(sender instanceof Player)) {
                    sender.sendMessage(P + "§c'here' only works for players.");
                    return true;
                }
                if (args.length > 2) return usage(sender, label);
                Location loc = ((Player) sender).getLocation();
                world = loc.getWorld();
                cx = loc.getBlockX();
                cz = loc.getBlockZ();
                source = "your position";
                break;
            }
            case "spawn":
            case "origin": {
                if (args.length > 3) return usage(sender, label);
                world = args.length == 3 ? Bukkit.getWorld(args[2]) : defaultWorld(sender);
                if (world == null) {
                    sender.sendMessage(P + "§cWorld not found: " + args[2]);
                    return true;
                }
                if (mode.equals("spawn")) {
                    Location loc = world.getSpawnLocation();
                    cx = loc.getBlockX();
                    cz = loc.getBlockZ();
                    source = "world spawn";
                } else {
                    cx = 0;
                    cz = 0;
                    source = "world origin";
                }
                break;
            }
            default: {
                if (args.length < 3) return usage(sender, label);
                try {
                    cx = Integer.parseInt(args[1]);
                    cz = Integer.parseInt(args[2]);
                } catch (NumberFormatException e) {
                    return usage(sender, label);
                }
                world = args.length == 4 ? Bukkit.getWorld(args[3]) : defaultWorld(sender);
                if (world == null) {
                    sender.sendMessage(P + "§cWorld not found: " + args[3]);
                    return true;
                }
                source = "given coordinates";
            }
        }

        File regionDir = regionDir(world);
        if (regionDir == null) {
            sender.sendMessage(P + "§cCould not find the region folder of " + world.getName());
            return true;
        }
        if (!running.compareAndSet(false, true)) {
            sender.sendMessage(P + "§cA scan is already running.");
            return true;
        }
        sender.sendMessage(P + "§eCenter: X " + cx + ", Z " + cz + " in " + world.getName() + "§r (" + source + ")");

        // keep Chunky from changing region files while they are read
        final ScanGuard guard = ScanGuard.begin(sender, getConfig().getBoolean("pause-chunky", true),
                getConfig().getStringList("suspend-plugins"));

        // flush loaded chunks so the files contain current InhabitedTime values
        sender.sendMessage(P + "Saving " + world.getName() + "...");
        world.save();

        int threads = Math.max(1, Runtime.getRuntime().availableProcessors());
        int regionCount = RegionScanner.listRegionFiles(regionDir).length;
        sender.sendMessage(P + "Scanning " + regionCount + " region files on " + threads
                + " threads, min time " + TimeParse.format(thresholdTicks)
                + (export ? ", exporting every chunk" : "") + ". The server may lag until this finishes.");

        final int fcx = cx, fcz = cz;
        final World fworld = world;
        final long fthreshold = thresholdTicks;
        final String fshape = chunkyShape;
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                long start = System.currentTimeMillis();
                int step = Math.max(1, regionCount / 10);
                File dir = getDataFolder();
                dir.mkdirs();
                String base = fworld.getName() + "-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
                File csv = new File(dir, base + "-visited.csv");
                File png = new File(dir, base + ".png");
                File exportFile = new File(dir, base + "-chunks.csv");

                RegionScanner.Result r;
                long exportedRows = 0;
                try (ChunkExport sink = export ? new ChunkExport(exportFile, fcx, fcz, fthreshold) : null) {
                    r = RegionScanner.scan(regionDir, threads, sink, done -> {
                        if (done % step == 0 && done < regionCount) {
                            int pct = (int) (100L * done / Math.max(1, regionCount));
                            tell(sender, P + "§7" + pct + "% (" + done + "/" + regionCount + ")");
                        }
                    });
                    if (sink != null) exportedRows = sink.rows();
                }
                Analysis a = new Analysis(r, fcx, fcz, fthreshold);
                Analysis.writeCsv(r, csv);
                a.writeHeatmap(r, png, 4096);

                long secs = (System.currentTimeMillis() - start) / 1000;
                List<String> lines = a.summary(r);
                final long rows = exportedRows;
                Bukkit.getScheduler().runTask(this, () -> {
                    sender.sendMessage(P + "§aDone in " + secs + "s.");
                    for (String l : lines) sender.sendMessage(P + l);
                    if (a.qualifying > 0) suggestChunky(sender, a, fworld.getName(), fcx, fcz, fshape);
                    sender.sendMessage(P + "Files in plugins/" + dir.getName() + "/: " + png.getName() + ", " + csv.getName()
                            + (export ? ", " + exportFile.getName() + " (" + rows + " chunks, "
                            + mb(exportFile.length()) + ")" : ""));
                });
            } catch (Throwable t) {
                getLogger().severe("Scan failed: " + t);
                t.printStackTrace();
                tell(sender, P + "§cScan failed: " + t.getMessage());
            } finally {
                try {
                    Bukkit.getScheduler().runTask(this, () -> guard.end(sender));
                } catch (Throwable ignored) {
                    // plugin is being disabled (server stopping); Chunky saves its own tasks then
                }
                running.set(false);
            }
        });
        return true;
    }

    private String version() {
        return getDescription().getVersion();
    }

    private boolean versionCommand(CommandSender sender) {
        sender.sendMessage(P + "ChunkScan " + version() + " - https://github.com/" + UpdateChecker.REPO);
        sender.sendMessage(P + "§7Checking for updates...");
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            String msg;
            try {
                UpdateChecker.Release latest = UpdateChecker.fetchLatest(version());
                if (UpdateChecker.compare(latest.version, version()) > 0) {
                    newerRelease = latest;
                    msg = "§eNew version " + latest.version + " available: " + latest.url;
                } else {
                    newerRelease = null;
                    msg = "§aYou are running the latest version.";
                }
            } catch (Exception e) {
                msg = "§cCould not check for updates: " + e.getMessage();
            }
            tell(sender, P + msg);
        });
        return true;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        UpdateChecker.Release r = newerRelease;
        Player p = event.getPlayer();
        if (r != null && p.hasPermission("chunkscan.use")) {
            p.sendMessage(P + "§eA new ChunkScan version is available: " + r.version
                    + " (installed: " + version() + "). " + r.url);
        }
    }

    private void suggestChunky(CommandSender sender, Analysis a, String world, int cx, int cz, String chunkyShape) {
        if (chunkyShape != null) {
            // center/world already match Chunky; only the radius has to be set
            if (chunkyShape.equals("circle")) {
                sender.sendMessage(P + "§eChunky: /chunky radius " + a.circleRadius);
            } else if (chunkyShape.equals("square")) {
                sender.sendMessage(P + "§eChunky: /chunky radius " + a.squareRadius);
            } else {
                sender.sendMessage(P + "§eChunky shape is " + chunkyShape + "; switch to circle or square: "
                        + "/chunky shape circle + /chunky radius " + a.circleRadius
                        + "  or  /chunky shape square + /chunky radius " + a.squareRadius);
            }
        } else {
            sender.sendMessage(P + "§eChunky: /chunky world " + world + " | /chunky center " + cx + " " + cz
                    + " | /chunky shape circle | /chunky radius " + a.circleRadius);
        }
    }

    private boolean usage(CommandSender sender, String label) {
        sender.sendMessage(P + "Usage: /" + label + " <minTime> [chunky | spawn [world] | origin [world] | here | <x> <z> [world]] [--export]");
        sender.sendMessage(P + "minTime: e.g. 30s, 5m, 1h, 600t (ticks); a plain number means seconds.");
        sender.sendMessage(P + "Center: chunky = Chunky's selection, spawn = world spawn, origin = 0 0, here = your position.");
        sender.sendMessage(P + "Without a center, Chunky's selection is used (else your position / spawn).");
        sender.sendMessage(P + "--export also writes a CSV listing every generated chunk with its details.");
        sender.sendMessage(P + "/" + label + " version shows the installed version and checks for updates.");
        return true;
    }

    static final String EXPORT_FLAG = "--export";

    private static boolean hasFlag(String[] args) {
        for (String a : args) if (a.equalsIgnoreCase(EXPORT_FLAG)) return true;
        return false;
    }

    private static String[] stripFlag(String[] args) {
        List<String> out = new ArrayList<>();
        for (String a : args) if (!a.equalsIgnoreCase(EXPORT_FLAG)) out.add(a);
        return out.toArray(new String[0]);
    }

    private static String mb(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0);
    }

    private static World defaultWorld(CommandSender sender) {
        return sender instanceof Player ? ((Player) sender).getWorld() : Bukkit.getWorlds().get(0);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] rawArgs) {
        List<String> options = new ArrayList<>();
        if (rawArgs.length == 0) return options;
        // complete as if --export weren't there (it may appear anywhere after minTime)
        String[] earlier = stripFlag(Arrays.copyOf(rawArgs, rawArgs.length - 1));
        String[] args = Arrays.copyOf(earlier, earlier.length + 1);
        args[args.length - 1] = rawArgs[rawArgs.length - 1];
        if (args.length >= 2 && !hasFlag(rawArgs)) options.add(EXPORT_FLAG);
        if (args.length == 1) {
            options.addAll(Arrays.asList("30s", "1m", "5m", "30m", "1h", "version"));
        } else if (args.length == 2) {
            options.addAll(Arrays.asList("chunky", "spawn", "origin", "here"));
            if (sender instanceof Player) options.add(String.valueOf(((Player) sender).getLocation().getBlockX()));
        } else if (args.length == 3) {
            String mode = args[1].toLowerCase(Locale.ROOT);
            if (mode.equals("spawn") || mode.equals("origin")) {
                for (World w : Bukkit.getWorlds()) options.add(w.getName());
            } else if (isInt(args[1]) && sender instanceof Player) {
                options.add(String.valueOf(((Player) sender).getLocation().getBlockZ()));
            }
        } else if (args.length == 4 && isInt(args[1])) {
            for (World w : Bukkit.getWorlds()) options.add(w.getName());
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        options.removeIf(o -> !o.toLowerCase(Locale.ROOT).startsWith(prefix));
        return options;
    }

    private static boolean isInt(String s) {
        try {
            Integer.parseInt(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void tell(CommandSender sender, String msg) {
        Bukkit.getScheduler().runTask(this, () -> sender.sendMessage(msg));
    }

    private static String fmt(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    private static File regionDir(World world) {
        File folder = world.getWorldFolder();
        String sub;
        if (world.getEnvironment() == World.Environment.NETHER) sub = "DIM-1/region";
        else if (world.getEnvironment() == World.Environment.THE_END) sub = "DIM1/region";
        else sub = "region";
        String[] candidates = {sub, "region", "DIM-1/region", "DIM1/region"};
        for (String c : candidates) {
            File f = new File(folder, c);
            if (f.isDirectory()) return f;
        }
        return null;
    }
}
