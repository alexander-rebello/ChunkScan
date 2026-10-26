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
                    if (folder.equals("region")) f.trimmable.add(key);
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
