package com.example.charmap.client;

import com.example.charmap.model.Waypoint;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

// The full-screen map. Drag to move, wheel to zoom towards the mouse, right-click for a waypoint. Coarse tiles show at once and sharpen as you stop.
public final class MapScreen extends Screen {
    private static final float MIN_ZOOM = 1 / 64f, MAX_ZOOM = 16f;
    private static float savedZoom = 1.5f;

    private double centreX, centreZ;
    private float zoom, shownZoom;
    private double anchorX, anchorZ, anchorScreenX, anchorScreenY;
    private boolean zooming;
    private boolean dragging;
    private double lastX, lastY, mouseX, mouseY;
    private long lastFrame;
    private final Ui.Hits hits = new Ui.Hits();
    private boolean menuOpen;
    private float menuX, menuY;
    private int menuBlockX, menuBlockZ;
    private Waypoint menuWaypoint;

    public MapScreen() {
        super(Component.literal("World map"));
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null) {
            centreX = p.getX();
            centreZ = p.getZ();
        }
        zoom = savedZoom;
        shownZoom = zoom;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private int viewLayer(MapClient.Session s) {
        return s.layer;
    }

    private void measureMouse() {
        Minecraft mc = Minecraft.getInstance();
        var w = mc.getWindow();
        mouseX = mc.mouseHandler.xpos() * w.getWidth() / Math.max(1, w.getScreenWidth());
        mouseY = mc.mouseHandler.ypos() * w.getHeight() / Math.max(1, w.getScreenHeight());
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        Minecraft mc = Minecraft.getInstance();
        MapClient.Session s = MapClient.session;
        measureMouse();
        var window = mc.getWindow();
        int w = window.getWidth(), h = window.getHeight();
        long now = System.nanoTime();
        float dt = lastFrame == 0 ? 0.016f : Math.min(0.1f, (now - lastFrame) / 1e9f);
        lastFrame = now;
        if (zooming) {
            shownZoom += (zoom - shownZoom) * Math.min(1f, dt * 16f);
            if (Math.abs(shownZoom - zoom) < zoom * 0.002f) {
                shownZoom = zoom;
                zooming = false;
            }
            centreX = anchorX - anchorScreenX / shownZoom;
            centreZ = anchorZ - anchorScreenY / shownZoom;
        }

        Ui.toRealPixels(g);
        RenderSystem.disableDepthTest();
        g.fill(0, 0, w, h, 0xFF120C14);
        hits.clear();
        if (s != null) {
            float cx = w / 2f, cy = h / 2f;
            float[] shape = {-cx, -cy, cx, -cy, cx, cy, -cx, cy};
            MapView.draw(g, s.textures, viewLayer(s), shape, cx, cy, centreX, centreZ, shownZoom, 0f, 1f, s.textures.budget() * 3 / 4);
            drawWaypoints(g, s, cx, cy);
            drawMates(g, s, cx, cy);
            LocalPlayer p = mc.player;
            if (p != null) {
                float px = cx + (float) (p.getX() - centreX) * shownZoom, py = cy + (float) (p.getZ() - centreZ) * shownZoom;
                Ui.arrow(g, px, py, 9, (float) Math.toRadians(p.getViewYRot(partial) - 180), Ui.PINK, 0xFF1A0F1C);
            }
            drawBars(g, s, w, h);
            if (menuOpen) drawMenu(g, s);
        }
        g.flush();
        RenderSystem.enableDepthTest();
        g.pose().popPose();
    }

    private void drawWaypoints(GuiGraphics g, MapClient.Session s, float cx, float cy) {
        for (Waypoint wp : MapClient.WAYPOINTS) {
            if (!wp.shown) continue;
            int[] at = wp.placeIn(s.dimension);
            if (at == null) continue;
            float x = cx + (float) (at[0] + 0.5 - centreX) * shownZoom, y = cy + (float) (at[2] + 0.5 - centreZ) * shownZoom;
            if (x < -20 || y < -20 || x > cx * 2 + 20 || y > cy * 2 + 20) continue;
            Ui.disc(g, x, y, 9, 0xF0180E1B);
            Ui.disc(g, x, y, 6.5f, 0xFF000000 | wp.colour);
            boolean over = Math.abs(mouseX - x) < 12 && Math.abs(mouseY - y) < 12;
            if (over || shownZoom >= 0.25f) Ui.tag(g, wp.name, x, y + 12, 0xD0180E1B, wp.dimension.equals(s.dimension) ? Ui.TEXT : Ui.TEXT_DIM);
        }
    }

    private void drawMates(GuiGraphics g, MapClient.Session s, float cx, float cy) {
        for (Teammates.Mate mate : Teammates.list()) {
            if (!Teammates.sameDimension(mate, s.dimension)) continue;
            float x = cx + (float) (mate.x() - centreX) * shownZoom, y = cy + (float) (mate.z() - centreZ) * shownZoom;
            float r = Math.max(10, Ui.scale() * 5.5f);
            Teammates.head(g, mate, x, y, r);
            Ui.tag(g, mate.name(), x, y + r + 4, 0xD0180E1B, Ui.TEXT);
        }
    }

    private void drawBars(GuiGraphics g, MapClient.Session s, int w, int h) {
        float k = Ui.scale(), line = Ui.lineHeight(), pad = 7 * k, pillH = line + 7 * k;

        // Under the mouse
        int bx = (int) Math.floor(centreX + (mouseX - w / 2.0) / shownZoom), bz = (int) Math.floor(centreZ + (mouseY - h / 2.0) / shownZoom);
        String where = bx + ", " + bz;
        if (s.layer != 0) where += "   ·   cave view, y " + (s.layer - 8) * 16;
        int tw = Ui.width(where);
        Ui.plate(g, pad, pad, pad + tw + 10 * k, pad + pillH, pillH / 2, Ui.PLUM);
        Ui.text(g, where, pad + 5 * k, pad + (pillH - line) / 2 + 1, Ui.TEXT);

        // Fill progress
        if (Filler.active()) {
            float barW = Math.min(w * 0.42f, 210 * k), x0 = w / 2f - barW / 2, h2 = line + 9 * k;
            Ui.plate(g, x0, pad, x0 + barW, pad + h2 + 8, 12, Ui.PLUM);
            String text = Filler.status + "  ·  " + Filler.regionsDone + " of " + Filler.regionsTotal + " regions  ·  " + Math.round(Filler.speed()) + " chunks/s";
            Ui.textCentred(g, text, w / 2f, pad + 3 * k, Ui.TEXT);
            float amount = Filler.regionsTotal == 0 ? 0 : (float) Filler.regionsDone / Filler.regionsTotal;
            float tx0 = x0 + 5 * k, tx1 = x0 + barW - 5 * k, ty = pad + h2 + 2 - 2 * k;
            Ui.round(g, tx0, ty, tx1, ty + 2.5f * k, 1.25f * k, Ui.PLUM_DARK);
            Ui.round(g, tx0, ty, tx0 + (tx1 - tx0) * amount, ty + 2.5f * k, 1.25f * k, Ui.PINK);
        }

        // Buttons
        float y = h - pad - pillH, x = pad;
        x = button(g, x, y, pillH, k, "Centre on me", this::centreOnPlayer);
        x = button(g, x, y, pillH, k, "Waypoints", () -> Minecraft.getInstance().setScreen(new WaypointListScreen(this)));
        x = button(g, x, y, pillH, k, "Settings", () -> Minecraft.getInstance().setScreen(new SettingsScreen(this)));

        // Zoom, round buttons at the right
        float r = pillH / 2, zx = w - pad - r;
        roundButton(g, zx, h - pad - r, r, "-", () -> zoomBy(1 / 1.5f, w / 2.0, h / 2.0));
        roundButton(g, zx - r * 2 - 3 * k, h - pad - r, r, "+", () -> zoomBy(1.5f, w / 2.0, h / 2.0));

        if (MapConfig.debug) {
            String debug = Minimap.debugLine();
            Ui.text(g, debug, pad, pad + pillH + 4 * k, Ui.PINK);
        }
    }

    private float button(GuiGraphics g, float x, float y, float h, float k, String label, Runnable press) {
        float w = Ui.width(label) + 12 * k;
        Ui.pill(g, hits, x, y, x + w, y + h, label, false, mouseX, mouseY, press);
        return x + w + 3 * k;
    }

    private void roundButton(GuiGraphics g, float cx, float cy, float r, String label, Runnable press) {
        Ui.pill(g, hits, cx - r, cy - r, cx + r, cy + r, label, false, mouseX, mouseY, press);
    }

    private void drawMenu(GuiGraphics g, MapClient.Session s) {
        float k = Ui.scale(), rowH = Ui.lineHeight() + 7 * k;
        String add = "Add waypoint here";
        String edit = menuWaypoint == null ? null : "Edit " + menuWaypoint.name;
        float rowW = Math.max(Ui.width(add), edit == null ? 0 : Ui.width(edit)) + 12 * k;
        int rows = edit == null ? 1 : 2;
        float pad = 3 * k;
        float x0 = menuX, y0 = menuY;
        Ui.plate(g, x0, y0, x0 + rowW + pad * 2, y0 + rows * rowH + (rows + 1) * pad, 12, Ui.PLUM);
        hits.add(0, 0, 1 << 20, 1 << 20, () -> menuOpen = false); // a click anywhere else shuts the menu
        Ui.pill(g, hits, x0 + pad, y0 + pad, x0 + pad + rowW, y0 + pad + rowH, add, false, mouseX, mouseY, () -> {
            menuOpen = false;
            LocalPlayer p = Minecraft.getInstance().player;
            int y = p == null ? 64 : p.getBlockY();
            Minecraft.getInstance().setScreen(WaypointScreen.newAt(this, menuBlockX, y, menuBlockZ));
        });
        if (edit != null) {
            Waypoint wp = menuWaypoint;
            Ui.pill(g, hits, x0 + pad, y0 + pad * 2 + rowH, x0 + pad + rowW, y0 + (pad + rowH) * 2, edit, false, mouseX, mouseY, () -> {
                menuOpen = false;
                Minecraft.getInstance().setScreen(new WaypointScreen(this, wp, false));
            });
        }
    }

    private void centreOnPlayer() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return;
        zooming = false;
        shownZoom = zoom;
        centreX = p.getX();
        centreZ = p.getZ();
    }

    private void zoomBy(float by, double screenX, double screenY) {
        var w = Minecraft.getInstance().getWindow();
        double sx = screenX - w.getWidth() / 2.0, sy = screenY - w.getHeight() / 2.0;
        anchorX = centreX + sx / shownZoom;
        anchorZ = centreZ + sy / shownZoom;
        anchorScreenX = sx;
        anchorScreenY = sy;
        zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom * by));
        savedZoom = zoom;
        zooming = true;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double delta) {
        measureMouse();
        zoomBy((float) Math.pow(1.3, delta), mouseX, mouseY);
        return true;
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        measureMouse();
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && hits.press(mouseX, mouseY)) return true;
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            dragging = true;
            zooming = false;
            shownZoom = zoom;
            lastX = mouseX;
            lastY = mouseY;
            return true;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            var w = Minecraft.getInstance().getWindow();
            menuX = (float) mouseX;
            menuY = (float) mouseY;
            menuBlockX = (int) Math.floor(centreX + (mouseX - w.getWidth() / 2.0) / shownZoom);
            menuBlockZ = (int) Math.floor(centreZ + (mouseY - w.getHeight() / 2.0) / shownZoom);
            menuWaypoint = nearestWaypoint(w.getWidth() / 2f, w.getHeight() / 2f);
            menuOpen = true;
            return true;
        }
        return false;
    }

    private Waypoint nearestWaypoint(float cx, float cy) {
        MapClient.Session s = MapClient.session;
        if (s == null) return null;
        Waypoint best = null;
        double bestD = 16;
        for (Waypoint wp : MapClient.WAYPOINTS) {
            int[] at = wp.placeIn(s.dimension);
            if (at == null) continue;
            double d = Math.hypot(cx + (at[0] + 0.5 - centreX) * shownZoom - mouseX, cy + (at[2] + 0.5 - centreZ) * shownZoom - mouseY);
            if (d < bestD) {
                bestD = d;
                best = wp;
            }
        }
        return best;
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        measureMouse();
        if (dragging) {
            centreX -= (mouseX - lastX) / shownZoom;
            centreZ -= (mouseY - lastY) / shownZoom;
            lastX = mouseX;
            lastY = mouseY;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        dragging = false;
        hits.release();
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (MapClient.KEY_MAP.matches(key, scan)) {
            onClose();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    // For the automatic walk through the game.
    public void showAt(double x, double z, float newZoom) {
        centreX = x;
        centreZ = z;
        zoom = newZoom;
        shownZoom = newZoom;
        zooming = false;
    }
}
