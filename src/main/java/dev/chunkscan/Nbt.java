package dev.chunkscan;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Minimal streaming NBT reader that only picks the few chunk fields ChunkScan needs and skips everything else. */
final class Nbt {

    /** Fields read from a chunk. Missing values stay at their defaults. */
    static final class ChunkData {
        long inhabitedTime = 0;
        boolean hasInhabitedTime = false;
        int dataVersion = -1;
        long lastUpdate = -1;
        String status = null;
    }

    private static final byte[] INHABITED = ascii("InhabitedTime");
    private static final byte[] LEVEL = ascii("Level");
    private static final byte[] DATA_VERSION = ascii("DataVersion");
    private static final byte[] LAST_UPDATE = ascii("LastUpdate");
    private static final byte[] STATUS = ascii("Status");

    private Nbt() {}

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Reads a chunk's root compound.
     * @param full false: stop as soon as InhabitedTime is found (fast path);
     *             true: read the whole compound to also collect DataVersion, LastUpdate and Status
     */
    static ChunkData read(DataInputStream in, boolean full) throws IOException {
        int rootType = in.readUnsignedByte();
        if (rootType != 10) throw new IOException("root is not a compound");
        in.skipNBytes(in.readUnsignedShort()); // root name
        ChunkData d = new ChunkData();
        readCompound(in, d, full, true);
        return d;
    }

    /** @return true when reading can stop (fast path found InhabitedTime). */
    private static boolean readCompound(DataInputStream in, ChunkData d, boolean full, boolean root) throws IOException {
        byte[] nameBuf = new byte[64];
        while (true) {
            int type = in.readUnsignedByte();
            if (type == 0) return false;
            int nameLen = in.readUnsignedShort();
            int known = 0; // 1 inhabited, 2 level, 3 dataVersion, 4 lastUpdate, 5 status
            if (nameLen <= nameBuf.length) {
                in.readFully(nameBuf, 0, nameLen);
                if (type == 4 && eq(nameBuf, nameLen, INHABITED)) known = 1;
                else if (type == 10 && root && eq(nameBuf, nameLen, LEVEL)) known = 2;
                else if (type == 3 && root && eq(nameBuf, nameLen, DATA_VERSION)) known = 3;
                else if (type == 4 && eq(nameBuf, nameLen, LAST_UPDATE)) known = 4;
                else if (type == 8 && eq(nameBuf, nameLen, STATUS)) known = 5;
            } else {
                in.skipNBytes(nameLen);
            }
            switch (known) {
                case 1:
                    d.inhabitedTime = Math.max(0, in.readLong());
                    d.hasInhabitedTime = true;
                    if (!full) return true;
                    break;
                case 2: // pre-1.18 chunks keep their data inside "Level"
                    if (readCompound(in, d, full, false)) return true;
                    break;
                case 3: d.dataVersion = in.readInt(); break;
                case 4: d.lastUpdate = in.readLong(); break;
                case 5: d.status = in.readUTF(); break;
                default: skipPayload(in, type);
            }
        }
    }

    private static boolean eq(byte[] buf, int len, byte[] want) {
        if (len != want.length) return false;
        for (int i = 0; i < len; i++) if (buf[i] != want[i]) return false;
        return true;
    }

    private static void skipPayload(DataInputStream in, int type) throws IOException {
        switch (type) {
            case 1: in.skipNBytes(1); break;
            case 2: in.skipNBytes(2); break;
            case 3: case 5: in.skipNBytes(4); break;
            case 4: case 6: in.skipNBytes(8); break;
            case 7: in.skipNBytes(checkLen(in.readInt())); break;
            case 8: in.skipNBytes(in.readUnsignedShort()); break;
            case 9: {
                int elem = in.readUnsignedByte();
                int len = checkLen(in.readInt());
                for (int i = 0; i < len; i++) skipPayload(in, elem);
                break;
            }
            case 10: {
                while (true) {
                    int t = in.readUnsignedByte();
                    if (t == 0) break;
                    in.skipNBytes(in.readUnsignedShort());
                    skipPayload(in, t);
                }
                break;
            }
            case 11: in.skipNBytes(4L * checkLen(in.readInt())); break;
            case 12: in.skipNBytes(8L * checkLen(in.readInt())); break;
            case 0: break; // empty list element type
            default: throw new IOException("unknown NBT tag type " + type);
        }
    }

    private static int checkLen(int len) throws IOException {
        if (len < 0) throw new IOException("negative NBT length");
        return len;
    }
}
