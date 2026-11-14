package com.lisheng.simplerecorder.mixin;

import com.lisheng.simplerecorder.client.SimpleRecorderClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures the frame at the only point where it is both complete and safe to
 * read: after {@code Minecraft#renderFrame} submitted and presented it.
 *
 * <p>Reading earlier (for example from a HUD element) would either run before
 * the GUI has been drawn, or, on Vulkan, record the copy before the GUI draw
 * commands in the same submission. Reading here means the main render target
 * already contains the finished frame including the HUD, exactly like vanilla's
 * F2 screenshot, and both backends read the same texture.</p>
 *
 * <p>26.1+ is unobfuscated, so no refmap is involved and the target uses plain
 * official names.</p>
 */
@Mixin(Minecraft.class)
public class MinecraftFrameCaptureMixin {
    @Inject(method = "renderFrame(Z)V", at = @At("TAIL"))
    private void simplerecorder$captureAfterFrame(boolean renderLevel, CallbackInfo ci) {
        SimpleRecorderClient.onFrameRendered();
    }
}
