package dev.chunkscan;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * Writes one CSV row per generated chunk while the scan runs (rows arrive grouped by region file).
 * Thread-safe: workers hand over whole regions, which are formatted outside the lock and appended under it.
 */
public final class ChunkExport implements RegionScanner.ChunkSink, AutoCloseable {

    public static final String HEADER = "chunkX,chunkZ,minBlockX,minBlockZ,region,inhabitedTicks,inhabitedTime,"
            + "aboveThreshold,distanceToCenter,status,dataVersion,lastUpdateTick,compression,sizeOnDiskBytes,error\n";

    private final BufferedWriter writer;
    private final int centerX, centerZ;
    private final long thresholdTicks;
    private long rows;

    public ChunkExport(File file, int centerX, int centerZ, long thresholdTicks) throws IOException {
        this.writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8);
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.thresholdTicks = thresholdTicks;
        writer.write(HEADER);
    }

    @Override
    public void accept(List<RegionScanner.ChunkRecord> chunks) throws IOException {
        StringBuilder sb = new StringBuilder(chunks.size() * 120);
        for (RegionScanner.ChunkRecord c : chunks) {
            double dx = c.chunkX * 16 + 8 - centerX, dz = c.chunkZ * 16 + 8 - centerZ;
            sb.append(c.chunkX).append(',')
              .append(c.chunkZ).append(',')
              .append(c.chunkX * 16).append(',')
              .append(c.chunkZ * 16).append(',')
              .append(c.region).append(',');
            if (c.readable) {
                sb.append(c.inhabitedTicks).append(',')
                  .append(hms(c.inhabitedTicks)).append(',')
                  .append(c.inhabitedTicks > thresholdTicks).append(',');
            } else {
                sb.append(",,,");
            }
            sb.append(Math.round(Math.sqrt(dx * dx + dz * dz))).append(',')
              .append(c.status == null ? "" : stripNamespace(c.status)).append(',')
              .append(c.dataVersion < 0 ? "" : String.valueOf(c.dataVersion)).append(',')
              .append(c.lastUpdate < 0 ? "" : String.valueOf(c.lastUpdate)).append(',')
              .append(c.compression == null ? "" : c.compression).append(',')
              .append(c.sizeBytes).append(',')
              .append(c.error == null ? "" : csv(c.error)).append('\n');
        }
        synchronized (this) {
            writer.write(sb.toString());
            rows += chunks.size();
        }
    }

    public synchronized long rows() {
        return rows;
    }

    @Override
    public synchronized void close() throws IOException {
        writer.close();
    }

    /** Ticks as H:MM:SS (spreadsheets read this as a duration). */
    static String hms(long ticks) {
        long s = ticks / 20;
        return String.format(java.util.Locale.ROOT, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
    }

    private static String stripNamespace(String s) {
        return s.startsWith("minecraft:") ? s.substring("minecraft:".length()) : s;
    }

    private static String csv(String s) {
        return s.contains(",") || s.contains("\"") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }
}
