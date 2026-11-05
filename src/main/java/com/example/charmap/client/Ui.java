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
import org.joml.Matrix4f;

// Smooth rounded shapes and the normal Minecraft font, all in real screen pixels so they stay crisp at any GUI scale. Nothing here is a straight-edged box.
// Between begin and end, every shape goes into one list of triangles and all text into one batch: on a built-in graphics chip each separate draw costs far more than its pixels.
public final class Ui {
    private Ui() {}

    public static final int PINK = 0xFFFF7EB6, PINK_DARK = 0xFFC2548A, PLUM = 0xFF2A1B2E, PLUM_LIGHT = 0xFF3D2A43, PLUM_DARK = 0xFF18101B, TEXT = 0xFFF8EAF2, TEXT_DIM = 0xFFBBA5B5;

    private record Line(String text, float x, float y, int colour, int scale) {}

    private static boolean open;
    private static BufferBuilder buf;
    private static Matrix4f matrix;
    private static final List<Line> LINES = new ArrayList<>();
    private static final List<Runnable> AFTER = new ArrayList<>();

    public static float scale() {
        return (float) Minecraft.getInstance().getWindow().getGuiScale();
    }

    public static void toRealPixels(GuiGraphics g) {
        float s = 1f / scale();
        g.pose().pushPose();
        g.pose().scale(s, s, 1);
    }

    public static void begin(GuiGraphics g) {
        if (open) return;
        g.flush();
        open = true;
        matrix = g.pose().last().pose();
        buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
    }

    // Draws all shapes, then anything textured that was waiting for them, then all the text on top.
    public static void end(GuiGraphics g) {
        if (!open) return;
        open = false;
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.disableDepthTest();
        BufferUploader.drawWithShader(buf.end());
        buf = null;
        if (!AFTER.isEmpty()) {
            for (Runnable r : AFTER) r.run();
            AFTER.clear();
        }
        if (!LINES.isEmpty()) {
            g.drawManaged(() -> {
                var font = Minecraft.getInstance().font;
                PoseStack pose = g.pose();
                for (Line l : LINES) {
                    pose.pushPose();
                    pose.translate(Math.round(l.x), Math.round(l.y), 0);
                    pose.scale(l.scale, l.scale, 1);
                    g.drawString(font, l.text, 0, 0, l.colour, false);
                    pose.popPose();
                }
            });
            LINES.clear();
        }
        RenderSystem.disableCull();
        RenderSystem.disableDepthTest();
    }

    // For something textured that has to wait until the shapes are down.
    public static void after(Runnable draw) {
        AFTER.add(draw);
    }

    private static void vertex(float x, float y, int c) {
        buf.vertex(matrix, x, y, 0).color((c >> 16) & 255, (c >> 8) & 255, c & 255, c >>> 24).endVertex();
    }

    // A filled polygon around cx,cy, with a soft 1.2 pixel fade on its edge so it never looks jagged.
    public static void polygon(GuiGraphics g, float cx, float cy, float[] poly, float[] outer, int colour) {
        boolean own = !open;
        if (own) begin(g);
        int n = poly.length / 2;
        for (int i = 0; i < n; i++) {
            int k = (i + 1) % n;
            vertex(cx, cy, colour);
            vertex(cx + poly[i * 2], cy + poly[i * 2 + 1], colour);
            vertex(cx + poly[k * 2], cy + poly[k * 2 + 1], colour);
        }
        if (outer != null) band(g, cx, cy, poly, outer, colour, colour & 0xFFFFFF);
        if (own) end(g);
    }

    // A strip between two outlines with the same number of points, fading from one colour to the other.
    public static void band(GuiGraphics g, float cx, float cy, float[] inner, float[] outer, int innerColour, int outerColour) {
        boolean own = !open;
        if (own) begin(g);
        int n = inner.length / 2;
        for (int i = 0; i < n; i++) {
            int k = (i + 1) % n;
            float ix = cx + inner[i * 2], iy = cy + inner[i * 2 + 1], jx = cx + inner[k * 2], jy = cy + inner[k * 2 + 1];
            float ox = cx + outer[i * 2], oy = cy + outer[i * 2 + 1], px = cx + outer[k * 2], py = cy + outer[k * 2 + 1];
            vertex(ix, iy, innerColour);
            vertex(ox, oy, outerColour);
            vertex(jx, jy, innerColour);
            vertex(jx, jy, innerColour);
            vertex(ox, oy, outerColour);
            vertex(px, py, outerColour);
        }
        if (own) end(g);
    }

    public static float[] rect(float w, float h, float radius, float grow, int perCorner) {
        float[] out = new float[(perCorner + 1) * 4 * 2];
        radius = Math.min(radius, Math.min(w, h) / 2);
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
        int rr = Math.max(0, Math.min(255, ((colour >> 16) & 255) + by)), gg = Math.max(0, Math.min(255, ((colour >> 8) & 255) + by)), bb = Math.max(0, Math.min(255, (colour & 255) + by));
        return (colour & 0xFF000000) | (rr << 16) | (gg << 8) | bb;
    }

    public static void disc(GuiGraphics g, float cx, float cy, float radius, int colour) {
        int corner = Math.max(3, (int) Math.min(radius * 1.5f, 10));
        polygon(g, cx, cy, Shapes.rounded(radius, radius, corner), Shapes.rounded(radius + 1.1f, radius + 1.1f, corner), colour);
    }

    private static final float[] HEX = {1f, 0f, 0.5f, 0.866f, -0.5f, 0.866f, -1f, 0f, -0.5f, -0.866f, 0.5f, -0.866f};

    // A small dot, cheap enough to draw lots of. Only between begin and end.
    public static void dot(float x, float y, float r, int c) {
        for (int i = 0; i < 6; i++) {
            int j = (i + 1) % 6;
            vertex(x, y, c);
            vertex(x + HEX[i * 2] * r, y + HEX[i * 2 + 1] * r, c);
            vertex(x + HEX[j * 2] * r, y + HEX[j * 2 + 1] * r, c);
        }
    }

    // The arrow showing which way someone is facing: angle 0 = pointing up on the screen.
    public static void arrow(GuiGraphics g, float cx, float cy, float size, float radians, int colour, int edge) {
        float[] outerShape = Shapes.rotate(new float[] {0, -size * 1.28f, size * 0.95f, size * 1.05f, 0, size * 0.58f, -size * 0.95f, size * 1.05f}, radians);
        float[] shape = Shapes.rotate(new float[] {0, -size, size * 0.68f, size * 0.8f, 0, size * 0.36f, -size * 0.68f, size * 0.8f}, radians);
        boolean own = !open;
        if (own) begin(g);
        dart(cx, cy, outerShape, edge);
        dart(cx, cy, shape, colour);
        if (own) end(g);
    }

    // Four-point dart: two triangles, because its notch would break a simple fan.
    private static void dart(float cx, float cy, float[] p, int colour) {
        for (int i : new int[] {0, 1, 2, 0, 2, 3}) vertex(cx + p[i * 2], cy + p[i * 2 + 1], colour);
    }

    public static Component styled(String s) {
        return Component.literal(s);
    }

    // The font's own pixels must land on whole screen pixels or it goes blurry: always a whole-number size.
    private static int textScale(float size) {
        return Math.max(1, Math.round(scale() * size));
    }

    public static int width(String s) {
        return Minecraft.getInstance().font.width(s) * textScale(1f);
    }

    public static int lineHeight() {
        return 9 * textScale(1f);
    }

    public static void text(GuiGraphics g, String s, float x, float y, int colour) {
        LINES.add(new Line(s, x, y, colour, textScale(1f)));
        if (!open) {
            begin(g);
            end(g);
        }
    }

    public static void textCentred(GuiGraphics g, String s, float cx, float y, int colour) {
        text(g, s, cx - width(s) / 2f, y, colour);
    }

    // A piece of text on its own small rounded tag, kept on the screen.
    public static void tag(GuiGraphics g, String s, float cx, float y, int back, int colour) {
        int w = width(s) + Math.round(6 * scale());
        int screen = Minecraft.getInstance().getWindow().getWidth();
        cx = Math.max(w / 2f + 2, Math.min(screen - w / 2f - 2, cx));
        round(g, cx - w / 2f, y, cx + w / 2f, y + lineHeight() + 2, lineHeight() * 0.55f, back);
        textCentred(g, s, cx, y + Math.round(scale() * 0.5f), colour);
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
