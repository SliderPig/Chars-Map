package com.example.charmap.model;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// A small reader for Minecraft's saved data. It runs off the main thread on files the game may be writing, so it asks nothing of the game itself.
// Parts of a chunk the map has no use for (lighting, entities, ticks) are skipped without being kept, which is most of the file.
public final class Nbt {
    private Nbt() {}

    private static final Set<String> SKIP = Set.of("BlockLight", "SkyLight", "block_entities", "block_ticks", "fluid_ticks", "PostProcessing",
            "structures", "CarvingMasks", "blending_data", "Lights", "entities", "Entities", "TileEntities", "UpgradeData", "Upgrade", "ForgeCaps", "ForgeData", "bukkit_values");

    private static final class In {
        final byte[] a;
        int o;

        In(byte[] a) {
            this.a = a;
        }

        void need(int n) throws IOException {
            if (n < 0 || o + n > a.length) throw new EOFException();
        }

        int u8() throws IOException {
            need(1);
            return a[o++] & 255;
        }

        int u16() throws IOException {
            need(2);
            int v = ((a[o] & 255) << 8) | (a[o + 1] & 255);
            o += 2;
            return v;
        }

        int i32() throws IOException {
            need(4);
            int v = ((a[o] & 255) << 24) | ((a[o + 1] & 255) << 16) | ((a[o + 2] & 255) << 8) | (a[o + 3] & 255);
            o += 4;
            return v;
        }

        long i64() throws IOException {
            long hi = i32();
            return (hi << 32) | (i32() & 0xFFFFFFFFL);
        }

        String text() throws IOException {
            int n = u16();
            need(n);
            boolean plain = true;
            for (int i = 0; i < n && plain; i++) plain = a[o + i] > 0;
            String s = plain ? new String(a, o, n, StandardCharsets.ISO_8859_1) : new DataInputStream(new ByteArrayInputStream(lengthPrefixed(n))).readUTF();
            o += n;
            return s;
        }

        private byte[] lengthPrefixed(int n) {
            byte[] b = new byte[n + 2];
            b[0] = (byte) (n >> 8);
            b[1] = (byte) n;
            System.arraycopy(a, o, b, 2, n);
            return b;
        }

        void skip(int n) throws IOException {
            need(n);
            o += n;
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> read(byte[] data) throws IOException {
        In in = new In(data);
        if (in.u8() != 10) throw new IOException("not a compound");
        in.text();
        return (Map<String, Object>) tag(in, 10, 0);
    }

    private static Object tag(In in, int type, int depth) throws IOException {
        if (depth > 32) throw new IOException("nested too deep");
        switch (type) {
            case 1: return (byte) in.u8();
            case 2: return (short) in.u16();
            case 3: return in.i32();
            case 4: return in.i64();
            case 5: return Float.intBitsToFloat(in.i32());
            case 6: return Double.longBitsToDouble(in.i64());
            case 7: {
                int n = in.i32();
                in.need(n);
                byte[] b = new byte[n];
                System.arraycopy(in.a, in.o, b, 0, n);
                in.o += n;
                return b;
            }
            case 8: return in.text();
            case 9: {
                int inner = in.u8(), n = in.i32();
                if (n < 0 || n > in.a.length) throw new IOException("bad list");
                List<Object> list = new ArrayList<>(n);
                for (int i = 0; i < n; i++) list.add(tag(in, inner, depth + 1));
                return list;
            }
            case 10: {
                Map<String, Object> map = new HashMap<>();
                while (true) {
                    int t = in.u8();
                    if (t == 0) return map;
                    String name = in.text();
                    if (SKIP.contains(name)) skip(in, t, depth + 1);
                    else map.put(name, tag(in, t, depth + 1));
                }
            }
            case 11: {
                int n = in.i32();
                if (n < 0 || n > in.a.length / 4) throw new IOException("bad ints");
                int[] v = new int[n];
                for (int i = 0; i < n; i++) v[i] = in.i32();
                return v;
            }
            case 12: {
                int n = in.i32();
                if (n < 0 || n > in.a.length / 8) throw new IOException("bad longs");
                long[] v = new long[n];
                for (int i = 0; i < n; i++) v[i] = in.i64();
                return v;
            }
            default:
                throw new IOException("unknown tag " + type);
        }
    }

    private static void skip(In in, int type, int depth) throws IOException {
        if (depth > 64) throw new IOException("nested too deep");
        switch (type) {
            case 1: in.skip(1); return;
            case 2: in.skip(2); return;
            case 3: case 5: in.skip(4); return;
            case 4: case 6: in.skip(8); return;
            case 7: in.skip(in.i32()); return;
            case 8: in.skip(in.u16()); return;
            case 9: {
                int inner = in.u8(), n = in.i32();
                if (n < 0 || n > in.a.length) throw new IOException("bad list");
                for (int i = 0; i < n; i++) skip(in, inner, depth + 1);
                return;
            }
            case 10: {
                while (true) {
                    int t = in.u8();
                    if (t == 0) return;
                    in.skip(in.u16());
                    skip(in, t, depth + 1);
                }
            }
            case 11: in.skip(multiply(in.i32(), 4)); return;
            case 12: in.skip(multiply(in.i32(), 8)); return;
            default: throw new IOException("unknown tag " + type);
        }
    }

    private static int multiply(int n, int by) throws IOException {
        if (n < 0 || n > Integer.MAX_VALUE / by) throw new IOException("bad array");
        return n * by;
    }
}
