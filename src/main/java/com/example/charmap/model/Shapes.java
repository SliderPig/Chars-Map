package com.example.charmap.model;

// The outlines maps are drawn in. All smooth polygons, so the map can be cut to a circle or a rounded square at any rotation
// with plain triangles: no stencil, no extra framebuffer, nothing that could fight another mod or a weak graphics chip.
public final class Shapes {
    private Shapes() {}

    // A rounded square centred on 0,0 as x,y pairs, clockwise on screen. radius = half the side for a circle.
    public static float[] rounded(float half, float radius, int perCorner) {
        radius = Math.min(radius, half);
        float[] out = new float[(perCorner + 1) * 4 * 2];
        int o = 0;
        float inner = half - radius;
        float[][] corners = {{inner, -inner, -90}, {inner, inner, 0}, {-inner, inner, 90}, {-inner, -inner, 180}};
        for (float[] c : corners) {
            for (int i = 0; i <= perCorner; i++) {
                double a = Math.toRadians(c[2] + 90.0 * i / perCorner);
                out[o++] = c[0] + (float) Math.cos(a) * radius;
                out[o++] = c[1] + (float) Math.sin(a) * radius;
            }
        }
        return out;
    }

    public static float[] rotate(float[] points, double radians) {
        float[] out = new float[points.length];
        float cos = (float) Math.cos(radians), sin = (float) Math.sin(radians);
        for (int i = 0; i < points.length; i += 2) {
            out[i] = points[i] * cos - points[i + 1] * sin;
            out[i + 1] = points[i] * sin + points[i + 1] * cos;
        }
        return out;
    }

    // Cuts a polygon down to what's inside a rectangle (the map tile it falls on). Returns x,y pairs; empty when nothing is inside.
    public static float[] clip(float[] poly, float x0, float y0, float x1, float y1) {
        float[] p = clipEdge(poly, 0, x0, 1);
        if (p.length > 0) p = clipEdge(p, 0, x1, -1);
        if (p.length > 0) p = clipEdge(p, 1, y0, 1);
        if (p.length > 0) p = clipEdge(p, 1, y1, -1);
        return p;
    }

    // Keeps the side of a line where (value - limit) * sign >= 0.
    private static float[] clipEdge(float[] p, int axis, float limit, int sign) {
        int n = p.length / 2;
        float[] out = new float[(n + n + 2) * 2];
        int o = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            float a = p[i * 2 + axis], b = p[j * 2 + axis];
            boolean aIn = (a - limit) * sign >= 0, bIn = (b - limit) * sign >= 0;
            if (aIn) {
                out[o++] = p[i * 2];
                out[o++] = p[i * 2 + 1];
            }
            if (aIn != bIn) {
                float t = (limit - a) / (b - a);
                out[o++] = p[i * 2] + (p[j * 2] - p[i * 2]) * t;
                out[o++] = p[i * 2 + 1] + (p[j * 2 + 1] - p[i * 2 + 1]) * t;
            }
        }
        return java.util.Arrays.copyOf(out, o);
    }

    // Pulls a point in to the edge of the map shape when it's outside, keeping its direction. Used for teammates and waypoints out of range.
    public static boolean toEdge(float[] xy, float half, float radius) {
        float x = xy[0], y = xy[1];
        float ax = Math.abs(x), ay = Math.abs(y);
        radius = Math.min(radius, half);
        float inner = half - radius;
        boolean inside;
        if (ax <= inner || ay <= inner) inside = ax <= half && ay <= half;
        else {
            float dx = ax - inner, dy = ay - inner;
            inside = dx * dx + dy * dy <= radius * radius;
        }
        if (inside) return false;
        // Walk back along the line to the middle till it fits: exact enough and always ends up on the edge.
        float lo = 0, hi = 1;
        for (int i = 0; i < 14; i++) {
            float t = (lo + hi) / 2, px = ax * t, py = ay * t;
            boolean in;
            if (px <= inner || py <= inner) in = px <= half && py <= half;
            else {
                float dx = px - inner, dy = py - inner;
                in = dx * dx + dy * dy <= radius * radius;
            }
            if (in) lo = t;
            else hi = t;
        }
        xy[0] = x * lo;
        xy[1] = y * lo;
        return true;
    }
}
