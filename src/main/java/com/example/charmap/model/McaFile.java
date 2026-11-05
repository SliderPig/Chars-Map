package com.example.charmap.model;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;

// One region file of a world save, read whole into memory so the game can keep writing to its copy on disk.
public final class McaFile {
    private final byte[] file;

    public McaFile(byte[] file) {
        this.file = file;
    }

    public static McaFile read(Path path) throws IOException {
        return new McaFile(Files.readAllBytes(path));
    }

    // Just the table at the start of the file: enough for has and timestamp, tiny to read.
    public static McaFile readTable(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return new McaFile(in.readNBytes(8192));
        }
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
        if (kind == 2) return inflate(start + 5, length - 1);
        if (kind != 1) return null;
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(file, start + 5, length - 1))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(8192, length * 4));
            byte[] buffer = new byte[8192];
            int got;
            while ((got = in.read(buffer)) > 0) out.write(buffer, 0, got);
            return out.toByteArray();
        }
    }

    // One unpacker per thread, reused: making a new one for every chunk is most of the cost of a small chunk.
    private static final ThreadLocal<Inflater> INFLATER = ThreadLocal.withInitial(Inflater::new);

    private byte[] inflate(int from, int length) throws IOException {
        Inflater inflater = INFLATER.get();
        inflater.reset();
        inflater.setInput(file, from, length);
        byte[] out = new byte[Math.max(16384, length * 5)];
        int size = 0;
        try {
            while (!inflater.finished()) {
                if (size == out.length) out = java.util.Arrays.copyOf(out, out.length * 2);
                int got = inflater.inflate(out, size, out.length - size);
                if (got == 0 && (inflater.needsInput() || inflater.needsDictionary())) throw new IOException("chunk data cut short");
                size += got;
            }
        } catch (java.util.zip.DataFormatException e) {
            throw new IOException("bad chunk data", e);
        }
        return java.util.Arrays.copyOf(out, size);
    }
}
