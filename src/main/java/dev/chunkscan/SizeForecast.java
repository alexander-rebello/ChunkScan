package dev.chunkscan;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Predicts how much disk space a Chunky trim frees.
 *
 * Chunky's trim (outside a shape, without an inhabited-time filter) deletes a region file together with its
 * poi and entities files only when every chunk center in that region lies outside the shape. In region files
 * on the border it only removes the chunk entries; those files keep their size. So the forecast is: current
 * size minus all files of regions that lie completely outside.
 */
public final class SizeForecast {

    /** A trim shape around a center, tested like Chunky does (on chunk centers). */
    public interface Shape {
        boolean contains(double x, double z);

        static Shape circle(double cx, double cz, double r) {
            return (x, z) -> Math.hypot(cx - x, cz - z) <= r;
        }

        static Shape square(double cx, double cz, double r) {
            return (x, z) -> x >= cx - r && x <= cx + r && z >= cz - r && z <= cz + r;
        }
    }

    private static final Pattern REGION = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    private static final Pattern MCC = Pattern.compile("c\\.(-?\\d+)\\.(-?\\d+)\\.mcc");
    private static final String[] FOLDERS = {"region", "entities", "poi"};

    /** Bytes per region (key rx,rz packed) across region/, entities/, poi/ incl. external chunk files. */
    private final Map<Long, Long> bytesPerRegion = new HashMap<>();
    /** Regions that have a file in region/ (only those are trimmed by Chunky). */
    private final java.util.Set<Long> trimmable = new java.util.HashSet<>();
    /** Size of just region/r.x.z.mca per region. */
    private final Map<Long, Long> regionFileBytes = new HashMap<>();
    public long dimensionBytes; // region + entities + poi, current

    public long afterBytes;      // filled by forecast()
    public long deletedRegionFiles;

    private SizeForecast() {}

    /** Reads file sizes of region/, entities/ and poi/ in a dimension folder. */
    public static SizeForecast measure(File dimensionDir) {
        SizeForecast f = new SizeForecast();
        for (String folder : FOLDERS) {
            File[] files = new File(dimensionDir, folder).listFiles();
            if (files == null) continue;
            for (File file : files) {
                if (!file.isFile()) continue;
                long size = file.length();
                f.dimensionBytes += size;
                Matcher r = REGION.matcher(file.getName());
                Matcher c = MCC.matcher(file.getName());
                long key;
                if (r.matches()) {
                    key = key(Integer.parseInt(r.group(1)), Integer.parseInt(r.group(2)));
                    if (folder.equals("region")) {
                        f.trimmable.add(key);
                        f.regionFileBytes.put(key, size);
                    }
                } else if (c.matches()) {
                    key = key(Math.floorDiv(Integer.parseInt(c.group(1)), 32), Math.floorDiv(Integer.parseInt(c.group(2)), 32));
                } else {
                    continue;
                }
                f.bytesPerRegion.merge(key, size, Long::sum);
            }
        }
        return f;
    }

    /** Predicted size after trimming everything outside the shape; also sets afterBytes and deletedRegionFiles. */
    public long forecast(Shape shape) {
        long freed = 0, deleted = 0;
        for (long key : trimmable) {
            int rx = (int) (key >> 32), rz = (int) key;
            if (fullyOutside(rx, rz, shape)) {
                freed += bytesPerRegion.getOrDefault(key, 0L);
                deleted++;
            }
        }
        afterBytes = dimensionBytes - freed;
        deletedRegionFiles = deleted;
        return afterBytes;
    }

    /** Expected sizes for one way of trimming. */
    public static final class Outcome {
        public long afterTrim;       // chunk data after Chunky's trim
        public long afterOptimize;   // chunk data after trim + /chunkscan optimize (region files repacked)
        public long deletedRegions;  // region files Chunky deletes completely
    }

    /** Decides per chunk whether it survives a trim. */
    public interface ChunkKeep {
        boolean keep(int chunkX, int chunkZ);
    }

    /**
     * Forecast for a trim that keeps the chunks matching {@code keep}.
     * @param regionGone Chunky's rule for deleting a whole region (with its poi/entities files)
     *
     * Optimized size counts only region/ files (header + sectors of kept chunks); entities/ and poi/ files of
     * kept regions are counted at their current size, so the optimize figure is a little conservative.
     */
    public Outcome outcome(RegionScanner.Result r, ChunkKeep keep, java.util.function.BiPredicate<Integer, Integer> regionGone) {
        Map<Long, RegionScanner.Region> scanned = new HashMap<>();
        for (RegionScanner.Region reg : r.regions) scanned.put(key(reg.rx, reg.rz), reg);
        Outcome o = new Outcome();
        long freed = 0, optimizeSaves = 0;
        for (long key : trimmable) {
            int rx = (int) (key >> 32), rz = (int) key;
            if (regionGone.test(rx, rz)) {
                freed += bytesPerRegion.getOrDefault(key, 0L);
                o.deletedRegions++;
                continue;
            }
            RegionScanner.Region reg = scanned.get(key);
            long current = regionFileBytes.getOrDefault(key, 0L);
            if (reg == null) continue;
            long sectors = 0;
            for (int i = 0; i < 1024; i++) {
                if (reg.has(i) && keep.keep(rx * 32 + (i & 31), rz * 32 + (i >>> 5))) sectors += reg.sectors(i);
            }
            long packed = sectors == 0 ? 0 : 8192 + sectors * 4096;
            if (packed < current) optimizeSaves += current - packed;
        }
        o.afterTrim = dimensionBytes - freed;
        o.afterOptimize = o.afterTrim - optimizeSaves;
        return o;
    }

    /** Trim outside a shape (Chunky's radius trim). */
    public Outcome shapeOutcome(RegionScanner.Result r, Shape shape) {
        return outcome(r, (x, z) -> shape.contains((x << 4) + 8, (z << 4) + 8), (rx, rz) -> fullyOutside(rx, rz, shape));
    }

    /**
     * Trim of every chunk with InhabitedTime <= threshold (Chunky's trim with an inhabited-time filter):
     * a region file is deleted when none of its chunks is above the threshold.
     */
    public Outcome inhabitedOutcome(RegionScanner.Result r, long thresholdTicks) {
        java.util.Set<Long> above = new java.util.HashSet<>();
        java.util.Set<Long> regionsWithAbove = new java.util.HashSet<>();
        for (int i = 0; i < r.visitedCount; i++) {
            if (r.visitedTicks[i] <= thresholdTicks) continue;
            above.add(key(r.visitedX[i], r.visitedZ[i]));
            regionsWithAbove.add(key(Math.floorDiv(r.visitedX[i], 32), Math.floorDiv(r.visitedZ[i], 32)));
        }
        return outcome(r, (x, z) -> above.contains(key(x, z)), (rx, rz) -> !regionsWithAbove.contains(key(rx, rz)));
    }

    /** Same test as Chunky: a region goes only if no chunk center in it is inside the shape. */
    static boolean fullyOutside(int rx, int rz, Shape shape) {
        for (int x = 0; x < 32; x++) {
            for (int z = 0; z < 32; z++) {
                if (shape.contains(((rx * 32 + x) << 4) + 8, ((rz * 32 + z) << 4) + 8)) return false;
            }
        }
        return true;
    }

    /** Total size of all files below a folder (e.g. the whole world folder). */
    public static long folderBytes(File dir) {
        try (Stream<Path> s = Files.walk(dir.toPath())) {
            return s.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }

    public static String human(long bytes) {
        if (bytes < 0) return "?";
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        double v = bytes;
        int u = 0;
        while (v >= 1024 && u < units.length - 1) {
            v /= 1024;
            u++;
        }
        return String.format(java.util.Locale.ROOT, u == 0 ? "%.0f %s" : "%.1f %s", v, units[u]);
    }

    private static long key(int rx, int rz) {
        return ((long) rx << 32) | (rz & 0xFFFFFFFFL);
    }
}
