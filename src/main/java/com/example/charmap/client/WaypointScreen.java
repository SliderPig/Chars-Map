package com.example.charmap.client;

import com.example.charmap.model.Waypoint;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

// Add or change one waypoint: a name, where it is and a colour.
public final class WaypointScreen extends Screen {
    private static final int[] COLOURS = {0xFF7EB6, 0xE5484D, 0xF5A524, 0xF5E050, 0x6BD968, 0x4CC9F0, 0x7A7FFF, 0xE9E3EC};
    private static int nextColour;

    private final Screen back;
    private final Waypoint waypoint;
    private final boolean isNew;
    private final Ui.Hits hits = new Ui.Hits();
    private EditBox name, xBox, yBox, zBox;
    private int colour;
    private double mouseX, mouseY;

    public WaypointScreen(Screen back, Waypoint waypoint, boolean isNew) {
        super(Component.literal("Waypoint"));
        this.back = back;
        this.waypoint = waypoint;
        this.isNew = isNew;
        this.colour = waypoint.colour;
    }

    public static WaypointScreen newHere(Screen back) {
        LocalPlayer p = Minecraft.getInstance().player;
        return p == null ? newAt(back, 0, 64, 0) : newAt(back, p.getBlockX(), p.getBlockY(), p.getBlockZ());
    }

    public static WaypointScreen newAt(Screen back, int x, int y, int z) {
        MapClient.Session s = MapClient.session;
        String dim = s == null ? Waypoint.OVERWORLD : s.dimension;
        int colour = COLOURS[nextColour++ % COLOURS.length];
        return new WaypointScreen(back, new Waypoint("Place " + (MapClient.WAYPOINTS.size() + 1), x, y, z, dim, colour, false), true);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private float panelW() {
        return 190 * Ui.scale();
    }

    private float panelH() {
        float k = Ui.scale();
        return 118 * k;
    }

    @Override
    protected void init() {
        // These boxes live in GUI units: only the things drawn around them are in real pixels.
        int pw = 190, ph = 118;
        int x0 = width / 2 - pw / 2, y0 = height / 2 - ph / 2;
        name = box(x0 + 40, y0 + 20, pw - 50, waypoint.name, 32);
        xBox = box(x0 + 40, y0 + 39, 40, "" + waypoint.x, 9);
        yBox = box(x0 + 85, y0 + 39, 30, "" + waypoint.y, 5);
        zBox = box(x0 + 120, y0 + 39, 60, "" + waypoint.z, 9);
        setInitialFocus(name);
    }

    private EditBox box(int x, int y, int w, String value, int max) {
        EditBox b = new EditBox(font, x, y, w, 13, Component.empty());
        b.setBordered(false);
        b.setMaxLength(max);
        b.setValue(value);
        b.setTextColor(Ui.TEXT);
        addRenderableWidget(b);
        return b;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        Minecraft mc = Minecraft.getInstance();
        var window = mc.getWindow();
        mouseX = mc.mouseHandler.xpos() * window.getWidth() / Math.max(1, window.getScreenWidth());
        mouseY = mc.mouseHandler.ypos() * window.getHeight() / Math.max(1, window.getScreenHeight());
        int w = window.getWidth(), h = window.getHeight();
        float k = Ui.scale();
        hits.clear();

        Ui.toRealPixels(g);
        RenderSystem.disableDepthTest();
        g.fill(0, 0, w, h, 0x70000000);
        Ui.begin(g);
        float pw = panelW(), ph = panelH();
        float x0 = (Math.round(width / 2f - 95)) * k, y0 = (Math.round(height / 2f - 59)) * k;
        Ui.plate(g, x0, y0, x0 + pw, y0 + ph, 16, Ui.PLUM);
        Ui.textCentred(g, isNew ? "New waypoint" : "Waypoint", x0 + pw / 2, y0 + 5 * k, Ui.PINK);

        Ui.text(g, "Name", x0 + 8 * k, y0 + 21 * k, Ui.TEXT_DIM);
        well(g, x0 + 37 * k, y0 + 18 * k, x0 + pw - 7 * k, y0 + 33 * k);
        Ui.text(g, "Place", x0 + 8 * k, y0 + 40 * k, Ui.TEXT_DIM);
        well(g, x0 + 37 * k, y0 + 37 * k, x0 + 82 * k, y0 + 52 * k);
        well(g, x0 + 84 * k, y0 + 37 * k, x0 + 117 * k, y0 + 52 * k);
        well(g, x0 + 119 * k, y0 + 37 * k, x0 + pw - 7 * k, y0 + 52 * k);

        Ui.text(g, "Colour", x0 + 8 * k, y0 + 60 * k, Ui.TEXT_DIM);
        float r = 6 * k, sx = x0 + 44 * k, sy = y0 + 64 * k;
        for (int i = 0; i < COLOURS.length; i++) {
            float cx = sx + i * (r * 2 + 3 * k);
            int c = COLOURS[i];
            if (c == colour) Ui.disc(g, cx, sy, r + 1.5f * k, Ui.TEXT);
            Ui.disc(g, cx, sy, r, 0xFF000000 | c);
            hits.add(cx - r, sy - r, cx + r, sy + r, () -> colour = c);
        }

        float by = y0 + ph - 25 * k, bh = Ui.lineHeight() + 7 * k, each = (pw - 16 * k - 6 * k) / 3;
        Ui.pill(g, hits, x0 + 8 * k, by, x0 + 8 * k + each, by + bh, "Save", true, mouseX, mouseY, this::save);
        Ui.pill(g, hits, x0 + 11 * k + each, by, x0 + 11 * k + each * 2, by + bh, "Cancel", false, mouseX, mouseY, this::onClose);
        if (!isNew) Ui.pill(g, hits, x0 + 14 * k + each * 2, by, x0 + 14 * k + each * 3, by + bh, "Delete", false, mouseX, mouseY, this::delete);

        Ui.end(g);
        g.flush();
        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
        g.pose().popPose();
        RenderSystem.disableDepthTest();
        super.render(g, mx, my, partial);
        g.flush();
        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
    }

    private static void well(GuiGraphics g, float x0, float y0, float x1, float y1) {
        Ui.round(g, x0, y0, x1, y1, (y1 - y0) * 0.45f, Ui.PLUM_DARK);
    }

    private static int parsed(EditBox box, int fallback) {
        try {
            return Integer.parseInt(box.getValue().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void save() {
        String n = name.getValue().trim();
        waypoint.name = n.isEmpty() ? "Place" : n;
        waypoint.x = parsed(xBox, waypoint.x);
        waypoint.y = parsed(yBox, waypoint.y);
        waypoint.z = parsed(zBox, waypoint.z);
        waypoint.colour = colour;
        if (isNew) MapClient.WAYPOINTS.add(waypoint);
        MapClient.saveWaypoints();
        Minecraft.getInstance().setScreen(back);
    }

    private void delete() {
        MapClient.WAYPOINTS.remove(waypoint);
        MapClient.saveWaypoints();
        Minecraft.getInstance().setScreen(back);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            save();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        Minecraft mc = Minecraft.getInstance();
        var window = mc.getWindow();
        mouseX = mc.mouseHandler.xpos() * window.getWidth() / Math.max(1, window.getScreenWidth());
        mouseY = mc.mouseHandler.ypos() * window.getHeight() / Math.max(1, window.getScreenHeight());
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && hits.press(mouseX, mouseY)) return true;
        return super.mouseClicked(x, y, button);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(back);
    }
}
