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
import org.bukkit.plugin.java.JavaPlugin;

public final class ChunkScanPlugin extends JavaPlugin {

    private static final String P = "§6[ChunkScan] §r";
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 1 || args.length > 4) return usage(sender, label);

        long thresholdTicks;
        try {
            thresholdTicks = TimeParse.toTicks(args[0]);
        } catch (NumberFormatException e) {
            sender.sendMessage(P + "\u00a7cInvalid time: " + args[0]);
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
                    sender.sendMessage(P + "\u00a7cCould not read Chunky's selection (is Chunky installed and enabled?).");
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
                    sender.sendMessage(P + "\u00a7c'here' only works for players.");
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
                    sender.sendMessage(P + "\u00a7cWorld not found: " + args[2]);
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
                    sender.sendMessage(P + "\u00a7cWorld not found: " + args[3]);
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

        // flush loaded chunks so the files contain current InhabitedTime values
        sender.sendMessage(P + "Saving " + world.getName() + "...");
        world.save();

        int threads = Math.max(1, Runtime.getRuntime().availableProcessors());
        int regionCount = RegionScanner.listRegionFiles(regionDir).length;
        sender.sendMessage(P + "Center " + cx + ", " + cz + " in " + world.getName() + " from " + source + ".");
        sender.sendMessage(P + "Scanning " + regionCount + " region files on " + threads
                + " threads, min time " + TimeParse.format(thresholdTicks) + ". The server may lag until this finishes.");

        final int fcx = cx, fcz = cz;
        final World fworld = world;
        final long fthreshold = thresholdTicks;
        final String fshape = chunkyShape;
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                long start = System.currentTimeMillis();
                int step = Math.max(1, regionCount / 10);
                RegionScanner.Result r = RegionScanner.scan(regionDir, threads, done -> {
                    if (done % step == 0 && done < regionCount) {
                        int pct = (int) (100L * done / Math.max(1, regionCount));
                        tell(sender, P + "§7" + pct + "% (" + done + "/" + regionCount + ")");
                    }
                });
                Analysis a = new Analysis(r, fcx, fcz, fthreshold);

                File dir = getDataFolder();
                dir.mkdirs();
                String base = fworld.getName() + "-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
                File csv = new File(dir, base + ".csv");
                File png = new File(dir, base + ".png");
                Analysis.writeCsv(r, csv);
                a.writeHeatmap(r, png, 4096);

                long secs = (System.currentTimeMillis() - start) / 1000;
                List<String> lines = a.summary(r);
                Bukkit.getScheduler().runTask(this, () -> {
                    sender.sendMessage(P + "§aDone in " + secs + "s.");
                    for (String l : lines) sender.sendMessage(P + l);
                    if (a.qualifying > 0) suggestChunky(sender, a, fworld.getName(), fcx, fcz, fshape);
                    sender.sendMessage(P + "Files: plugins/" + dir.getName() + "/" + csv.getName()
                            + " and " + png.getName());
                });
            } catch (Throwable t) {
                getLogger().severe("Scan failed: " + t);
                t.printStackTrace();
                tell(sender, P + "§cScan failed: " + t.getMessage());
            } finally {
                running.set(false);
            }
        });
        return true;
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
        sender.sendMessage(P + "Usage: /" + label + " <minTime> [chunky | spawn [world] | origin [world] | here | <x> <z> [world]]");
        sender.sendMessage(P + "minTime: e.g. 30s, 5m, 1h, 600t (ticks); a plain number means seconds.");
        sender.sendMessage(P + "Center: chunky = Chunky's selection, spawn = world spawn, origin = 0 0, here = your position.");
        sender.sendMessage(P + "Without a center, Chunky's selection is used (else your position / spawn).");
        return true;
    }

    private static World defaultWorld(CommandSender sender) {
        return sender instanceof Player ? ((Player) sender).getWorld() : Bukkit.getWorlds().get(0);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            options.addAll(Arrays.asList("30s", "1m", "5m", "30m", "1h"));
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
