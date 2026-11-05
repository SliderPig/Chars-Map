package com.example.charmap.client;

import com.example.charmap.model.Waypoint;
import com.mojang.blaze3d.platform.InputConstants;
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
    private static boolean threeD = MapConfig.map3d;
    private final Map3D view3d = new Map3D();
    private boolean turning, cameraSet;
    private long lastDrag;

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

        if (threeD && s != null) {
            flyKeys(mc, dt);
            g.flush();
            view3d.render(g, s, w, h, partial);
        }
        Ui.toRealPixels(g);
        RenderSystem.disableDepthTest();
        hits.clear();
        if (threeD && s != null) {
            Ui.begin(g);
            drawMarkers3D(g, s, partial);
            drawBars(g, s, w, h);
            Ui.after(() -> Icons.flush(g));
            Ui.end(g);
        } else if (s != null) {
            g.fill(0, 0, w, h, 0xFF120C14);
            float cx = w / 2f, cy = h / 2f;
            float[] shape = {-cx, -cy, cx, -cy, cx, cy, -cx, cy};
            g.flush();
            MapView.draw(g.pose().last().pose(), s.textures, viewLayer(s), shape, cx, cy, centreX, centreZ, shownZoom, 0f, 1f, s.textures.budget() * 3 / 4);
            Ui.begin(g);
            drawMobs(g, s, cx, cy);
            drawWaypoints(g, s, cx, cy);
            drawMates(g, s, cx, cy);
            LocalPlayer p = mc.player;
            if (p != null) {
                float px = cx + (float) (p.getX() - centreX) * shownZoom, py = cy + (float) (p.getZ() - centreZ) * shownZoom;
                Ui.arrow(g, px, py, 9, (float) Math.toRadians(p.getViewYRot(partial) - 180), Ui.PINK, 0xFF1A0F1C);
            }
            drawBars(g, s, w, h);
            Ui.after(() -> Icons.flush(g));
            Ui.end(g);
            if (menuOpen) {
                Ui.begin(g);
                drawMenu(g, s);
                Ui.end(g);
            }
        }
        g.flush();
        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
        g.pose().popPose();
    }

    private void flyKeys(Minecraft mc, float dt) {
        long window = mc.getWindow().getWindow();
        float forward = (InputConstants.isKeyDown(window, GLFW.GLFW_KEY_W) ? 1 : 0) - (InputConstants.isKeyDown(window, GLFW.GLFW_KEY_S) ? 1 : 0);
        float right = (InputConstants.isKeyDown(window, GLFW.GLFW_KEY_D) ? 1 : 0) - (InputConstants.isKeyDown(window, GLFW.GLFW_KEY_A) ? 1 : 0);
        if (forward != 0 || right != 0) view3d.fly(forward, right, dt * 0.8f);
    }

    // Who and what is where, on top of the 3D view.
    private void drawMarkers3D(GuiGraphics g, MapClient.Session s, float partial) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (MapConfig.dots) {
            float r = Math.max(7, Ui.scale() * 4f);
            for (var e : Minimap.NEAR) {
                if (e.isRemoved()) continue;
                float[] at = view3d.project(e.getX(), e.getY() + e.getBbHeight() + 1.5, e.getZ());
                if (at == null) continue;
                if (view3d.drawnAsModel(e)) {
                    // The real thing is standing there: it only needs a name, if it's someone.
                    if (e instanceof net.minecraft.world.entity.player.Player && MapConfig.playerNames) Ui.tag(g, e.getName().getString(), at[0], at[1], 0xD0180E1B, Ui.TEXT);
                    continue;
                }
                int cell = !MapConfig.icons ? -1 : e instanceof net.minecraft.world.entity.player.Player ? Icons.playerCell(e.getUUID()) : Icons.mobCell(e.getType(), s.worker);
                if (cell < 0) {
                    Ui.dot(at[0], at[1], 4, 0xC0000000);
                    Ui.dot(at[0], at[1], 3, Minimap.ringColour(e));
                    continue;
                }
                Ui.dot(at[0], at[1], r + 1.6f, 0xE0180E1B);
                Ui.dot(at[0], at[1], r + 0.8f, Minimap.ringColour(e));
                Icons.add(cell, at[0], at[1], r);
                if (e instanceof net.minecraft.world.entity.player.Player && MapConfig.playerNames) Ui.tag(g, e.getName().getString(), at[0], at[1] + r + 3, 0xD0180E1B, Ui.TEXT);
            }
        }
        for (Waypoint wp : MapClient.WAYPOINTS) {
            if (!wp.shown) continue;
            int[] where = wp.placeIn(s.dimension);
            if (where == null) continue;
            float[] at = view3d.project(where[0] + 0.5, where[1] + 2, where[2] + 0.5);
            if (at == null) continue;
            Ui.dot(at[0], at[1], 9, 0xF0180E1B);
            Ui.disc(g, at[0], at[1], 6.5f, 0xFF000000 | wp.colour);
            Ui.tag(g, wp.name, at[0], at[1] + 12, 0xD0180E1B, wp.dimension.equals(s.dimension) ? Ui.TEXT : Ui.TEXT_DIM);
        }
        for (Teammates.Mate mate : Teammates.list()) {
            if (!Teammates.sameDimension(mate, s.dimension)) continue;
            float[] at = view3d.project(mate.x(), mate.y() + 3, mate.z());
            if (at == null) continue;
            float r = Math.max(10, Ui.scale() * 5.5f);
            Teammates.head(g, mate, at[0], at[1], r);
            Ui.tag(g, mate.name(), at[0], at[1] + r + 4, 0xD0180E1B, Ui.TEXT);
        }
        if (p != null) {
            float[] at = view3d.project(p.getX(), p.getY() + 3, p.getZ());
            if (at != null) Ui.arrow(g, at[0], at[1], 9, (float) Math.toRadians(p.getViewYRot(partial) - 180) + view3d.yaw, Ui.PINK, 0xFF1A0F1C);
        }
    }

    private void resetView() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null) view3d.reset(p.getX(), p.getY(), p.getZ());
    }

    public void set3D(boolean on) {
        Minecraft mc = Minecraft.getInstance();
        if (on != threeD) {
            MapConfig.map3d = on;
            MapConfig.save();
        }
        if (on && !threeD && !cameraSet) {
            cameraSet = true;
            double y = mc.player == null ? 64 : mc.player.getY();
            float h = mc.getWindow().getHeight();
            view3d.look(centreX, y, centreZ, Math.max(60, Math.min(600, h / (2 * (float) Math.tan(Math.toRadians(27))) / shownZoom)));
        }
        if (!on && threeD) {
            centreX = view3d.targetX;
            centreZ = view3d.targetZ;
            view3d.close(MapClient.session);
            zooming = false;
            cameraSet = false;
        }
        threeD = on;
    }

    public Map3D view3d() {
        return view3d;
    }

    @Override
    protected void init() {
        // Coming back to a map that was in 3D when it was last shut
        if (threeD) {
            threeD = false;
            set3D(true);
        }
    }

    @Override
    public void removed() {
        view3d.close(MapClient.session);
    }

    private void drawMobs(GuiGraphics g, MapClient.Session s, float cx, float cy) {
        if (!MapConfig.dots) return;
        float r = Math.max(7, Ui.scale() * 4f) * Math.min(1.4f, Math.max(0.7f, shownZoom));
        for (var e : Minimap.NEAR) {
            if (e.isRemoved()) continue;
            float x = cx + (float) (e.getX() - centreX) * shownZoom, y = cy + (float) (e.getZ() - centreZ) * shownZoom;
            if (x < -20 || y < -20 || x > cx * 2 + 20 || y > cy * 2 + 20) continue;
            int colour = Minimap.ringColour(e);
            int cell = !MapConfig.icons ? -1 : e instanceof net.minecraft.world.entity.player.Player ? Icons.playerCell(e.getUUID()) : Icons.mobCell(e.getType(), s.worker);
            if (cell < 0) {
                Ui.dot(x, y, 4, 0xC0000000);
                Ui.dot(x, y, 3, colour);
                continue;
            }
            Ui.dot(x, y, r + 1.6f, 0xE0180E1B);
            Ui.dot(x, y, r + 0.8f, colour);
            Icons.add(cell, x, y, r);
            boolean over = Math.abs(mouseX - x) < r && Math.abs(mouseY - y) < r;
            if (over) Ui.tag(g, e.getName().getString(), x, y + r + 3, 0xD0180E1B, Ui.TEXT);
            else if (e instanceof net.minecraft.world.entity.player.Player && MapConfig.playerNames) Ui.tag(g, e.getName().getString(), x, y + r + 3, 0xD0180E1B, Ui.TEXT);
        }
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
        if (threeD) where = "Drag to turn  ·  Right drag or WASD to move  ·  Wheel to zoom";
        int tw = Ui.width(where);
        Ui.plate(g, pad, pad, pad + tw + 10 * k, pad + pillH, pillH / 2, Ui.PLUM);
        Ui.text(g, where, pad + 5 * k, pad + (pillH - line) / 2 + 1, Ui.TEXT);

        // Fill progress
        if (Filler.active()) {
            String text = Filler.status + "  ·  " + Filler.regionsDone + " of " + Filler.regionsTotal + " regions  ·  " + Math.round(Filler.speed()) + " chunks/s";
            float barW = Math.min(w - pad * 2, Ui.width(text) + 14 * k), x0 = w / 2f - barW / 2, h2 = line + 9 * k;
            float top = threeD ? pad + pillH + 3 * k : pad; // under the help line in 3D
            Ui.plate(g, x0, top, x0 + barW, top + h2 + 8, 12, Ui.PLUM);
            Ui.textCentred(g, text, w / 2f, top + 3 * k, Ui.TEXT);
            float amount = Filler.regionsTotal == 0 ? 0 : (float) Filler.regionsDone / Filler.regionsTotal;
            float tx0 = x0 + 5 * k, tx1 = x0 + barW - 5 * k, ty = top + h2 + 2 - 2 * k;
            Ui.round(g, tx0, ty, tx1, ty + 2.5f * k, 1.25f * k, Ui.PLUM_DARK);
            Ui.round(g, tx0, ty, tx0 + (tx1 - tx0) * amount, ty + 2.5f * k, 1.25f * k, Ui.PINK);
        }

        // Buttons
        float y = h - pad - pillH, x = pad;
        x = button(g, x, y, pillH, k, "Centre on me", this::centreOnPlayer);
        x = button(g, x, y, pillH, k, threeD ? "Flat map" : "3D view", () -> set3D(!threeD));
        if (threeD) {
            x = button(g, x, y, pillH, k, "Reset view", this::resetView);
            // Why some mobs aren't there.
            float qr = pillH / 2, qx = x + qr;
            boolean overHelp = Math.hypot(mouseX - qx, mouseY - (y + qr)) <= qr;
            Ui.pill(g, null, qx - qr, y, qx + qr, y + pillH, "?", overHelp, mouseX, mouseY, () -> {});
            x = qx + qr + 3 * k;
            if (overHelp) {
                String[] lines = {"Mobs and players only show where the game has them loaded,", "which is near you. Teammates further away show as faces.", "Far from you the land is still there, just without anyone on it."};
                float wide = 0;
                for (String t : lines) wide = Math.max(wide, Ui.width(t));
                float boxH = lines.length * (line + 2 * k) + 8 * k, bx0 = qx - qr, by0 = y - boxH - 4 * k;
                Ui.plate(g, bx0, by0, bx0 + wide + 12 * k, by0 + boxH, 12, Ui.PLUM);
                for (int i = 0; i < lines.length; i++) Ui.text(g, lines[i], bx0 + 6 * k, by0 + 4 * k + i * (line + 2 * k), Ui.TEXT);
            }
        }
        x = button(g, x, y, pillH, k, "Waypoints", () -> Minecraft.getInstance().setScreen(new WaypointListScreen(this)));
        x = button(g, x, y, pillH, k, "Settings", () -> Minecraft.getInstance().setScreen(new SettingsScreen(this)));

        // Zoom, round buttons at the right
        float r = pillH / 2, zx = w - pad - r;
        roundButton(g, zx, h - pad - r, r, "-", () -> {
            if (threeD) view3d.zoom(-2);
            else zoomBy(1 / 1.5f, w / 2.0, h / 2.0);
        });
        roundButton(g, zx - r * 2 - 3 * k, h - pad - r, r, "+", () -> {
            if (threeD) view3d.zoom(2);
            else zoomBy(1.5f, w / 2.0, h / 2.0);
        });

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
        view3d.targetX = p.getX();
        view3d.targetY = p.getY();
        view3d.targetZ = p.getZ();
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
        if (threeD) view3d.zoom(delta);
        else zoomBy((float) Math.pow(1.3, delta), mouseX, mouseY);
        return true;
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        measureMouse();
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && hits.press(mouseX, mouseY)) return true;
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && threeD) {
            turning = true;
            view3d.turning = true;
            lastX = mouseX;
            lastY = mouseY;
            lastDrag = System.nanoTime();
            return true;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            dragging = true;
            zooming = false;
            shownZoom = zoom;
            lastX = mouseX;
            lastY = mouseY;
            return true;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && threeD) {
            dragging = true;
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
        if (turning) {
            long now = System.nanoTime();
            view3d.turn(mouseX - lastX, mouseY - lastY, (now - lastDrag) / 1e9f);
            lastDrag = now;
            lastX = mouseX;
            lastY = mouseY;
            return true;
        }
        if (dragging && threeD) {
            view3d.pan(mouseX - lastX, mouseY - lastY);
            lastX = mouseX;
            lastY = mouseY;
            return true;
        }
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
        turning = false;
        view3d.turning = false;
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
