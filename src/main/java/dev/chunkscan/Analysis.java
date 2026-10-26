package dev.chunkscan;

import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/** Turns a scan result into the radius answer, a histogram, a CSV and a heatmap PNG. */
public final class Analysis {

    public static final long[] BUCKET_LIMITS = {0, 5 * 20, 30 * 20, 60 * 20, 5 * 60 * 20,
            30 * 60 * 20, 2 * 3600 * 20, Long.MAX_VALUE};
    public static final String[] BUCKET_NAMES = {"never (0)", "<= 5s", "5s - 30s", "30s - 1m",
            "1m - 5m", "5m - 30m", "30m - 2h", "> 2h"};

    public final int centerX, centerZ;
    public final long thresholdTicks;

    public long qualifying;          // chunks with InhabitedTime > threshold
    public int circleRadius = 0;     // smallest circle radius (blocks) containing every qualifying chunk fully
    public int squareRadius = 0;     // same for a square (half side length)
    public int circleFarX, circleFarZ, squareFarX, squareFarZ; // the chunk that sets each radius
    public long outsideCircle, outsideSquare; // generated chunks whose center lies outside that radius
    public final long[] bucketCounts = new long[BUCKET_LIMITS.length];

    public Analysis(RegionScanner.Result r, int centerX, int centerZ, long thresholdTicks) {
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.thresholdTicks = thresholdTicks;

        long maxCircleSq = -1, maxSquare = -1;
        for (int i = 0; i < r.visitedCount; i++) {
            long t = r.visitedTicks[i];
            bucketCounts[bucket(t)]++;
            if (t <= thresholdTicks) continue;
            qualifying++;
            int cx = r.visitedX[i], cz = r.visitedZ[i];
            // farthest block corner of this chunk from the center
            long dx = Math.max(Math.abs((long) cx * 16 - centerX), Math.abs((long) cx * 16 + 16 - centerX));
            long dz = Math.max(Math.abs((long) cz * 16 - centerZ), Math.abs((long) cz * 16 + 16 - centerZ));
            long sq = dx * dx + dz * dz;
            if (sq > maxCircleSq) { maxCircleSq = sq; circleFarX = cx; circleFarZ = cz; }
            long s = Math.max(dx, dz);
            if (s > maxSquare) { maxSquare = s; squareFarX = cx; squareFarZ = cz; }
        }
        bucketCounts[0] = r.parsed - r.visitedCount; // generated but never inhabited
        if (qualifying > 0) {
            circleRadius = (int) Math.ceil(Math.sqrt((double) maxCircleSq));
            squareRadius = (int) maxSquare;
        }

        // How much would be trimmed (chunk center outside the shape)
        double rc = circleRadius;
        for (RegionScanner.Region reg : r.regions) {
            for (int i = 0; i < 1024; i++) {
                if (!reg.has(i)) continue;
                int cx = reg.rx * 32 + (i & 31), cz = reg.rz * 32 + (i >>> 5);
                double mx = cx * 16 + 8 - centerX, mz = cz * 16 + 8 - centerZ;
                if (mx * mx + mz * mz > rc * rc) outsideCircle++;
                if (Math.max(Math.abs(mx), Math.abs(mz)) > squareRadius) outsideSquare++;
            }
        }
    }

    static int bucket(long ticks) {
        for (int b = 0; b < BUCKET_LIMITS.length; b++) if (ticks <= BUCKET_LIMITS[b]) return b;
        return BUCKET_LIMITS.length - 1;
    }

    /** CSV of every chunk with InhabitedTime > 0, sorted by time descending. */
    public static void writeCsv(RegionScanner.Result r, File file) throws IOException {
        Integer[] idx = new Integer[r.visitedCount];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        java.util.Arrays.sort(idx, (a, b) -> Long.compare(r.visitedTicks[b], r.visitedTicks[a]));
        try (BufferedWriter w = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
            w.write("chunkX,chunkZ,blockX,blockZ,inhabitedTicks,inhabitedSeconds\n");
            for (int i : idx) {
                int cx = r.visitedX[i], cz = r.visitedZ[i];
                long t = r.visitedTicks[i];
                w.write(cx + "," + cz + "," + (cx * 16 + 8) + "," + (cz * 16 + 8) + "," + t + "," + (t / 20) + "\n");
            }
        }
    }

    /**
     * Heatmap: 1 pixel per chunk (downscaled if the world is huge), north up.
     * Dark gray = generated but never visited, blue = visited but below threshold,
     * yellow -> red = above threshold (log scale), green outline = suggested circle, white cross = center.
     */
    public void writeHeatmap(RegionScanner.Result r, File file, int maxSize) throws IOException {
        if (r.totalChunks == 0) return;
        int minCx = Integer.MAX_VALUE, minCz = Integer.MAX_VALUE, maxCx = Integer.MIN_VALUE, maxCz = Integer.MIN_VALUE;
        for (RegionScanner.Region reg : r.regions) {
            if (reg.isEmpty()) continue;
            minCx = Math.min(minCx, reg.rx * 32); maxCx = Math.max(maxCx, reg.rx * 32 + 31);
            minCz = Math.min(minCz, reg.rz * 32); maxCz = Math.max(maxCz, reg.rz * 32 + 31);
        }
        int wChunks = maxCx - minCx + 1, hChunks = maxCz - minCz + 1;
        int scale = Math.max(1, (int) Math.ceil(Math.max(wChunks, hChunks) / (double) maxSize));
        int w = (wChunks + scale - 1) / scale, h = (hChunks + scale - 1) / scale;

        // level per pixel: 0 = nothing, 1 = generated, 2..127 below threshold, 128..255 above
        byte[] level = new byte[w * h];
        for (RegionScanner.Region reg : r.regions) {
            for (int i = 0; i < 1024; i++) {
                if (!reg.has(i)) continue;
                int px = (reg.rx * 32 + (i & 31) - minCx) / scale, pz = (reg.rz * 32 + (i >>> 5) - minCz) / scale;
                int p = pz * w + px;
                if ((level[p] & 0xFF) < 1) level[p] = 1;
            }
        }
        long maxTicks = 1;
        for (int i = 0; i < r.visitedCount; i++) maxTicks = Math.max(maxTicks, r.visitedTicks[i]);
        double logT = Math.log1p(thresholdTicks), logMax = Math.log1p(Math.max(maxTicks, thresholdTicks + 1));
        for (int i = 0; i < r.visitedCount; i++) {
            long t = r.visitedTicks[i];
            int lv;
            if (t <= thresholdTicks) {
                lv = 2 + (int) Math.round(125 * (logT <= 0 ? 1 : Math.log1p(t) / logT));
            } else {
                lv = 128 + (int) Math.round(127 * (Math.log1p(t) - logT) / Math.max(1e-9, logMax - logT));
            }
            lv = Math.min(255, Math.max(2, lv));
            int p = ((r.visitedZ[i] - minCz) / scale) * w + (r.visitedX[i] - minCx) / scale;
            if ((level[p] & 0xFF) < lv) level[p] = (byte) lv;
        }

        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int p = 0; p < level.length; p++) img.setRGB(p % w, p / w, color(level[p] & 0xFF));

        // center cross and suggested circle, in pixel space
        double cpx = (centerX / 16.0 - minCx) / scale, cpz = (centerZ / 16.0 - minCz) / scale;
        for (int d = -3; d <= 3; d++) {
            plot(img, (int) cpx + d, (int) cpz, 0xFFFFFF);
            plot(img, (int) cpx, (int) cpz + d, 0xFFFFFF);
        }
        if (circleRadius > 0) {
            double rp = circleRadius / 16.0 / scale;
            int steps = (int) Math.max(360, rp * 8);
            for (int s = 0; s < steps; s++) {
                double a = 2 * Math.PI * s / steps;
                plot(img, (int) Math.round(cpx + rp * Math.cos(a)), (int) Math.round(cpz + rp * Math.sin(a)), 0x22DD44);
            }
        }
        ImageIO.write(img, "png", file);
    }

    private static void plot(BufferedImage img, int x, int y, int rgb) {
        if (x >= 0 && y >= 0 && x < img.getWidth() && y < img.getHeight()) img.setRGB(x, y, rgb);
    }

    private static int color(int lv) {
        if (lv == 0) return 0x000000;
        if (lv == 1) return 0x303030;
        if (lv < 128) { // below threshold: dim -> bright blue
            double f = (lv - 2) / 125.0;
            return rgb(30 + 40 * f, 60 + 100 * f, 120 + 135 * f);
        }
        double f = (lv - 128) / 127.0; // above threshold: yellow -> red
        return rgb(255, 230 - 200 * f, 40 - 40 * f);
    }

    private static int rgb(double r, double g, double b) {
        return ((int) r << 16) | ((int) g << 8) | (int) b;
    }

    /** Human readable summary lines (plain text, the plugin adds colors). */
    public List<String> summary(RegionScanner.Result r) {
        List<String> out = new ArrayList<>();
        out.add("Generated chunks: " + r.totalChunks + " in " + r.regions.size() + " region files"
                + (r.unreadable > 0 ? " (" + r.unreadable + " unreadable)" : "")
                + (r.lz4Skipped > 0 ? " (" + r.lz4Skipped + " LZ4-compressed, skipped)" : ""));
        out.add("InhabitedTime distribution:");
        for (int b = 0; b < bucketCounts.length; b++) {
            if (bucketCounts[b] > 0) out.add("  " + BUCKET_NAMES[b] + ": " + bucketCounts[b]);
        }
        out.add("Chunks with InhabitedTime > " + TimeParse.format(thresholdTicks) + ": " + qualifying);
        if (qualifying == 0) return out;
        out.add("Center: X " + centerX + ", Z " + centerZ);
        out.add("Circle radius: " + circleRadius + " blocks (farthest chunk " + circleFarX + "," + circleFarZ
                + " ~ block " + (circleFarX * 16 + 8) + "," + (circleFarZ * 16 + 8) + ") -> "
                + outsideCircle + " chunks outside (" + pct(outsideCircle, r.totalChunks) + ")");
        out.add("Square radius: " + squareRadius + " blocks (farthest chunk " + squareFarX + "," + squareFarZ
                + " ~ block " + (squareFarX * 16 + 8) + "," + (squareFarZ * 16 + 8) + ") -> "
                + outsideSquare + " chunks outside (" + pct(outsideSquare, r.totalChunks) + ")");
        return out;
    }

    static String pct(long part, long whole) {
        return whole == 0 ? "0%" : String.format(java.util.Locale.ROOT, "%.1f%%", 100.0 * part / whole);
    }

}
