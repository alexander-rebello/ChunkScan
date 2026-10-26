package dev.chunkscan;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Repacks region files (.mca in region/, entities/, poi/): copies every chunk that the header still lists
 * byte for byte into a new file without gaps, so space left by deleted chunks is given back.
 * Chunk data is never decompressed or changed. Files without chunks are deleted. Files with an invalid
 * header entry are left untouched.
 *
 * Must only run while the server does not have the world loaded (ChunkScan runs it at startup).
 */
public final class RegionOptimizer {

    private static final int SECTOR = 4096;
    private static final Pattern REGION = Pattern.compile("r\\.-?\\d+\\.-?\\d+\\.mca");
    private static final String[] FOLDERS = {"region", "entities", "poi"};

    /** Totals of one run. */
    public static final class Stats {
        public long files, rewritten, deleted, skippedInvalid, unchanged;
        public long bytesBefore, bytesAfter;

        void add(Stats o) {
            files += o.files;
            rewritten += o.rewritten;
            deleted += o.deleted;
            skippedInvalid += o.skippedInvalid;
            unchanged += o.unchanged;
            bytesBefore += o.bytesBefore;
            bytesAfter += o.bytesAfter;
        }
    }

    private RegionOptimizer() {}

    /** Optimizes region/, entities/ and poi/ of a dimension folder. */
    public static Stats optimizeDimension(File dimensionDir, Consumer<String> log) {
        Stats total = new Stats();
        for (String folder : FOLDERS) {
            File dir = new File(dimensionDir, folder);
            File[] leftovers = dir.listFiles((d, n) -> n.endsWith(".chunkscan-tmp")); // from an interrupted run
            if (leftovers != null) for (File f : leftovers) f.delete();
            File[] files = dir.listFiles((d, n) -> REGION.matcher(n).matches());
            if (files == null || files.length == 0) continue;
            Arrays.sort(files);
            log.accept("Optimizing " + files.length + " files in " + dir.getPath() + " ...");
            Stats s = new Stats();
            int step = Math.max(1, files.length / 10);
            for (int i = 0; i < files.length; i++) {
                long len = files[i].length();
                s.files++;
                s.bytesBefore += len;
                try {
                    optimizeFile(files[i], s);
                } catch (IOException e) {
                    s.skippedInvalid++;
                    s.bytesAfter += len; // left as it was
                    log.accept("  Skipped " + files[i].getName() + ": " + e.getMessage());
                }
                if ((i + 1) % step == 0 && i + 1 < files.length) {
                    log.accept("  " + (100 * (i + 1) / files.length) + "% (" + (i + 1) + "/" + files.length + ")");
                }
            }
            log.accept("  " + folder + ": " + SizeForecast.human(s.bytesBefore) + " -> " + SizeForecast.human(s.bytesAfter)
                    + " (" + s.rewritten + " repacked, " + s.deleted + " empty deleted, " + s.unchanged + " already compact"
                    + (s.skippedInvalid > 0 ? ", " + s.skippedInvalid + " skipped" : "") + ")");
            total.add(s);
        }
        return total;
    }

    /**
     * Repacks one region file; adds the outcome (and bytesAfter) to stats.
     * Throws, leaving the file untouched, on invalid data.
     */
    static void optimizeFile(File file, Stats stats) throws IOException {
        byte[] data = Files.readAllBytes(file.toPath());

        if (data.length == 0) { // never written: empty file, nothing to keep
            Files.delete(file.toPath());
            stats.deleted++;
            return;
        }
        if (data.length < 2 * SECTOR) throw new IOException("file shorter than its header");

        int[] start = new int[1024], length = new int[1024]; // byte offset and length (incl. 4-byte prefix)
        int chunks = 0;
        long usedSectors = 2;
        for (int i = 0; i < 1024; i++) {
            int loc = readInt(data, i * 4);
            if (loc == 0) continue;
            long pos = (long) (loc >>> 8) * SECTOR;
            int sectors = loc & 0xFF;
            if ((loc >>> 8) < 2 || sectors == 0 || pos + 5 > data.length) throw new IOException("invalid entry for chunk " + i);
            int len = readInt(data, (int) pos);
            if (len < 1 || pos + 4 + len > data.length || 4 + len > (long) sectors * SECTOR) {
                throw new IOException("invalid length for chunk " + i);
            }
            start[i] = (int) pos;
            length[i] = 4 + len;
            usedSectors += sectorsFor(length[i]);
            chunks++;
        }

        if (chunks == 0) {
            Files.delete(file.toPath());
            stats.deleted++;
            return;
        }
        long packedSize = usedSectors * SECTOR;
        if (packedSize >= data.length) { // nothing to gain
            stats.unchanged++;
            stats.bytesAfter += data.length;
            return;
        }

        byte[] out = new byte[(int) packedSize];
        System.arraycopy(data, SECTOR, out, SECTOR, SECTOR); // timestamps table
        int sector = 2;
        for (int i = 0; i < 1024; i++) {
            if (length[i] == 0) {
                writeInt(out, SECTOR + i * 4, 0); // no chunk, no timestamp
                continue;
            }
            int n = sectorsFor(length[i]);
            if (n > 255) throw new IOException("chunk " + i + " needs " + n + " sectors");
            System.arraycopy(data, start[i], out, sector * SECTOR, length[i]);
            writeInt(out, i * 4, (sector << 8) | n);
            sector += n;
        }
        verify(data, start, length, out);

        File tmp = new File(file.getParentFile(), file.getName() + ".chunkscan-tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            fos.write(out);
            fos.getFD().sync();
        }
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        stats.rewritten++;
        stats.bytesAfter += out.length;
    }

    /** Checks that every chunk in the new file is byte-identical to the original before the original is replaced. */
    private static void verify(byte[] original, int[] start, int[] length, byte[] packed) throws IOException {
        for (int i = 0; i < 1024; i++) {
            int loc = readInt(packed, i * 4);
            if (length[i] == 0) {
                if (loc != 0) throw new IOException("verification failed: unexpected chunk " + i);
                continue;
            }
            int pos = (loc >>> 8) * SECTOR;
            if (!Arrays.equals(original, start[i], start[i] + length[i], packed, pos, pos + length[i])) {
                throw new IOException("verification failed for chunk " + i);
            }
        }
    }

    private static int sectorsFor(int bytes) {
        return (bytes + SECTOR - 1) / SECTOR;
    }

    private static int readInt(byte[] b, int p) {
        return ((b[p] & 0xFF) << 24) | ((b[p + 1] & 0xFF) << 16) | ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
    }

    private static void writeInt(byte[] b, int p, int v) {
        b[p] = (byte) (v >>> 24);
        b[p + 1] = (byte) (v >>> 16);
        b[p + 2] = (byte) (v >>> 8);
        b[p + 3] = (byte) v;
    }
}
