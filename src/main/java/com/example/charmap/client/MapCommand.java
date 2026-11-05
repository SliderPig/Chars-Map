package com.example.charmap.client;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

// /charmap fill, stop, clear, debug. Only your own PC hears it.
final class MapCommand {
    private MapCommand() {}

    static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("charmap")
                .then(Commands.literal("fill")
                        .executes(c -> fill(false))
                        .then(Commands.literal("all").executes(c -> fill(true))))
                .then(Commands.literal("stop").executes(c -> {
                    Filler.stop();
                    say("Fill stopped.");
                    return 1;
                }))
                .then(Commands.literal("clear").executes(c -> {
                    MapClient.Session s = MapClient.session;
                    if (s == null) return 0;
                    Filler.stop();
                    s.textures.clearAll();
                    s.worker.clearAll(() -> {});
                    say("This dimension's map is cleared. It will fill back in from what you see.");
                    return 1;
                }))
                .then(Commands.literal("debug").executes(c -> {
                    MapConfig.debug = !MapConfig.debug;
                    MapConfig.save();
                    say(Minimap.debugLine());
                    return 1;
                })));
    }

    private static int fill(boolean all) {
        if (Filler.fillNow(all)) say(all ? "Filling the map from every generated chunk in this dimension." : "Filling the map round you from generated chunks.");
        return 1;
    }

    private static void say(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(Component.literal(text), false);
    }
}
