package com.example.charmap.model;

// Colour sums. Colours are 0xAARRGGBB ints. A pixel with alpha 0 is a part of the world nobody has seen.
public final class Shade {
    private Shade() {}

    public static final short NO_HEIGHT = Short.MIN_VALUE;

    public static int multiply(int colour, int tint) {
        int r = ((colour >> 16) & 255) * ((tint >> 16) & 255) / 255;
        int g = ((colour >> 8) & 255) * ((tint >> 8) & 255) / 255;
        int b = (colour & 255) * (tint & 255) / 255;
        return (colour & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    public static int mix(int under, int over, float amount) {
        float keep = 1 - amount;
        int r = (int) (((under >> 16) & 255) * keep + ((over >> 16) & 255) * amount);
        int g = (int) (((under >> 8) & 255) * keep + ((over >> 8) & 255) * amount);
        int b = (int) ((under & 255) * keep + (over & 255) * amount);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    public static int scale(int colour, float by) {
        int r = Math.min(255, (int) (((colour >> 16) & 255) * by));
        int g = Math.min(255, (int) (((colour >> 8) & 255) * by));
        int b = Math.min(255, (int) ((colour & 255) * by));
        return (colour & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    // Deeper water hides more of the bottom and gets a little darker, like looking down from a boat.
    public static int water(int water, int bottom, int depth) {
        float amount = Math.min(0.93f, 0.55f + depth * 0.055f);
        float dark = Math.max(0.72f, 1f - Math.max(0, depth - 4) * 0.012f);
        return scale(mix(bottom, water, amount), dark);
    }

    // Hills: ground rising away from the north-west is lit, ground falling away is in shadow. Compares with the pixels north and west.
    public static int hills(int base, int height, int north, int west) {
        if ((base >>> 24) == 0) return 0;
        int dn = north == NO_HEIGHT ? 0 : height - north;
        int dw = west == NO_HEIGHT ? 0 : height - west;
        int d = dn + dw;
        if (d == 0) return base;
        d = Math.max(-10, Math.min(10, d));
        // The first step counts for most, then it flattens out: cliffs stay readable without going black.
        float f = d > 0 ? 1f + 0.05f + d * 0.018f : 1f - 0.07f + d * 0.022f;
        int r = Math.min(255, (int) (((base >> 16) & 255) * f));
        int g = Math.min(255, (int) (((base >> 8) & 255) * f));
        int b = Math.min(255, (int) ((base & 255) * f));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    // One pixel of the next level up: the average of the four below it that anyone has seen.
    public static int average4(int a, int b, int c, int d) {
        int count = 0, r = 0, g = 0, bl = 0;
        if ((a >>> 24) != 0) { count++; r += (a >> 16) & 255; g += (a >> 8) & 255; bl += a & 255; }
        if ((b >>> 24) != 0) { count++; r += (b >> 16) & 255; g += (b >> 8) & 255; bl += b & 255; }
        if ((c >>> 24) != 0) { count++; r += (c >> 16) & 255; g += (c >> 8) & 255; bl += c & 255; }
        if ((d >>> 24) != 0) { count++; r += (d >> 16) & 255; g += (d >> 8) & 255; bl += d & 255; }
        if (count == 0) return 0;
        return 0xFF000000 | ((r / count) << 16) | ((g / count) << 8) | (bl / count);
    }

    // Same for heights.
    public static short averageHeight(short a, short b, short c, short d) {
        int count = 0, sum = 0;
        if (a != NO_HEIGHT) { count++; sum += a; }
        if (b != NO_HEIGHT) { count++; sum += b; }
        if (c != NO_HEIGHT) { count++; sum += c; }
        if (d != NO_HEIGHT) { count++; sum += d; }
        return count == 0 ? NO_HEIGHT : (short) Math.floorDiv(sum, count);
    }

    // Average colour of a list of texture pixels, ignoring see-through ones. Alpha of the result tells how solid it was.
    public static int averagePixels(int[] argb, int count) {
        long r = 0, g = 0, b = 0, a = 0;
        int seen = 0;
        for (int i = 0; i < count; i++) {
            int alpha = argb[i] >>> 24;
            if (alpha < 8) continue;
            seen++;
            a += alpha;
            r += ((argb[i] >> 16) & 255) * alpha;
            g += ((argb[i] >> 8) & 255) * alpha;
            b += (argb[i] & 255) * alpha;
        }
        if (seen == 0 || a == 0) return 0;
        return ((int) (a / count) << 24) | ((int) (r / a) << 16) | ((int) (g / a) << 8) | (int) (b / a);
    }
}
