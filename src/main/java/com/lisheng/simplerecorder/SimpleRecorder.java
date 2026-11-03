package com.lisheng.simplerecorder;

import com.lisheng.simplerecorder.client.ClientBootstrap;
import com.lisheng.simplerecorder.config.RecorderConfig;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;

/**
 * Client-only entry point.
 *
 * <p>The mod deliberately keeps this class free of client-only types. The
 * client setup class is referenced only after the physical side check, so a
 * dedicated server never loads it.</p>
 */
@Mod(SimpleRecorder.MODID)
public final class SimpleRecorder {
    public static final String MODID = "simplerecorder";

    public SimpleRecorder(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.CLIENT, RecorderConfig.SPEC);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ClientBootstrap.init(modEventBus, modContainer);
        }
    }
}
