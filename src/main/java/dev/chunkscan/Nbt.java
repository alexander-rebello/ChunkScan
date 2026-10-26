package dev.chunkscan;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Minimal streaming NBT reader that only looks for InhabitedTime and skips everything else. */
final class Nbt {

    private static final byte[] INHABITED = "InhabitedTime".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] LEVEL = "Level".getBytes(StandardCharsets.US_ASCII);

    private Nbt() {}

    /** @return InhabitedTime in ticks, 0 if the chunk has none. */
    static long findInhabitedTime(DataInputStream in) throws IOException {
        int rootType = in.readUnsignedByte();
        if (rootType != 10) throw new IOException("root is not a compound");
        in.skipNBytes(in.readUnsignedShort()); // root name
        long t = searchCompound(in, true);
        return Math.max(0, t);
    }

    /**
     * Walks a compound. Returns InhabitedTime as soon as it is found (1.18+ stores it at the root,
     * older versions inside "Level"), or -1 when the compound ends without it.
     */
    private static long searchCompound(DataInputStream in, boolean allowLevel) throws IOException {
        byte[] nameBuf = new byte[64];
        while (true) {
            int type = in.readUnsignedByte();
            if (type == 0) return -1;
            int nameLen = in.readUnsignedShort();
            boolean isInhabited = false, isLevel = false;
            if (nameLen <= nameBuf.length) {
                in.readFully(nameBuf, 0, nameLen);
                isInhabited = type == 4 && equals(nameBuf, nameLen, INHABITED);
                isLevel = type == 10 && allowLevel && equals(nameBuf, nameLen, LEVEL);
            } else {
                in.skipNBytes(nameLen);
            }
            if (isInhabited) return in.readLong();
            if (isLevel) {
                long t = searchCompound(in, false);
                if (t >= 0) return t;
                continue;
            }
            skipPayload(in, type);
        }
    }

    private static boolean equals(byte[] buf, int len, byte[] want) {
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
