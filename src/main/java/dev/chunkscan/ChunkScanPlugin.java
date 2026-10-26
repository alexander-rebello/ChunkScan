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

    private static final String PENDING = "optimize-pending.txt";

    /** Runs before the worlds are loaded: the only safe moment to rewrite their region files. */
    @Override
    public void onLoad() {
        File pending = new File(getDataFolder(), PENDING);
        if (!pending.isFile()) return;
        try {
            List<String> dims = java.nio.file.Files.readAllLines(pending.toPath());
            java.nio.file.Files.delete(pending.toPath()); // run once, even if something fails below
            for (String path : dims) {
                if (path.isBlank()) continue;
                File dim = new File(path.trim());
                getLogger().info("Optimizing region files of " + dim.getPath() + " before the world loads...");
                long start = System.currentTimeMillis();
                RegionOptimizer.Stats st = RegionOptimizer.optimizeDimension(dim, getLogger()::info);
                getLogger().info("Done in " + (System.currentTimeMillis() - start) / 1000 + "s: "
                        + SizeForecast.human(st.bytesBefore) + " -> " + SizeForecast.human(st.bytesAfter)
                        + " (saved " + SizeForecast.human(st.bytesBefore - st.bytesAfter) + ", " + st.rewritten + " files repacked, "
                        + st.deleted + " empty files deleted" + (st.skippedInvalid > 0 ? ", " + st.skippedInvalid + " skipped, see above" : "") + ")");
            }
        } catch (Exception e) {
            getLogger().severe("Optimizing failed: " + e);
        }
    }

    private boolean optimizeCommand(CommandSender sender, String[] args) {
        File pending = new File(getDataFolder(), PENDING);
        if (args.length == 2 && args[1].equalsIgnoreCase("cancel")) {
            sender.sendMessage(P + (pending.delete() ? "Cancelled the scheduled optimization." : "Nothing was scheduled."));
            return true;
        }
        if (args.length > 2) {
            sender.sendMessage(P + "Usage: /chunkscan optimize [world] | /chunkscan optimize cancel");
            return true;
        }
        World world = args.length == 2 ? Bukkit.getWorld(args[1]) : defaultWorld(sender);
        if (world == null) {
            sender.sendMessage(P + "§cWorld not found: " + args[1]);
            return true;
        }
        File regionDir = regionDir(world);
        if (regionDir == null) {
            sender.sendMessage(P + "§cCould not find the region folder of " + world.getName());
            return true;
        }
        String dim = regionDir.getParentFile().getAbsolutePath();
        try {
            getDataFolder().mkdirs();
            List<String> lines = pending.isFile() ? new ArrayList<>(java.nio.file.Files.readAllLines(pending.toPath())) : new ArrayList<>();
            if (!lines.contains(dim)) lines.add(dim);
            java.nio.file.Files.write(pending.toPath(), lines);
        } catch (java.io.IOException e) {
            sender.sendMessage(P + "§cCould not schedule: " + e.getMessage());
            return true;
        }
        sender.sendMessage(P + "§aScheduled: the region files of " + world.getName() + " are repacked on the next server start,"
                + " before the world loads.");
        sender.sendMessage(P + "Restart the server to run it (after a Chunky trim you need a restart anyway)."
                + " Make a backup first. Cancel with /chunkscan optimize cancel.");
        return true;
    }

    @Override
    public void onEnable() {
        if (new File(getDataFolder(), PENDING).isFile()) {
            getLogger().warning("An optimization is scheduled; it runs on the next server start.");
        }
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
        if (rawArgs.length >= 1 && rawArgs[0].equalsIgnoreCase("optimize")) return optimizeCommand(sender, rawArgs);
        boolean export = hasFlag(rawArgs);
        String[] args = stripFlag(rawArgs);
        if (!TimeParse.startsWithTime(args)) { // minTime is optional and defaults to 0
            String[] withTime = new String[args.length + 1];
            withTime[0] = "0";
            System.arraycopy(args, 0, withTime, 1, args.length);
            args = withTime;
        }
        if (args.length > 4) return usage(sender, label);
        long thresholdTicks = TimeParse.toTicks(args[0]);

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
        if (sender instanceof Player) {
            sender.sendMessage(P + "§eYou are running this in-game. Better leave the server and run it from the console:"
                    + " the scan makes the server lag, and trimming afterwards needs a restart anyway.");
        }
        int online = Bukkit.getOnlinePlayers().size();
        if (online > 0) {
            sender.sendMessage(P + "§eWarning: " + online + (online == 1 ? " player is" : " players are")
                    + " online. Run scans and trims when the server is empty.");
        }
        final ScanGuard guard = ScanGuard.begin(sender, getConfig().getBoolean("pause-chunky", true),
                getConfig().getStringList("auto-continue-plugins"));

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
                SizeForecast sizes = SizeForecast.measure(regionDir.getParentFile());
                long worldBytes = SizeForecast.folderBytes(fworld.getWorldFolder());
                List<String> lines = a.summary(r, sizes, worldBytes);
                List<String> options = a.trimOptions(r, sizes, worldBytes, fworld.getName(), fshape);
                final long rows = exportedRows;
                Bukkit.getScheduler().runTask(this, () -> {
                    sender.sendMessage(P + "§aDone in " + secs + "s.");
                    for (String l : lines) sender.sendMessage(P + l);
                    for (String l : options) sender.sendMessage(P + "§e" + l);
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


    private boolean usage(CommandSender sender, String label) {
        sender.sendMessage(P + "Usage: /" + label + " [minTime] [chunky | spawn [world] | origin [world] | here | <x> <z> [world]] [--export]");
        sender.sendMessage(P + "minTime: e.g. 30s, 5m, 1h, 600t (ticks); a plain number means seconds. Default: 0.");
        sender.sendMessage(P + "Center: chunky = Chunky's selection, spawn = world spawn, origin = 0 0, here = your position.");
        sender.sendMessage(P + "Without a center, Chunky's selection is used (else your position / spawn).");
        sender.sendMessage(P + "--export also writes a CSV listing every generated chunk with its details.");
        sender.sendMessage(P + "/" + label + " version shows the installed version and checks for updates.");
        sender.sendMessage(P + "/" + label + " optimize [world] repacks region files on the next server start (frees space after a trim).");
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
        if (rawArgs.length == 2 && rawArgs[0].equalsIgnoreCase("optimize")) {
            options.add("cancel");
            for (World w : Bukkit.getWorlds()) options.add(w.getName());
            String pre = rawArgs[1].toLowerCase(Locale.ROOT);
            options.removeIf(o -> !o.toLowerCase(Locale.ROOT).startsWith(pre));
            return options;
        }
        // complete as if --export weren't there (it may appear anywhere after minTime)
        String[] earlier = stripFlag(Arrays.copyOf(rawArgs, rawArgs.length - 1));
        if (earlier.length > 0 && !TimeParse.startsWithTime(earlier)) { // complete as if minTime 0 had been typed
            String[] withTime = new String[earlier.length + 1];
            withTime[0] = "0";
            System.arraycopy(earlier, 0, withTime, 1, earlier.length);
            earlier = withTime;
        }
        String[] args = Arrays.copyOf(earlier, earlier.length + 1);
        args[args.length - 1] = rawArgs[rawArgs.length - 1];
        if (args.length >= 2 && !hasFlag(rawArgs)) options.add(EXPORT_FLAG);
        if (args.length == 1) {
            options.addAll(Arrays.asList("30s", "1m", "5m", "30m", "1h", "chunky", "spawn", "origin", "here", "version", "optimize"));
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
