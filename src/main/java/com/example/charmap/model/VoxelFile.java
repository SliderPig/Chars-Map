package com.example.charmap.model;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

// One region's worth of block data for the 3D view: a table of 1024 places at the start of the file, then the chunks one after another.
// A chunk that shrank or stayed the size goes back in its old place; one that grew goes on the end. Only used on one thread.
public final class VoxelFile {
    private static final int MAGIC = 0x434D5631, HEADER = 4 + 1024 * 8;

    private VoxelFile() {}

    public static byte[] read(Path file, int index) throws IOException {
        if (!Files.exists(file)) return null;
        try (RandomAccessFile f = new RandomAccessFile(file.toFile(), "r")) {
            if (f.length() < HEADER || f.readInt() != MAGIC) return null;
            f.seek(4 + index * 8L);
            int offset = f.readInt(), length = f.readInt();
            if (offset <= 0 || length <= 0 || offset + (long) length > f.length()) return null;
            byte[] out = new byte[length];
            f.seek(offset);
            f.readFully(out);
            return out;
        }
    }

    // Writes unless it's the same as what's there. Returns whether anything was written.
    public static boolean write(Path file, int index, byte[] data) throws IOException {
        Files.createDirectories(file.getParent());
        try (RandomAccessFile f = new RandomAccessFile(file.toFile(), "rw")) {
            if (f.length() < HEADER) {
                f.setLength(0);
                f.writeInt(MAGIC);
                f.write(new byte[1024 * 8]);
            } else if (f.readInt() != MAGIC) {
                f.setLength(0);
                f.seek(0);
                f.writeInt(MAGIC);
                f.write(new byte[1024 * 8]);
            }
            f.seek(4 + index * 8L);
            int offset = f.readInt(), length = f.readInt();
            if (offset > 0 && length == data.length && offset + (long) length <= f.length()) {
                byte[] old = new byte[length];
                f.seek(offset);
                f.readFully(old);
                if (Arrays.equals(old, data)) return false;
            }
            int to = offset > 0 && data.length <= length ? offset : (int) f.length();
            f.seek(to);
            f.write(data);
            f.seek(4 + index * 8L);
            f.writeInt(to);
            f.writeInt(data.length);
            return true;
        }
    }
}
