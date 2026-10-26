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
        /** 4 KiB sectors each chunk occupies in the region file (from the header), 0 if absent. */
        public final byte[] sectors = new byte[1024];
        public long fileLength;

        Region(int rx, int rz) { this.rx = rx; this.rz = rz; }

        public int sectors(int i) { return sectors[i] & 0xFF; }

        boolean has(int i) { return (present[i >>> 6] & (1L << (i & 63))) != 0; }
        void set(int i) { present[i >>> 6] |= 1L << (i & 63); }
        boolean isEmpty() { for (long l : present) if (l != 0) return false; return true; }
    }

    /** One chunk as found on disk, handed to a {@link ChunkSink} when exporting. */
    public static final class ChunkRecord {
        public int chunkX, chunkZ;
        public String region;
        public String compression;     // gzip, zlib, none, lz4, custom; "+external" if stored in a .mcc file
        public long sizeBytes;         // bytes on disk: allocated 4 KiB sectors, or the .mcc file size
        public boolean readable;       // false if the chunk could not be decoded (see error)
        public String error;           // null if fine
        public long inhabitedTicks;
        public int dataVersion = -1;
        public long lastUpdate = -1;
        public String status;
    }

    /** Receives all chunks of one region file at a time; called from worker threads. */
    public interface ChunkSink {
        void accept(List<ChunkRecord> chunks) throws IOException;
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
        return scan(regionDir, threads, null, progress);
    }

    /**
     * @param sink if not null, every chunk (including unreadable ones) is passed to it with full details;
     *             this reads the whole chunk NBT and is therefore slower
     */
    public static Result scan(File regionDir, int threads, ChunkSink sink, IntConsumer progress) throws InterruptedException {
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
                    scanRegion(f, result, sink);
                    progress.accept(done.incrementAndGet());
                    return null;
                }));
            }
            for (Future<?> fu : futures) {
                try {
                    fu.get();
                } catch (java.util.concurrent.ExecutionException e) {
                    // scanRegion catches chunk errors itself; this is a sink (export file) failure or a bug
                    Throwable cause = e.getCause();
                    if (cause instanceof IOException) throw new IllegalStateException("Writing export failed: " + cause.getMessage(), cause);
                    synchronized (result) { result.unreadable++; }
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return result;
    }

    static void scanRegion(File file, Result result, ChunkSink sink) throws IOException {
        Matcher m = REGION_NAME.matcher(file.getName());
        if (!m.matches()) return;
        int rx = Integer.parseInt(m.group(1));
        int rz = Integer.parseInt(m.group(2));
        Region region = new Region(rx, rz);
        String regionName = file.getName();

        byte[] data;
        try {
            data = Files.readAllBytes(file.toPath());
        } catch (IOException e) {
            result.addRegion(region, new int[0], new int[0], new long[0], 0, 0, 0, 1, 0);
            return;
        }
        region.fileLength = data.length;
        if (data.length < 8192) { // empty / brand new region file
            result.addRegion(region, new int[0], new int[0], new long[0], 0, 0, 0, 0, 0);
            return;
        }

        int[] vx = new int[1024], vz = new int[1024];
        long[] vt = new long[1024];
        int n = 0;
        long total = 0, ok = 0, bad = 0, lz4 = 0;
        List<ChunkRecord> records = sink == null ? null : new ArrayList<>();

        for (int i = 0; i < 1024; i++) {
            int loc = readInt(data, i * 4);
            if (loc == 0) continue;
            int sectorOffset = loc >>> 8;
            int sectors = loc & 0xFF;
            int cx = rx * 32 + (i & 31);
            int cz = rz * 32 + (i >>> 5);
            ChunkRecord rec = null;
            if (records != null) {
                rec = new ChunkRecord();
                rec.chunkX = cx;
                rec.chunkZ = cz;
                rec.region = regionName;
                rec.sizeBytes = sectors * 4096L;
                records.add(rec);
            }

            long pos = (long) sectorOffset * 4096;
            if (sectorOffset < 2 || pos + 5 > data.length) {
                bad++;
                if (rec != null) rec.error = "chunk offset points outside the region file";
                continue;
            }

            int length = readInt(data, (int) pos);
            int type = data[(int) pos + 4] & 0xFF;
            boolean external = (type & 0x80) != 0;
            type &= 0x7F;
            if (rec != null) rec.compression = compressionName(type) + (external ? "+external" : "");

            byte[] payload;
            int off, len;
            if (external) {
                File mcc = new File(file.getParentFile(), "c." + cx + "." + cz + ".mcc");
                try {
                    payload = Files.readAllBytes(mcc.toPath());
                } catch (IOException e) {
                    bad++;
                    if (rec != null) rec.error = "external chunk file " + mcc.getName() + " missing";
                    continue;
                }
                off = 0;
                len = payload.length;
                if (rec != null) rec.sizeBytes += payload.length;
            } else {
                if (length < 1 || pos + 4 + length > data.length) {
                    bad++;
                    if (rec != null) rec.error = "chunk length invalid";
                    continue;
                }
                payload = data;
                off = (int) pos + 5;
                len = length - 1;
            }

            total++;
            region.set(i);
            region.sectors[i] = (byte) sectors;

            if (type == 4) { // LZ4 needs an extra library; rare (non-default setting)
                lz4++;
                if (rec != null) rec.error = "LZ4 compression not supported";
                continue;
            }
            Nbt.ChunkData chunk;
            try (InputStream raw = new ByteArrayInputStream(payload, off, len);
                 InputStream in = decompress(raw, type)) {
                if (in == null) {
                    bad++;
                    if (rec != null) rec.error = "unknown compression type " + type;
                    continue;
                }
                chunk = Nbt.read(new DataInputStream(new BufferedInputStream(in, 16384)), rec != null);
            } catch (IOException | RuntimeException e) {
                bad++;
                if (rec != null) rec.error = "corrupt chunk data: " + e.getClass().getSimpleName();
                continue;
            }
            ok++;
            if (rec != null) {
                rec.readable = true;
                rec.inhabitedTicks = chunk.inhabitedTime;
                rec.dataVersion = chunk.dataVersion;
                rec.lastUpdate = chunk.lastUpdate;
                rec.status = chunk.status;
            }
            if (chunk.inhabitedTime > 0) {
                vx[n] = cx;
                vz[n] = cz;
                vt[n] = chunk.inhabitedTime;
                n++;
            }
        }
        result.addRegion(region, vx, vz, vt, n, total, ok, bad, lz4);
        if (sink != null && !records.isEmpty()) sink.accept(records);
    }

    private static String compressionName(int type) {
        switch (type) {
            case 1: return "gzip";
            case 2: return "zlib";
            case 3: return "none";
            case 4: return "lz4";
            case 127: return "custom";
            default: return "unknown(" + type + ")";
        }
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
