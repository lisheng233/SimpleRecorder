package com.lisheng.simplerecorder;

import com.lisheng.simplerecorder.client.ClientBootstrap;
import com.lisheng.simplerecorder.config.RecorderConfig;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;

/**
 * Client-only entry point.
 *
 * <p>The mod deliberately keeps this class free of client-only types. The client
 * setup class is referenced through a supplier so a dedicated server never loads
 * it.</p>
 */
@Mod(SimpleRecorder.MODID)
public final class SimpleRecorder {
    public static final String MODID = "simplerecorder";

    public SimpleRecorder() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, RecorderConfig.SPEC);
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> ClientBootstrap::init);
    }
}
