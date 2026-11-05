package com.example.charmap.client;

import com.example.charmap.CharMap;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphics;

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

    // A round head with a thin pink ring. Comes out of the shared face texture like everyone else's.
    public static void head(GuiGraphics g, Mate mate, float cx, float cy, float radius) {
        Ui.dot(cx, cy, radius + 3.4f, 0xE0180E1B);
        Ui.disc(g, cx, cy, radius + 2.2f, Ui.PINK);
        Icons.add(Icons.playerCell(mate.id()), cx, cy, radius);
    }
}
