package com.example.charmap.client;

import com.example.charmap.model.Waypoint;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

// All your waypoints, in every dimension.
public final class WaypointListScreen extends Screen {
    private final Screen back;
    private final Ui.Hits hits = new Ui.Hits();
    private double mouseX, mouseY;
    private float scroll, maxScroll;

    public WaypointListScreen(Screen back) {
        super(Component.literal("Waypoints"));
        this.back = back;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void measureMouse() {
        Minecraft mc = Minecraft.getInstance();
        var w = mc.getWindow();
        mouseX = mc.mouseHandler.xpos() * w.getWidth() / Math.max(1, w.getScreenWidth());
        mouseY = mc.mouseHandler.ypos() * w.getHeight() / Math.max(1, w.getScreenHeight());
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        measureMouse();
        Minecraft mc = Minecraft.getInstance();
        var window = mc.getWindow();
        int w = window.getWidth(), h = window.getHeight();
        float k = Ui.scale(), line = Ui.lineHeight();
        hits.clear();
        Ui.toRealPixels(g);
        RenderSystem.disableDepthTest();
        g.fill(0, 0, w, h, 0x70000000);

        float pw = Math.min(w - 20f, 280 * k), ph = Math.min(h - 20f, 190 * k);
        float x0 = w / 2f - pw / 2, y0 = h / 2f - ph / 2, pad = 7 * k;
        Ui.plate(g, x0, y0, x0 + pw, y0 + ph, 16, Ui.PLUM);
        Ui.textCentred(g, "Waypoints", x0 + pw / 2, y0 + 5 * k, Ui.PINK);

        float rowH = line + 9 * k, gap = 2.5f * k;
        float top = y0 + line + 10 * k, bottom = y0 + ph - pad - rowH - 4 * k;
        List<Waypoint> list = new ArrayList<>(MapClient.WAYPOINTS);
        maxScroll = Math.max(0, list.size() * (rowH + gap) - (bottom - top));
        scroll = Math.max(0, Math.min(maxScroll, scroll));
        if (list.isEmpty()) Ui.textCentred(g, "Nothing here yet. Press B in the world to add one.", x0 + pw / 2, top + 12 * k, Ui.TEXT_DIM);

        LocalPlayer p = mc.player;
        MapClient.Session s = MapClient.session;
        g.flush();
        RenderSystem.enableScissor((int) x0, (int) (h - bottom), (int) pw, (int) (bottom - top));
        float y = top - scroll;
        for (Waypoint wp : list) {
            if (y + rowH >= top && y <= bottom) drawRow(g, wp, x0 + pad, y, pw - pad * 2, rowH, k, line, top, bottom, p, s);
            y += rowH + gap;
        }
        g.flush();
        RenderSystem.disableScissor();

        float by = y0 + ph - pad - rowH, bw = (pw - pad * 2 - 3 * k) / 2;
        Ui.pill(g, hits, x0 + pad, by, x0 + pad + bw, by + rowH, "Add here", true, mouseX, mouseY, () -> mc.setScreen(WaypointScreen.newHere(this)));
        Ui.pill(g, hits, x0 + pad + bw + 3 * k, by, x0 + pad + bw * 2 + 3 * k, by + rowH, "Done", false, mouseX, mouseY, this::onClose);

        g.flush();
        RenderSystem.enableDepthTest();
        g.pose().popPose();
    }

    private void drawRow(GuiGraphics g, Waypoint wp, float x, float y, float w, float h, float k, float line, float top, float bottom, LocalPlayer p, MapClient.Session s) {
        Ui.round(g, x, y, x + w, y + h, h * 0.45f, Ui.shift(Ui.PLUM_LIGHT, -10));
        float cy = y + h / 2;
        Ui.disc(g, x + h / 2, cy, 5 * k, 0xFF000000 | wp.colour);
        Ui.text(g, wp.name, x + h, cy - line / 2 + 1, wp.shown ? Ui.TEXT : Ui.TEXT_DIM);
        String info = wp.x + ", " + wp.y + ", " + wp.z;
        if (s != null && p != null) {
            int[] at = wp.placeIn(s.dimension);
            if (at != null) info += "  ·  " + Math.round(Math.hypot(at[0] - p.getX(), at[2] - p.getZ())) + "m";
            if (!wp.dimension.equals(s.dimension)) info += "  ·  " + shortDimension(wp.dimension);
        }
        float buttons = 62 * k;
        float infoW = Ui.width(info);
        Ui.text(g, info, x + w - buttons - infoW - 4 * k, cy - line / 2 + 1, Ui.TEXT_DIM);
        g.flush();
        float bh = h - 3 * k, by = y + 1.5f * k;
        boolean clickable = cy > top && cy < bottom;
        Ui.pill(g, clickable ? hits : null, x + w - buttons, by, x + w - buttons / 2 - 1 * k, by + bh, wp.shown ? "Shown" : "Hidden", wp.shown, mouseX, mouseY, () -> {
            wp.shown = !wp.shown;
            MapClient.saveWaypoints();
        });
        Ui.pill(g, clickable ? hits : null, x + w - buttons / 2 + 1 * k, by, x + w - 1.5f * k, by + bh, "Edit", false, mouseX, mouseY, () -> Minecraft.getInstance().setScreen(new WaypointScreen(this, wp, false)));
    }

    private static String shortDimension(String d) {
        int i = d.indexOf(':');
        return i < 0 ? d : d.substring(i + 1).replace('_', ' ');
    }

    @Override
    public boolean mouseScrolled(double x, double y, double delta) {
        scroll = Math.max(0, Math.min(maxScroll, scroll - (float) delta * 24 * Ui.scale()));
        return true;
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        measureMouse();
        return button == GLFW.GLFW_MOUSE_BUTTON_LEFT && hits.press(mouseX, mouseY);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(back);
    }
}
