package dev.chunkscan;

import java.io.File;
import java.text.SimpleDateFormat;
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
            sender.sendMessage(P + "§cInvalid time: " + args[0]);
            return true;
        }

        // Resolve center + world: explicit x z > "here" > Chunky selection > own position > spawn
        World world = null;
        int cx, cz;
        String source;
        String chunkyShape = null;
        if (args.length >= 3) {
            try {
                cx = Integer.parseInt(args[1]);
                cz = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                sender.sendMessage(P + "§cx and z must be whole numbers.");
                return true;
            }
            if (args.length == 4) {
                world = Bukkit.getWorld(args[3]);
                if (world == null) {
                    sender.sendMessage(P + "§cWorld not found: " + args[3]);
                    return true;
                }
            }
            source = "given coordinates";
        } else if (args.length == 2 && !args[1].equalsIgnoreCase("here")) {
            return usage(sender, label);
        } else {
            ChunkyBridge.Selection sel = args.length == 2 ? null : ChunkyBridge.read();
            if (sel != null && Bukkit.getWorld(sel.world) != null) {
                world = Bukkit.getWorld(sel.world);
                cx = (int) Math.floor(sel.centerX);
                cz = (int) Math.floor(sel.centerZ);
                chunkyShape = sel.shape.toLowerCase(Locale.ROOT);
                source = "Chunky selection (shape " + chunkyShape + ", current radius " + fmt(sel.radiusX) + ")";
            } else {
                Location loc = sender instanceof Player
                        ? ((Player) sender).getLocation()
                        : Bukkit.getWorlds().get(0).getSpawnLocation();
                world = loc.getWorld();
                cx = loc.getBlockX();
                cz = loc.getBlockZ();
                source = sender instanceof Player ? "your position" : "world spawn";
            }
        }
        if (world == null) {
            world = sender instanceof Player ? ((Player) sender).getWorld() : Bukkit.getWorlds().get(0);
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
        sender.sendMessage(P + "Usage: /" + label + " <minTime> [here | <x> <z> [world]]");
        sender.sendMessage(P + "minTime: e.g. 30s, 5m, 1h, 600t (ticks); a plain number means seconds.");
        sender.sendMessage(P + "Without a center, Chunky's selection is used (else your position / spawn).");
        return true;
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
