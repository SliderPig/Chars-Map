package com.example.charmap.client;

import com.example.charmap.CharMap;
import com.example.charmap.model.Shapes;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

// Friends on the map, through the teams mod when it's there. Without that mod there's simply nobody to show.
public final class Teammates {
    private Teammates() {}

    public record Mate(UUID id, String name, double x, double y, double z, String dimension, float health, float maxHealth) {}

    private static List<Mate> mates = List.of();
    // Other code can set this for checks, instead of the teams mod.
    public static List<Mate> extra = List.of();

    private static boolean looked, there;
    private static Method matesMethod, id, name, x, y, z, dimension, health, maxHealth;
    private static int age;

    private static void find() {
        looked = true;
        try {
            Class<?> client = Class.forName("com.example.charteams.api.CharTeamsClient");
            matesMethod = client.getMethod("mates");
            Class<?> mate = Class.forName("com.example.charteams.api.Mate");
            id = mate.getMethod("id");
            name = mate.getMethod("name");
            x = mate.getMethod("x");
            y = mate.getMethod("y");
            z = mate.getMethod("z");
            dimension = mate.getMethod("dimension");
            health = mate.getMethod("health");
            maxHealth = mate.getMethod("maxHealth");
            there = true;
        } catch (Throwable t) {
            there = false; // no teams mod: nothing to do
        }
    }

    static void tick() {
        if (++age % 20 != 1) return;
        if (!looked) find();
        if (!there) return;
        try {
            List<?> raw = (List<?>) matesMethod.invoke(null);
            List<Mate> out = new ArrayList<>(raw.size());
            for (Object m : raw) {
                out.add(new Mate((UUID) id.invoke(m), (String) name.invoke(m), (Double) x.invoke(m), (Double) y.invoke(m), (Double) z.invoke(m), (String) dimension.invoke(m), (Float) health.invoke(m), (Float) maxHealth.invoke(m)));
            }
            mates = out;
        } catch (Throwable t) {
            CharMap.once("teams", "Couldn't read teammates from the teams mod", t);
            there = false;
        }
    }

    public static List<Mate> list() {
        if (extra.isEmpty()) return mates;
        List<Mate> all = new ArrayList<>(mates);
        all.addAll(extra);
        return all;
    }

    public static boolean sameDimension(Mate mate, String here) {
        String d = mate.dimension();
        if (d == null) return false;
        return d.equals(here) || ("minecraft:" + d).equals(here);
    }

    // A round head with the hat layer on top and a thin pink ring.
    public static void head(GuiGraphics g, Mate mate, float cx, float cy, float radius) {
        Minecraft mc = Minecraft.getInstance();
        ResourceLocation skin = DefaultPlayerSkin.getDefaultSkin(mate.id());
        if (mc.getConnection() != null) {
            PlayerInfo info = mc.getConnection().getPlayerInfo(mate.id());
            if (info != null) skin = info.getSkinLocation();
        }
        float[] circle = Shapes.rounded(radius, radius, 8);
        Ui.polygon(g, cx, cy, Shapes.rounded(radius + 2.2f, radius + 2.2f, 8), Shapes.rounded(radius + 3.2f, radius + 3.2f, 8), Ui.PINK);
        face(g, skin, cx, cy, circle, radius, 8f / 64, 8f / 64);
        face(g, skin, cx, cy, circle, radius, 40f / 64, 8f / 64);
    }

    private static void face(GuiGraphics g, ResourceLocation skin, float cx, float cy, float[] circle, float radius, float u, float v) {
        g.flush();
        Matrix4f m = g.pose().last().pose();
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, skin);
        RenderSystem.enableBlend();
        RenderSystem.disableCull();
        RenderSystem.defaultBlendFunc();
        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_TEX);
        float side = 8f / 64, mu = u + side / 2, mv = v + side / 2;
        buf.vertex(m, cx, cy, 0).uv(mu, mv).endVertex();
        int n = circle.length / 2;
        for (int i = 0; i <= n; i++) {
            int k = i % n;
            buf.vertex(m, cx + circle[k * 2], cy + circle[k * 2 + 1], 0).uv(mu + circle[k * 2] / radius * side / 2, mv + circle[k * 2 + 1] / radius * side / 2).endVertex();
        }
        BufferUploader.drawWithShader(buf.end());
    }
}
