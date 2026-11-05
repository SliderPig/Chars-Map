package com.example.charmap.model;

// How long the map took each frame: average, the slow end and the single worst. Averages hide hitches, so all three get reported.
public final class FrameStats {
    private static final int BUCKETS = 400;
    private static final long BUCKET_NANOS = 50_000L; // 0.05 ms, so up to 20 ms; anything more lands in the last one
    private final int[] counts = new int[BUCKETS];
    private long frames, total, worst;

    public void add(long nanos) {
        frames++;
        total += nanos;
        if (nanos > worst) worst = nanos;
        int b = (int) Math.min(BUCKETS - 1, nanos / BUCKET_NANOS);
        counts[b]++;
    }

    public void reset() {
        java.util.Arrays.fill(counts, 0);
        frames = 0;
        total = 0;
        worst = 0;
    }

    public long frames() {
        return frames;
    }

    public double averageMillis() {
        return frames == 0 ? 0 : total / (double) frames / 1e6;
    }

    public double worstMillis() {
        return worst / 1e6;
    }

    // The time that this share of frames came in under, e.g. 0.99.
    public double percentileMillis(double share) {
        if (frames == 0) return 0;
        long want = (long) Math.ceil(frames * share), seen = 0;
        for (int i = 0; i < BUCKETS; i++) {
            seen += counts[i];
            if (seen >= want) return (i + 1) * BUCKET_NANOS / 1e6;
        }
        return worstMillis();
    }

    public String summary() {
        return String.format("%d frames: average %.3f ms, 99%% under %.2f ms, worst %.2f ms", frames, averageMillis(), percentileMillis(0.99), worstMillis());
    }
}
