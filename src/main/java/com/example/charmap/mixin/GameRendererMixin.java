package com.example.charmap.mixin;

import com.example.charmap.client.MapScreen;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// The full-screen map covers the whole window, so the world behind it doesn't need drawing. That frame time goes to the map instead.
@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
    @Inject(method = "renderLevel", at = @At("HEAD"), cancellable = true)
    private void charmap$nothingBehindTheMap(float partialTick, long nanoTime, PoseStack pose, CallbackInfo ci) {
        if (Minecraft.getInstance().screen instanceof MapScreen) ci.cancel();
    }
}
