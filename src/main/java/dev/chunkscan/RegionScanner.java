package dev.chunkscan;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Reads InhabitedTime of every chunk straight from the .mca region files.
 * No chunks are loaded; this is pure file reading, so it is safe to run async.
 */
public final class RegionScanner {

    private static final Pattern REGION_NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

    /** One region: which of its 1024 chunks exist on disk. */
    public static final class Region {
        public final int rx, rz;
        public final long[] present = new long[16]; // 1024 bits

        Region(int rx, int rz) { this.rx = rx; this.rz = rz; }

        boolean has(int i) { return (present[i >>> 6] & (1L << (i & 63))) != 0; }
        void set(int i) { present[i >>> 6] |= 1L << (i & 63); }
        boolean isEmpty() { for (long l : present) if (l != 0) return false; return true; }
    }

    /** Everything a scan found. Visited = InhabitedTime > 0. */
    public static final class Result {
        public final List<Region> regions = new ArrayList<>();
        public int[] visitedX = new int[1024], visitedZ = new int[1024];
        public long[] visitedTicks = new long[1024];
        public int visitedCount;
        public long totalChunks, parsed, unreadable, lz4Skipped;

        synchronized void addRegion(Region r, int[] vx, int[] vz, long[] vt, int n,
                                    long total, long ok, long bad, long lz4) {
            regions.add(r);
            totalChunks += total;
            parsed += ok;
            unreadable += bad;
            lz4Skipped += lz4;
            while (visitedCount + n > visitedX.length) {
                int cap = visitedX.length * 2;
                visitedX = java.util.Arrays.copyOf(visitedX, cap);
                visitedZ = java.util.Arrays.copyOf(visitedZ, cap);
                visitedTicks = java.util.Arrays.copyOf(visitedTicks, cap);
            }
            System.arraycopy(vx, 0, visitedX, visitedCount, n);
            System.arraycopy(vz, 0, visitedZ, visitedCount, n);
            System.arraycopy(vt, 0, visitedTicks, visitedCount, n);
            visitedCount += n;
        }
    }

    public static File[] listRegionFiles(File regionDir) {
        File[] files = regionDir.listFiles((d, name) -> REGION_NAME.matcher(name).matches());
        return files == null ? new File[0] : files;
    }

    /**
     * Scans all region files in the directory.
     * @param progress called with the number of finished region files (from worker threads)
     */
    public static Result scan(File regionDir, int threads, IntConsumer progress) throws InterruptedException {
        File[] files = listRegionFiles(regionDir);
        Result result = new Result();
        AtomicInteger done = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads), r -> {
            Thread t = new Thread(r, "ChunkScan-worker");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (File f : files) {
                futures.add(pool.submit(() -> {
                    scanRegion(f, result);
                    progress.accept(done.incrementAndGet());
                }));
            }
            for (Future<?> fu : futures) {
                try {
                    fu.get();
                } catch (java.util.concurrent.ExecutionException e) {
                    // scanRegion catches its own IO errors; anything here is a bug, count and continue
                    synchronized (result) { result.unreadable++; }
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return result;
    }

    static void scanRegion(File file, Result result) {
        Matcher m = REGION_NAME.matcher(file.getName());
        if (!m.matches()) return;
        int rx = Integer.parseInt(m.group(1));
        int rz = Integer.parseInt(m.group(2));
        Region region = new Region(rx, rz);

        byte[] data;
        try {
            data = Files.readAllBytes(file.toPath());
        } catch (IOException e) {
            result.addRegion(region, new int[0], new int[0], new long[0], 0, 0, 0, 1, 0);
            return;
        }
        if (data.length < 8192) { // empty / brand new region file
            result.addRegion(region, new int[0], new int[0], new long[0], 0, 0, 0, 0, 0);
            return;
        }

        int[] vx = new int[1024], vz = new int[1024];
        long[] vt = new long[1024];
        int n = 0;
        long total = 0, ok = 0, bad = 0, lz4 = 0;

        for (int i = 0; i < 1024; i++) {
            int loc = readInt(data, i * 4);
            if (loc == 0) continue;
            int sectorOffset = loc >>> 8;
            int cx = rx * 32 + (i & 31);
            int cz = rz * 32 + (i >>> 5);
            long pos = (long) sectorOffset * 4096;
            if (sectorOffset < 2 || pos + 5 > data.length) { bad++; continue; }

            int length = readInt(data, (int) pos);
            int type = data[(int) pos + 4] & 0xFF;
            boolean external = (type & 0x80) != 0;
            type &= 0x7F;

            byte[] payload;
            int off, len;
            if (external) {
                File mcc = new File(file.getParentFile(), "c." + cx + "." + cz + ".mcc");
                try {
                    payload = Files.readAllBytes(mcc.toPath());
                } catch (IOException e) { bad++; continue; }
                off = 0;
                len = payload.length;
            } else {
                if (length < 1 || pos + 4 + length > data.length) { bad++; continue; }
                payload = data;
                off = (int) pos + 5;
                len = length - 1;
            }

            total++;
            region.set(i);

            if (type == 4) { lz4++; continue; } // LZ4 needs an extra library; rare (non-default setting)
            long ticks;
            try (InputStream raw = new ByteArrayInputStream(payload, off, len);
                 InputStream in = decompress(raw, type)) {
                if (in == null) { bad++; continue; }
                ticks = Nbt.findInhabitedTime(new DataInputStream(new BufferedInputStream(in, 16384)));
            } catch (IOException | RuntimeException e) {
                bad++;
                continue;
            }
            ok++;
            if (ticks > 0) {
                vx[n] = cx;
                vz[n] = cz;
                vt[n] = ticks;
                n++;
            }
        }
        result.addRegion(region, vx, vz, vt, n, total, ok, bad, lz4);
    }

    private static InputStream decompress(InputStream raw, int type) throws IOException {
        switch (type) {
            case 1: return new GZIPInputStream(raw);
            case 2: return new InflaterInputStream(raw);
            case 3: return raw;
            default: return null;
        }
    }

    private static int readInt(byte[] b, int p) {
        return ((b[p] & 0xFF) << 24) | ((b[p + 1] & 0xFF) << 16) | ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
    }
}
