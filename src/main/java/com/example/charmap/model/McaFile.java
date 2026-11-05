package com.example.charmap.model;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

// One region file of a world save, read whole into memory so the game can keep writing to its copy on disk.
public final class McaFile {
    private final byte[] file;

    public McaFile(byte[] file) {
        this.file = file;
    }

    public static McaFile read(Path path) throws IOException {
        return new McaFile(Files.readAllBytes(path));
    }

    private int offsetEntry(int index) {
        int o = index * 4;
        return ((file[o] & 255) << 16) | ((file[o + 1] & 255) << 8) | (file[o + 2] & 255);
    }

    public boolean has(int index) {
        return file.length >= 8192 && offsetEntry(index) >= 2;
    }

    // When the game last saved this chunk, seconds since 1970. 0 = never.
    public int timestamp(int index) {
        if (file.length < 8192) return 0;
        int o = 4096 + index * 4;
        return ((file[o] & 255) << 24) | ((file[o + 1] & 255) << 16) | ((file[o + 2] & 255) << 8) | (file[o + 3] & 255);
    }

    // The chunk's saved data, unpacked. Null when there's none, it was half written, or it lives in a separate oversized file.
    public byte[] chunk(int index) throws IOException {
        if (!has(index)) return null;
        int start = offsetEntry(index) * 4096;
        if (start + 5 > file.length) return null;
        int length = ((file[start] & 255) << 24) | ((file[start + 1] & 255) << 16) | ((file[start + 2] & 255) << 8) | (file[start + 3] & 255);
        int kind = file[start + 4] & 255;
        if (length <= 1 || start + 4 + length > file.length || (kind & 0x80) != 0) return null;
        if (kind == 3) {
            byte[] raw = new byte[length - 1];
            System.arraycopy(file, start + 5, raw, 0, raw.length);
            return raw;
        }
        InputStream in = new ByteArrayInputStream(file, start + 5, length - 1);
        Inflater inflater = null;
        try {
            if (kind == 1) in = new GZIPInputStream(in);
            else if (kind == 2) in = new InflaterInputStream(in, inflater = new Inflater(), 4096);
            else return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(8192, length * 4));
            byte[] buffer = new byte[8192];
            int got;
            while ((got = in.read(buffer)) > 0) out.write(buffer, 0, got);
            return out.toByteArray();
        } finally {
            if (inflater != null) inflater.end();
            in.close();
        }
    }
}
