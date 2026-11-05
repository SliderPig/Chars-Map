package com.example.charmap.client;

import com.example.charmap.model.Shapes;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

// Smooth rounded shapes and clean text, all in real screen pixels so they stay crisp at any GUI scale. Nothing here is a straight-edged box.
public final class Ui {
    private Ui() {}

    public static final int PINK = 0xFFFF7EB6, PINK_DARK = 0xFFC2548A, PLUM = 0xFF2A1B2E, PLUM_LIGHT = 0xFF3D2A43, PLUM_DARK = 0xFF18101B, TEXT = 0xFFF8EAF2, TEXT_DIM = 0xFFBBA5B5;
    public static final Style FONT = Style.EMPTY.withFont(new ResourceLocation("minecraft", "uniform"));

    public static float scale() {
        return (float) Minecraft.getInstance().getWindow().getGuiScale();
    }

    public static void toRealPixels(GuiGraphics g) {
        float s = 1f / scale();
        g.pose().pushPose();
        g.pose().scale(s, s, 1);
    }

    private static int a(int c) { return c >>> 24; }
    private static int r(int c) { return (c >> 16) & 255; }
    private static int gr(int c) { return (c >> 8) & 255; }
    private static int b(int c) { return c & 255; }

    // A filled polygon around cx,cy, with a soft 1.2 pixel fade on its edge so it never looks jagged.
    public static void polygon(GuiGraphics g, float cx, float cy, float[] poly, float[] outer, int colour) {
        g.flush();
        Matrix4f m = g.pose().last().pose();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR);
        buf.vertex(m, cx, cy, 0).color(r(colour), gr(colour), b(colour), a(colour)).endVertex();
        int n = poly.length / 2;
        for (int i = 0; i <= n; i++) {
            int k = i % n;
            buf.vertex(m, cx + poly[k * 2], cy + poly[k * 2 + 1], 0).color(r(colour), gr(colour), b(colour), a(colour)).endVertex();
        }
        BufferUploader.drawWithShader(buf.end());
        if (outer != null) band(g, cx, cy, poly, outer, colour, colour & 0xFFFFFF);
    }

    // A strip between two outlines with the same number of points, fading from one colour to the other.
    public static void band(GuiGraphics g, float cx, float cy, float[] inner, float[] outer, int innerColour, int outerColour) {
        Matrix4f m = g.pose().last().pose();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.TRIANGLE_STRIP, DefaultVertexFormat.POSITION_COLOR);
        int n = inner.length / 2;
        for (int i = 0; i <= n; i++) {
            int k = i % n;
            buf.vertex(m, cx + inner[k * 2], cy + inner[k * 2 + 1], 0).color(r(innerColour), gr(innerColour), b(innerColour), a(innerColour)).endVertex();
            buf.vertex(m, cx + outer[k * 2], cy + outer[k * 2 + 1], 0).color(r(outerColour), gr(outerColour), b(outerColour), a(outerColour)).endVertex();
        }
        BufferUploader.drawWithShader(buf.end());
    }

    public static float[] rect(float w, float h, float radius, float grow, int perCorner) {
        float[] out = new float[(perCorner + 1) * 4 * 2];
        radius = Math.min(radius, Math.min(w, h) / 2) ;
        float ix = w / 2 - radius, iy = h / 2 - radius, rr = radius + grow;
        float[][] corners = {{ix, -iy, -90}, {ix, iy, 0}, {-ix, iy, 90}, {-ix, -iy, 180}};
        int o = 0;
        for (float[] c : corners) {
            for (int i = 0; i <= perCorner; i++) {
                double an = Math.toRadians(c[2] + 90.0 * i / perCorner);
                out[o++] = c[0] + (float) Math.cos(an) * rr;
                out[o++] = c[1] + (float) Math.sin(an) * rr;
            }
        }
        return out;
    }

    public static void round(GuiGraphics g, float x0, float y0, float x1, float y1, float radius, int colour) {
        float w = x1 - x0, h = y1 - y0;
        if (w <= 0 || h <= 0) return;
        int corner = Math.max(3, (int) Math.min(radius, 12));
        polygon(g, (x0 + x1) / 2, (y0 + y1) / 2, rect(w, h, radius, 0, corner), rect(w, h, radius, 1.2f, corner), colour);
    }

    // A rounded plate with a soft shadow under it and a lit top edge, so it sits above what's behind it.
    public static void plate(GuiGraphics g, float x0, float y0, float x1, float y1, float radius, int colour) {
        float w = x1 - x0, h = y1 - y0;
        int corner = Math.max(3, (int) Math.min(radius, 12));
        float cx = (x0 + x1) / 2, cy = (y0 + y1) / 2;
        band(g, cx, cy + 2, rect(w, h, radius, 0, corner), rect(w, h, radius, 6f, corner), 0x50000000, 0);
        round(g, x0, y0, x1, y1, radius, 0xFF000000 | shift(colour, 34));
        round(g, x0, y0 + 1, x1, y1, radius, colour);
    }

    public static int shift(int colour, int by) {
        int rr = Math.max(0, Math.min(255, r(colour) + by)), gg = Math.max(0, Math.min(255, gr(colour) + by)), bb = Math.max(0, Math.min(255, b(colour) + by));
        return (colour & 0xFF000000) | (rr << 16) | (gg << 8) | bb;
    }

    public static void disc(GuiGraphics g, float cx, float cy, float radius, int colour) {
        int corner = Math.max(3, (int) Math.min(radius * 1.5f, 10));
        polygon(g, cx, cy, Shapes.rounded(radius, radius, corner), Shapes.rounded(radius + 1.1f, radius + 1.1f, corner), colour);
    }

    // The arrow showing which way someone is facing: angle 0 = pointing up on the screen.
    public static void arrow(GuiGraphics g, float cx, float cy, float size, float radians, int colour, int edge) {
        float[] outerShape = Shapes.rotate(new float[] {0, -size * 1.28f, size * 0.95f, size * 1.05f, 0, size * 0.58f, -size * 0.95f, size * 1.05f}, radians);
        float[] shape = Shapes.rotate(new float[] {0, -size, size * 0.68f, size * 0.8f, 0, size * 0.36f, -size * 0.68f, size * 0.8f}, radians);
        flatConcave(g, cx, cy, outerShape, edge);
        flatConcave(g, cx, cy, shape, colour);
    }

    // Four-point dart: two triangles, because its notch would break a simple fan.
    private static void flatConcave(GuiGraphics g, float cx, float cy, float[] p, int colour) {
        g.flush();
        Matrix4f m = g.pose().last().pose();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        int[] order = {0, 1, 2, 0, 2, 3};
        for (int i : order) buf.vertex(m, cx + p[i * 2], cy + p[i * 2 + 1], 0).color(r(colour), gr(colour), b(colour), a(colour)).endVertex();
        BufferUploader.drawWithShader(buf.end());
    }

    public static Component styled(String s) {
        return Component.literal(s).withStyle(FONT);
    }

    public static int width(String s) {
        return Math.round(Minecraft.getInstance().font.width(styled(s)) * scale());
    }

    public static int lineHeight() {
        return Math.round(9 * scale());
    }

    public static void text(GuiGraphics g, String s, float x, float y, int colour) {
        text(g, s, x, y, colour, 1f);
    }

    public static void text(GuiGraphics g, String s, float x, float y, int colour, float size) {
        PoseStack pose = g.pose();
        pose.pushPose();
        pose.translate(x, y, 0);
        float k = scale() * size;
        pose.scale(k, k, 1);
        g.drawString(Minecraft.getInstance().font, styled(s), 0, 0, colour, false);
        pose.popPose();
    }

    public static void textCentred(GuiGraphics g, String s, float cx, float y, int colour) {
        text(g, s, cx - width(s) / 2f, y, colour);
    }

    // A piece of text on its own small rounded tag.
    public static void tag(GuiGraphics g, String s, float cx, float y, int back, int colour) {
        int w = width(s) + Math.round(6 * scale());
        round(g, cx - w / 2f, y, cx + w / 2f, y + lineHeight() + 2, lineHeight() * 0.55f, back);
        textCentred(g, s, cx, y + Math.round(scale() * 0.5f), colour);
        g.flush();
    }

    // A screen's buttons: what's drawn this frame is what can be clicked next.
    public static final class Hits {
        public interface Press {
            void at(double x, double y);
        }

        private record Hit(float x0, float y0, float x1, float y1, Press press, Press drag) {}

        private final List<Hit> list = new ArrayList<>();
        private Hit dragging;

        public void clear() {
            list.clear();
        }

        public void add(float x0, float y0, float x1, float y1, Runnable press) {
            list.add(new Hit(x0, y0, x1, y1, (x, y) -> press.run(), null));
        }

        public void addDrag(float x0, float y0, float x1, float y1, Press drag) {
            list.add(new Hit(x0, y0, x1, y1, drag, drag));
        }

        public boolean press(double x, double y) {
            for (int i = list.size() - 1; i >= 0; i--) {
                Hit h = list.get(i);
                if (x >= h.x0 && x < h.x1 && y >= h.y0 && y < h.y1) {
                    h.press.at(x, y);
                    dragging = h.drag != null ? h : null;
                    return true;
                }
            }
            return false;
        }

        public boolean dragTo(double x, double y) {
            if (dragging == null) return false;
            dragging.drag.at(x, y);
            return true;
        }

        public void release() {
            dragging = null;
        }
    }

    // A pill-shaped button. Lights up under the mouse; pink when it's on.
    public static void pill(GuiGraphics g, Hits hits, float x0, float y0, float x1, float y1, String label, boolean on, double mx, double my, Runnable press) {
        boolean over = mx >= x0 && mx < x1 && my >= y0 && my < y1;
        int back = on ? (over ? shift(PINK, 14) : PINK) : (over ? shift(PLUM_LIGHT, 22) : PLUM_LIGHT);
        plate(g, x0, y0, x1, y1, (y1 - y0) / 2, back);
        textCentred(g, label, (x0 + x1) / 2, (y0 + y1) / 2 - lineHeight() / 2f + 1, on ? PLUM_DARK : TEXT);
        g.flush();
        if (hits != null) hits.add(x0, y0, x1, y1, press);
    }

    public static void slider(GuiGraphics g, Hits hits, float x0, float y, float x1, float h, float amount, double mx, double my, java.util.function.DoubleConsumer change) {
        float cy = y + h / 2, track = Math.max(4, h / 4);
        round(g, x0, cy - track / 2, x1, cy + track / 2, track / 2, PLUM_DARK);
        float kx = x0 + (x1 - x0) * amount;
        round(g, x0, cy - track / 2, kx, cy + track / 2, track / 2, PINK_DARK);
        boolean over = mx >= x0 - 6 && mx < x1 + 6 && my >= y && my < y + h;
        disc(g, kx, cy, h * 0.36f, over ? shift(PINK, 20) : PINK);
        if (hits != null) hits.addDrag(x0 - 8, y, x1 + 8, y + h, (px, py) -> change.accept(Math.max(0, Math.min(1, (px - x0) / (x1 - x0)))));
    }
}
