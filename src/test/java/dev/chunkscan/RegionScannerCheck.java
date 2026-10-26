package dev.chunkscan;

import java.io.*;
import java.nio.file.*;
import java.util.zip.*;

/**
 * Self-check without a test framework (run by CI: java -cp target/classes:target/test-classes dev.chunkscan.RegionScannerCheck <workDir> <outDir>).
 * Builds fake region files in the real .mca format and checks the scanner's answers. */
public class RegionScannerCheck {

    // --- tiny NBT writer ---
    static void name(DataOutputStream o, int type, String n) throws IOException { o.writeByte(type); o.writeUTF(n); }

    static byte[] modernChunk(int cx, int cz, long inhabited) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(b);
        name(o, 10, "");
        name(o, 3, "DataVersion"); o.writeInt(3953);
        name(o, 8, "Status"); o.writeUTF("minecraft:full");
        name(o, 3, "xPos"); o.writeInt(cx);
        name(o, 3, "zPos"); o.writeInt(cz);
        // sections: list of compounds with long arrays, byte, nested compound, list of strings
        name(o, 9, "sections"); o.writeByte(10); o.writeInt(2);
        for (int s = 0; s < 2; s++) {
            name(o, 1, "Y"); o.writeByte(s);
            name(o, 10, "block_states");
              name(o, 9, "palette"); o.writeByte(10); o.writeInt(1);
                name(o, 8, "Name"); o.writeUTF("minecraft:stone"); o.writeByte(0);
              name(o, 12, "data"); o.writeInt(256); for (int i = 0; i < 256; i++) o.writeLong(i * 31L);
            o.writeByte(0);
            name(o, 7, "BlockLight"); o.writeInt(2048); o.write(new byte[2048]);
            name(o, 9, "empty"); o.writeByte(0); o.writeInt(0);
            o.writeByte(0);
        }
        name(o, 11, "heights"); o.writeInt(37); for (int i = 0; i < 37; i++) o.writeInt(i);
        name(o, 6, "d"); o.writeDouble(1.5);
        name(o, 5, "f"); o.writeFloat(1.5f);
        name(o, 2, "s"); o.writeShort(7);
        name(o, 4, "LastUpdate"); o.writeLong(123456);
        name(o, 4, "InhabitedTime"); o.writeLong(inhabited);
        name(o, 9, "block_entities"); o.writeByte(10); o.writeInt(0);
        o.writeByte(0);
        return b.toByteArray();
    }

    static byte[] legacyChunk(int cx, int cz, long inhabited) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(b);
        name(o, 10, "");
        name(o, 3, "DataVersion"); o.writeInt(2586);
        name(o, 10, "Level");
          name(o, 3, "xPos"); o.writeInt(cx);
          name(o, 3, "zPos"); o.writeInt(cz);
          name(o, 7, "Biomes"); o.writeInt(10); o.write(new byte[10]);
          name(o, 4, "InhabitedTime"); o.writeLong(inhabited);
        o.writeByte(0);
        o.writeByte(0);
        return b.toByteArray();
    }

    static byte[] compress(byte[] raw, int type) throws IOException {
        if (type == 3) return raw;
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (OutputStream o = type == 1 ? new GZIPOutputStream(b) : new DeflaterOutputStream(b)) { o.write(raw); }
        return b.toByteArray();
    }

    /** chunks: {localIndex, type(1/2/3/4, |128 external), payload} */
    static void writeRegion(File dir, int rx, int rz, Object[][] chunks) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int[] locs = new int[1024];
        int sector = 2;
        for (Object[] c : chunks) {
            int idx = (Integer) c[0], type = (Integer) c[1];
            byte[] data = (byte[]) c[2];
            ByteArrayOutputStream ch = new ByteArrayOutputStream();
            DataOutputStream o = new DataOutputStream(ch);
            if ((type & 128) != 0) {
                int cx = rx * 32 + (idx & 31), cz = rz * 32 + (idx >>> 5);
                Files.write(new File(dir, "c." + cx + "." + cz + ".mcc").toPath(), data);
                o.writeInt(1); o.writeByte(type);
            } else {
                o.writeInt(data.length + 1); o.writeByte(type); o.write(data);
            }
            byte[] bytes = ch.toByteArray();
            int sectors = (bytes.length + 4095) / 4096;
            byte[] padded = new byte[sectors * 4096];
            System.arraycopy(bytes, 0, padded, 0, bytes.length);
            body.write(padded);
            locs[idx] = (sector << 8) | sectors;
            sector += sectors;
        }
        try (DataOutputStream f = new DataOutputStream(new FileOutputStream(new File(dir, "r." + rx + "." + rz + ".mca")))) {
            for (int l : locs) f.writeInt(l);
            f.write(new byte[4096]); // timestamps
            f.write(body.toByteArray());
        }
    }

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if (!ok) System.exit(1);
    }

    public static void main(String[] args) throws Exception {
        File dim = new File(args[0]);
        File dir = new File(dim, "region");
        dir.mkdirs();
        for (String sub : new String[]{"region", "poi", "entities"}) {
            File d = new File(dim, sub);
            d.mkdirs();
            for (File f : d.listFiles()) f.delete();
        }

        writeRegion(dir, 0, 0, new Object[][]{
            {0, 2, compress(modernChunk(0, 0, 100000), 2)},             // 0,0 zlib, 83 min
            {5 + 3 * 32, 2, compress(modernChunk(5, 3, 0), 2)},         // 5,3 never visited
            {31 + 31 * 32, 1, compress(modernChunk(31, 31, 700), 1)},   // 31,31 gzip, 35s
            {2, 3, legacyChunk(2, 0, 50)},                              // 2,0 uncompressed pre-1.18, 2.5s
            {7, 4, new byte[]{1, 2, 3}},                                // LZ4 -> skipped
            {8, 2, new byte[]{9, 9, 9}},                                // corrupt
        });
        // external .mcc chunk at -20,-10 (region -1,-1, local 12,22)
        writeRegion(dir, -1, -1, new Object[][]{
            {12 + 22 * 32, 2 | 128, compress(modernChunk(-20, -10, 30000), 2)},
        });
        // a fully pre-generated, never visited region
        Object[][] pregen = new Object[1024][];
        byte[] empty = compress(modernChunk(0, 0, 0), 2);
        for (int i = 0; i < 1024; i++) pregen[i] = new Object[]{i, 2, empty};
        writeRegion(dir, 2, 0, pregen);
        new File(dir, "r.5.5.mca").createNewFile(); // empty region file

        RegionScanner.Result r = RegionScanner.scan(dir, 2, d -> {});
        Analysis a = new Analysis(r, 0, 0, TimeParse.toTicks("30s"));
        for (String l : a.summary(r)) System.out.println("  " + l);

        check(r.totalChunks == 6 + 1 + 1024, "total chunks = " + r.totalChunks);
        check(r.lz4Skipped == 1, "lz4 skipped");
        check(r.unreadable == 1, "corrupt counted as unreadable");
        check(r.visitedCount == 4, "visited (>0) = " + r.visitedCount);
        check(a.qualifying == 3, "qualifying (>30s) = " + a.qualifying);
        check(a.circleRadius == 725, "circle radius = " + a.circleRadius);
        check(a.squareRadius == 512, "square radius = " + a.squareRadius);
        check(a.circleFarX == 31 && a.circleFarZ == 31, "farthest chunk 31,31");
        check(a.outsideCircle == 1024, "pregen region outside circle = " + a.outsideCircle);
        check(a.bucketCounts[0] == 1031 - 2 - 4, "never-visited bucket");

        Analysis b = new Analysis(r, 0, 0, TimeParse.toTicks("1m"));
        check(b.qualifying == 2 && b.circleRadius == (int) Math.ceil(Math.hypot(320, 160)), "1m threshold -> radius " + b.circleRadius);

        check(TimeParse.toTicks("600t") == 600 && TimeParse.toTicks("5m") == 6000
                && TimeParse.toTicks("2") == 40 && TimeParse.toTicks("1h") == 72000
                && TimeParse.format(1200).equals("1m") && TimeParse.format(700).equals("35s"), "time parsing");

        // size forecast: region/poi/entities files of regions completely outside the shape are deleted
        Files.write(new File(dim, "poi/r.2.0.mca").toPath(), new byte[10000]);
        Files.write(new File(dim, "entities/r.0.0.mca").toPath(), new byte[3000]);
        Files.write(new File(dim, "entities/r.9.9.mca").toPath(), new byte[500]); // no region file: never trimmed
        SizeForecast sf = SizeForecast.measure(dim);
        long r00 = new File(dir, "r.0.0.mca").length(), r11 = new File(dir, "r.-1.-1.mca").length();
        long r20 = new File(dir, "r.2.0.mca").length(), mcc = new File(dir, "c.-20.-10.mcc").length();
        check(sf.dimensionBytes == r00 + r11 + r20 + mcc + 10000 + 3000 + 500, "measured dimension size");
        long after = sf.forecast(SizeForecast.Shape.circle(0, 0, 725));
        check(after == r00 + r11 + mcc + 3000 + 500 && sf.deletedRegionFiles == 2,
                "circle forecast keeps r.0.0 and r.-1.-1, deletes r.2.0 (+poi) and empty r.5.5: " + SizeForecast.human(after));
        check(sf.forecast(SizeForecast.Shape.square(0, 0, 1100)) == sf.dimensionBytes && sf.deletedRegionFiles == 1,
                "square reaching into r.2.0 keeps it (only empty r.5.5 goes)");
        check(SizeForecast.fullyOutside(2, 0, SizeForecast.Shape.square(0, 0, 1031))
                && !SizeForecast.fullyOutside(2, 0, SizeForecast.Shape.square(0, 0, 1032)), "chunk-center rule like Chunky");
        check(SizeForecast.human(1536).equals("1.5 KB") && SizeForecast.human(5L << 30).equals("5.0 GB"), "size format");
        java.util.List<String> lines2 = a.summary(r, sf, sf.dimensionBytes + 1000);
        for (String l : lines2) if (l.contains("size") || l.contains("after trim")) System.out.println("  " + l);

        // optimizer: simulate Chunky's inhabited trim on a copy, repack, compare with the forecast and the scan
        long tTicks = TimeParse.toTicks("30s");
        SizeForecast.Outcome inh = sf.inhabitedOutcome(r, tTicks);
        File copy = new File(args[0] + "-optimize");
        copyDir(dim, copy);
        java.util.Set<Long> above = new java.util.HashSet<>();
        for (int i = 0; i < r.visitedCount; i++) if (r.visitedTicks[i] > tTicks) above.add(pack(r.visitedX[i], r.visitedZ[i]));
        simulateChunkyTrim(copy, (x, z) -> above.contains(pack(x, z)));
        check(dirBytes(copy) == inh.afterTrim, "inhabited trim forecast matches simulated trim: " + SizeForecast.human(inh.afterTrim));
        RegionScanner.Result beforeOpt = RegionScanner.scan(new File(copy, "region"), 2, d -> {});
        java.util.List<String> optLog = new java.util.ArrayList<>();
        RegionOptimizer.Stats st = RegionOptimizer.optimizeDimension(copy, optLog::add);
        optLog.forEach(l -> System.out.println("  log: " + l));
        check(dirBytes(copy) == inh.afterOptimize, "optimize forecast matches real result: "
                + SizeForecast.human(dirBytes(copy)) + " vs " + SizeForecast.human(inh.afterOptimize));
        check(st.rewritten == 1 && st.unchanged == 1 && st.skippedInvalid == 2 && st.bytesBefore - st.bytesAfter == inh.afterTrim - inh.afterOptimize,
                "repacked r.0.0, r.-1.-1 already compact, fake entities files skipped and counted unchanged");
        RegionScanner.Result afterOpt = RegionScanner.scan(new File(copy, "region"), 2, d -> {});
        check(afterOpt.totalChunks == beforeOpt.totalChunks && afterOpt.visitedCount == beforeOpt.visitedCount
                && afterOpt.parsed == beforeOpt.parsed && afterOpt.unreadable == beforeOpt.unreadable
                && afterOpt.lz4Skipped == beforeOpt.lz4Skipped, "repacked files contain the same chunks");
        long sumBefore = 0, sumAfter = 0;
        for (int i = 0; i < beforeOpt.visitedCount; i++) sumBefore += beforeOpt.visitedTicks[i] * 31 + beforeOpt.visitedX[i] * 7 + beforeOpt.visitedZ[i];
        for (int i = 0; i < afterOpt.visitedCount; i++) sumAfter += afterOpt.visitedTicks[i] * 31 + afterOpt.visitedX[i] * 7 + afterOpt.visitedZ[i];
        check(sumBefore == sumAfter, "same chunk contents after repacking");
        RegionOptimizer.Stats again = RegionOptimizer.optimizeDimension(copy, l -> {});
        check(again.rewritten == 0 && again.unchanged == 2 && again.bytesBefore == again.bytesAfter, "second run changes nothing");
        // a file with a broken header is left untouched
        File broken = new File(copy, "region/r.7.7.mca");
        byte[] bad = new byte[3 * 4096];
        bad[0] = 0; bad[1] = 0; bad[2] = 9; bad[3] = 1; // chunk 0 at sector 9 (beyond the end)
        Files.write(broken.toPath(), bad);
        java.util.List<String> badLog = new java.util.ArrayList<>();
        RegionOptimizer.optimizeDimension(copy, badLog::add);
        check(java.util.Arrays.equals(Files.readAllBytes(broken.toPath()), bad) && String.join("\n", badLog).contains("Skipped r.7.7.mca"),
                "broken file skipped and untouched");
        // option texts
        java.util.List<String> opts = a.trimOptions(r, sf, -1, "world", null);
        opts.forEach(l -> System.out.println("  " + l));
        check(String.join("\n", opts).contains("/chunky trim world square 0 0 0 0 outside 600"), "option 2 command has both radii");

        // minTime is optional; "x z" and "x z world" are coordinates
        check(TimeParse.startsWithTime(new String[]{"5m"}) && TimeParse.startsWithTime(new String[]{"30", "100", "200"})
                && TimeParse.startsWithTime(new String[]{"30", "chunky"})
                && !TimeParse.startsWithTime(new String[]{}) && !TimeParse.startsWithTime(new String[]{"chunky"})
                && !TimeParse.startsWithTime(new String[]{"100", "-200"})
                && !TimeParse.startsWithTime(new String[]{"100", "200", "world"}), "optional minTime");

        // update checker: version comparison and GitHub response parsing
        check(UpdateChecker.compare("1.10.0", "1.9.3") > 0 && UpdateChecker.compare("v1.3.0", "1.3.0") == 0
                && UpdateChecker.compare("1.3", "1.3.0") == 0 && UpdateChecker.compare("1.4.0-beta", "1.4.0") < 0
                && UpdateChecker.compare("1.3.0", "1.3.1") < 0, "version compare");
        UpdateChecker.Release rel = UpdateChecker.parse("{\"url\":\"https://api.github.com/x\",\"html_url\":\"https://github.com/a/b/releases/tag/v1.4.0\",\"tag_name\":\"v1.4.0\",\"name\":\"x\"}");
        check(rel.version.equals("1.4.0") && rel.url.equals("https://github.com/a/b/releases/tag/v1.4.0"), "release parse");

        File out = new File(args[1]);
        out.mkdirs();
        Analysis.writeCsv(r, new File(out, "test.csv"));
        a.writeHeatmap(r, new File(out, "test.png"), 2048);
        System.out.println(new String(Files.readAllBytes(new File(out, "test.csv").toPath())));

        // full export: every chunk entry, with details
        File exp = new File(out, "chunks.csv");
        RegionScanner.Result r2;
        long rows;
        try (ChunkExport sink = new ChunkExport(exp, 0, 0, TimeParse.toTicks("30s"))) {
            r2 = RegionScanner.scan(dir, 3, sink, d -> {});
            rows = sink.rows();
        }
        java.util.List<String> lines = Files.readAllLines(exp.toPath());
        check(lines.get(0).equals(ChunkExport.HEADER.trim()), "export header");
        check(rows == 1031 && lines.size() == 1032, "export has one row per chunk: " + rows);
        check(r2.totalChunks == r.totalChunks && r2.visitedCount == r.visitedCount, "export scan matches fast scan");
        java.util.Map<String, String> byChunk = new java.util.HashMap<>();
        for (String l : lines.subList(1, lines.size())) {
            String[] f = l.split(",", 2);
            byChunk.put(f[0] + "," + f[1].split(",")[0], l);
        }
        System.out.println(ChunkExport.HEADER.trim());
        for (String k : new String[]{"0,0", "31,31", "2,0", "-20,-10", "7,0", "8,0", "5,3"}) System.out.println(byChunk.get(k));
        check(byChunk.get("31,31").equals("31,31,496,496,r.0.0.mca,700,0:00:35,true,713,full,3953,123456,gzip,4096,"), "modern gzip chunk row");
        check(byChunk.get("2,0").equals("2,0,32,0,r.0.0.mca,50,0:00:02,false,41,,2586,,none,4096,"), "legacy chunk row");
        check(byChunk.get("-20,-10").startsWith("-20,-10,-320,-160,r.-1.-1.mca,30000,0:25:00,true,") && byChunk.get("-20,-10").contains(",zlib+external,"), "external chunk row");
        check(byChunk.get("7,0").endsWith(",lz4,4096,LZ4 compression not supported") && byChunk.get("7,0").contains(",,,"), "lz4 row");
        check(byChunk.get("8,0").contains("corrupt chunk data"), "corrupt row");
        check(byChunk.get("0,0").contains(",1:23:20,true,"), "hms format");
    }

    static long pack(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    static void copyDir(File from, File to) throws IOException {
        if (to.exists()) try (var w = Files.walk(to.toPath())) { w.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); }
        try (var w = Files.walk(from.toPath())) {
            for (Path p : (Iterable<Path>) w::iterator) {
                Path t = to.toPath().resolve(from.toPath().relativize(p));
                if (Files.isDirectory(p)) Files.createDirectories(t); else Files.copy(p, t);
            }
        }
    }

    static long dirBytes(File dir) throws IOException {
        long sum = 0;
        for (String sub : new String[]{"region", "entities", "poi"}) {
            File[] fs = new File(dir, sub).listFiles();
            if (fs != null) for (File f : fs) sum += f.length();
        }
        return sum;
    }

    /** What Chunky's trim with an inhabited-time filter does to the files (see its TrimCommand). */
    static void simulateChunkyTrim(File dim, java.util.function.BiPredicate<Integer, Integer> keep) throws IOException {
        File[] regions = new File(dim, "region").listFiles((d, n) -> n.matches("r\\.-?\\d+\\.-?\\d+\\.mca"));
        for (File f : regions) {
            String[] parts = f.getName().split("\\.");
            int rx = Integer.parseInt(parts[1]), rz = Integer.parseInt(parts[2]);
            boolean anyKept = false;
            for (int i = 0; i < 1024 && !anyKept; i++) anyKept = keep.test(rx * 32 + (i & 31), rz * 32 + (i >>> 5));
            if (!anyKept) {
                for (String sub : new String[]{"region", "entities", "poi"}) new File(dim, sub + "/" + f.getName()).delete();
                continue;
            }
            byte[] data = Files.readAllBytes(f.toPath());
            if (data.length < 8192) continue;
            for (int i = 0; i < 1024; i++) {
                if (!keep.test(rx * 32 + (i & 31), rz * 32 + (i >>> 5))) {
                    data[i * 4] = 0; data[i * 4 + 1] = 0; data[i * 4 + 2] = 0; data[i * 4 + 3] = 0;
                }
            }
            Files.write(f.toPath(), data);
        }
    }
}
