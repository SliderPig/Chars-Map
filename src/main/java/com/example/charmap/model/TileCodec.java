package com.example.charmap.model;

import java.nio.ByteBuffer;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

// What a tile looks like on disk. Colours go in separate red, green and blue runs and heights as differences,
// because that squeezes far better and inflating is the only cost when a tile loads.
public final class TileCodec {
    private TileCodec() {}

    private static final int MAGIC_BASE = 0x434D5431, MAGIC_LOD = 0x434D4C31;
    public static final int PIXELS = Coords.TILE * Coords.TILE;

    private static final int BASE_RAW = PIXELS * 3 + PIXELS * 2 + 1024 * 4 * 2 + Coords.TILE * 2 * 2;
    private static final int LOD_RAW = PIXELS * 3;

    // The block-level tile: unshaded colours and heights, so hills can be worked out against whatever is next to them.
    public static final class Base {
        public final int[] colour = new int[PIXELS];
        public final short[] height = new short[PIXELS];
        public final int[] hash = new int[1024];   // quick fingerprint of each chunk's columns: same hash, nothing to redo
        public final int[] stamp = new int[1024];  // when each chunk was last coloured, seconds since 1970
        public final short[] north = new short[Coords.TILE]; // bottom row of heights of the tile to the north
        public final short[] west = new short[Coords.TILE];  // right-hand column of the tile to the west

        public Base() {
            java.util.Arrays.fill(height, Shade.NO_HEIGHT);
            java.util.Arrays.fill(north, Shade.NO_HEIGHT);
            java.util.Arrays.fill(west, Shade.NO_HEIGHT);
        }
    }

    public static byte[] encodeBase(Base t) {
        ByteBuffer raw = ByteBuffer.allocate(BASE_RAW);
        byte[] a = raw.array();
        int o = 0;
        for (int i = 0; i < PIXELS; i++) a[o++] = (byte) (t.colour[i] >> 16);
        for (int i = 0; i < PIXELS; i++) a[o++] = (byte) (t.colour[i] >> 8);
        for (int i = 0; i < PIXELS; i++) a[o++] = (byte) t.colour[i];
        short last = 0;
        for (int i = 0; i < PIXELS; i++) {
            short d = (short) (t.height[i] - last);
            last = t.height[i];
            a[o++] = (byte) (d >> 8);
            a[o++] = (byte) d;
        }
        raw.position(o);
        for (int v : t.hash) raw.putInt(v);
        for (int v : t.stamp) raw.putInt(v);
        for (short v : t.north) raw.putShort(v);
        for (short v : t.west) raw.putShort(v);
        return pack(MAGIC_BASE, a);
    }

    public static Base decodeBase(byte[] file) throws DataFormatException {
        byte[] a = unpack(file, MAGIC_BASE, BASE_RAW);
        Base t = new Base();
        int o = 0, n = PIXELS;
        for (int i = 0; i < n; i++) {
            int r = a[i] & 255, g = a[n + i] & 255, b = a[n * 2 + i] & 255;
            int c = (r << 16) | (g << 8) | b;
            t.colour[i] = c == 0 ? 0 : 0xFF000000 | c;
        }
        o = n * 3;
        short last = 0;
        for (int i = 0; i < n; i++) {
            short d = (short) (((a[o] & 255) << 8) | (a[o + 1] & 255));
            o += 2;
            last = (short) (last + d);
            t.height[i] = last;
        }
        ByteBuffer raw = ByteBuffer.wrap(a);
        raw.position(o);
        for (int i = 0; i < 1024; i++) t.hash[i] = raw.getInt();
        for (int i = 0; i < 1024; i++) t.stamp[i] = raw.getInt();
        for (int i = 0; i < Coords.TILE; i++) t.north[i] = raw.getShort();
        for (int i = 0; i < Coords.TILE; i++) t.west[i] = raw.getShort();
        return t;
    }

    // A far-away level: finished colours only.
    public static byte[] encodeLod(int[] pixels) {
        byte[] a = new byte[LOD_RAW];
        int n = PIXELS;
        for (int i = 0; i < n; i++) {
            a[i] = (byte) (pixels[i] >> 16);
            a[n + i] = (byte) (pixels[i] >> 8);
            a[n * 2 + i] = (byte) pixels[i];
        }
        return pack(MAGIC_LOD, a);
    }

    public static int[] decodeLod(byte[] file, int[] into) throws DataFormatException {
        byte[] a = unpack(file, MAGIC_LOD, LOD_RAW);
        int n = PIXELS;
        for (int i = 0; i < n; i++) {
            int c = ((a[i] & 255) << 16) | ((a[n + i] & 255) << 8) | (a[n * 2 + i] & 255);
            into[i] = c == 0 ? 0 : 0xFF000000 | c;
        }
        return into;
    }

    private static byte[] pack(int magic, byte[] raw) {
        Deflater d = new Deflater(3);
        try {
            d.setInput(raw);
            d.finish();
            byte[] out = new byte[Math.max(4096, raw.length / 3)];
            ByteBuffer.wrap(out).putInt(magic);
            int o = 4;
            while (!d.finished()) {
                if (o == out.length) out = java.util.Arrays.copyOf(out, out.length * 2);
                o += d.deflate(out, o, out.length - o);
            }
            return java.util.Arrays.copyOf(out, o);
        } finally {
            d.end();
        }
    }

    private static byte[] unpack(byte[] file, int magic, int size) throws DataFormatException {
        if (file.length < 4 || ByteBuffer.wrap(file).getInt() != magic) throw new DataFormatException("not a map tile");
        Inflater in = new Inflater();
        try {
            in.setInput(file, 4, file.length - 4);
            byte[] a = new byte[size];
            int o = 0;
            while (o < size && !in.finished()) {
                int got = in.inflate(a, o, size - o);
                if (got == 0 && (in.needsInput() || in.needsDictionary())) break;
                o += got;
            }
            if (o != size) throw new DataFormatException("tile cut short");
            return a;
        } finally {
            in.end();
        }
    }

    // A known pixel must never look like "nobody has seen this" once its alpha is thrown away on disk.
    public static int known(int rgb) {
        int c = rgb & 0xFFFFFF;
        return 0xFF000000 | (c == 0 ? 1 : c);
    }
}
