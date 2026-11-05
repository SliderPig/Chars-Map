package com.example.charmap.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

// Everything in one rounded panel, changes show straight away on the minimap behind it.
public final class SettingsScreen extends Screen {
    private final Screen back;
    private final Ui.Hits hits = new Ui.Hits();
    private double mouseX, mouseY;

    public SettingsScreen(Screen back) {
        super(Component.literal("Map settings"));
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
        Ui.toRealPixels(g);
        RenderSystem.disableDepthTest();
        g.fill(0, 0, w, h, 0x60000000);
        hits.clear();

        float k = Ui.scale(), line = Ui.lineHeight();
        float rowH = line + 7 * k, gap = 2.5f * k, columnW = 158 * k, labelW = 62 * k, pad = 8 * k;
        float panelW = columnW * 2 + pad * 3;
        int rows = 9;
        float panelH = line + 8 * k + (rowH + gap) * rows + pad * 2 + rowH;
        // On a small window, shrink the gap rather than run off the screen.
        float x0 = Math.max(4, w / 2f - panelW / 2), y0 = Math.max(4, h / 2f - panelH / 2);
        Ui.plate(g, x0, y0, x0 + panelW, y0 + panelH, 16, Ui.PLUM);
        Ui.textCentred(g, "Char's Map", x0 + panelW / 2, y0 + 4 * k, Ui.PINK);
        float top = y0 + line + 8 * k;

        // Left column: the minimap
        float x = x0 + pad, cw = columnW - labelW;
        float y = top;
        y = toggle(g, "Minimap", x, y, labelW, cw, rowH, gap, MapConfig.showMinimap ? "On" : "Off", MapConfig.showMinimap, () -> MapConfig.showMinimap = !MapConfig.showMinimap);
        y = toggle(g, "Shape", x, y, labelW, cw, rowH, gap, MapConfig.circle ? "Circle" : "Rounded", false, () -> MapConfig.circle = !MapConfig.circle);
        Ui.text(g, "Corner", x, y + (rowH - line) / 2, Ui.TEXT_DIM);
        String[] corners = {"TL", "TR", "BL", "BR"};
        float each = (cw - gap * 3) / 4;
        for (int i = 0; i < 4; i++) {
            int index = i;
            float bx = x + labelW + i * (each + gap);
            Ui.pill(g, hits, bx, y, bx + each, y + rowH, corners[i], MapConfig.corner == i, mouseX, mouseY, () -> MapConfig.corner = index);
        }
        y += rowH + gap;
        y = slider(g, "Size", x, y, labelW, cw, rowH, gap, (MapConfig.size - 80) / 420f, a -> MapConfig.size = 80 + (int) Math.round(a * 420), MapConfig.size + "");
        y = slider(g, "Opacity", x, y, labelW, cw, rowH, gap, (MapConfig.opacity - 0.2f) / 0.8f, a -> MapConfig.opacity = 0.2f + (float) a * 0.8f, Math.round(MapConfig.opacity * 100) + "%");
        y = slider(g, "Zoom", x, y, labelW, cw, rowH, gap, MapConfig.zoom / (float) (MapConfig.ZOOMS.length - 1), a -> MapConfig.zoom = (int) Math.round(a * (MapConfig.ZOOMS.length - 1)), "x" + trim(MapConfig.pixelsPerBlock()));
        y = toggle(g, "Rotate", x, y, labelW, cw, rowH, gap, MapConfig.rotate ? "Turns with you" : "North up", MapConfig.rotate, () -> MapConfig.rotate = !MapConfig.rotate);
        y = toggle(g, "Mob dots", x, y, labelW, cw, rowH, gap, MapConfig.dots ? "On" : "Off", MapConfig.dots, () -> MapConfig.dots = !MapConfig.dots);
        y = toggle(g, "Text", x, y, labelW, cw, rowH, gap, MapConfig.infoText ? "Biome + coords" : "Off", MapConfig.infoText, () -> MapConfig.infoText = !MapConfig.infoText);

        // Right column: the world map and how it fills
        x = x0 + pad * 2 + columnW;
        y = top;
        y = toggle(g, "Fill map", x, y, labelW, cw, rowH, gap, MapConfig.fillEnabled ? "From generated" : "Off", MapConfig.fillEnabled, () -> MapConfig.fillEnabled = !MapConfig.fillEnabled);
        y = slider(g, "Fill reach", x, y, labelW, cw, rowH, gap, (MapConfig.fillRadius - 1) / 31f, a -> MapConfig.fillRadius = 1 + (int) Math.round(a * 31), MapConfig.fillRadius * 512 + "");
        Ui.pill(g, hits, x, y, x + columnW, y + rowH, Filler.active() ? "Stop filling" : "Fill now (whole dimension)", Filler.active(), mouseX, mouseY, () -> {
            if (Filler.active()) Filler.stop();
            else Filler.fillNow(true);
        });
        y += rowH + gap;
        y = toggle(g, "Caves", x, y, labelW, cw, rowH, gap, MapConfig.caveMode == 1 ? "Automatic" : "Off", MapConfig.caveMode == 1, () -> MapConfig.caveMode = 1 - MapConfig.caveMode);
        y = toggle(g, "Nether", x, y, labelW, cw, rowH, gap, MapConfig.netherMode == 0 ? "Below the roof" : "Around your Y", false, () -> MapConfig.netherMode = 1 - MapConfig.netherMode);
        y = toggle(g, "Beacons", x, y, labelW, cw, rowH, gap, MapConfig.beacons ? "On" : "Off", MapConfig.beacons, () -> MapConfig.beacons = !MapConfig.beacons);
        y = toggle(g, "Compass", x, y, labelW, cw, rowH, gap, MapConfig.compass ? "On" : "Off", MapConfig.compass, () -> MapConfig.compass = !MapConfig.compass);
        y = toggle(g, "Memory", x, y, labelW, cw, rowH, gap, MapConfig.textureMemoryMb + " MB, next join", false, () -> MapConfig.textureMemoryMb = MapConfig.textureMemoryMb >= 128 ? 32 : MapConfig.textureMemoryMb * 2);
        y = toggle(g, "Debug line", x, y, labelW, cw, rowH, gap, MapConfig.debug ? "On" : "Off", MapConfig.debug, () -> MapConfig.debug = !MapConfig.debug);

        float bottom = y0 + panelH - pad - rowH;
        Ui.pill(g, hits, x0 + pad, bottom, x0 + pad + columnW, bottom + rowH, MapConfig.enabled ? "Whole mod: on" : "Whole mod: resting", MapConfig.enabled, mouseX, mouseY, () -> MapConfig.enabled = !MapConfig.enabled);
        Ui.pill(g, hits, x0 + pad * 2 + columnW, bottom, x0 + pad * 2 + columnW * 2, bottom + rowH, "Done", false, mouseX, mouseY, this::onClose);

        g.flush();
        RenderSystem.enableDepthTest();
        g.pose().popPose();
    }

    private static String trim(float f) {
        return f == (int) f ? Integer.toString((int) f) : Float.toString(f);
    }

    private float toggle(GuiGraphics g, String label, float x, float y, float labelW, float cw, float rowH, float gap, String value, boolean on, Runnable press) {
        Ui.text(g, label, x, y + (rowH - Ui.lineHeight()) / 2, Ui.TEXT_DIM);
        Ui.pill(g, hits, x + labelW, y, x + labelW + cw, y + rowH, value, on, mouseX, mouseY, press);
        return y + rowH + gap;
    }

    private float slider(GuiGraphics g, String label, float x, float y, float labelW, float cw, float rowH, float gap, float amount, java.util.function.DoubleConsumer change, String value) {
        Ui.text(g, label + "  " + value, x, y + (rowH - Ui.lineHeight()) / 2, Ui.TEXT_DIM);
        Ui.slider(g, hits, x + labelW + 6, y, x + labelW + cw - 6, rowH, amount, mouseX, mouseY, change);
        return y + rowH + gap;
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        measureMouse();
        return button == GLFW.GLFW_MOUSE_BUTTON_LEFT && hits.press(mouseX, mouseY);
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        measureMouse();
        return hits.dragTo(mouseX, mouseY);
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        hits.release();
        return true;
    }

    @Override
    public void onClose() {
        MapConfig.save();
        Minecraft.getInstance().setScreen(back);
    }
}
