package com.lisheng.simplerecorder.client;

import com.lisheng.simplerecorder.avif.AvifSupport;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;

/**
 * Client-only wiring, called from {@code FMLPreInitializationEvent} when the
 * physical side is CLIENT.
 */
public final class ClientSetup {
    private ClientSetup() {
    }

    public static void init() {
        MinecraftForge.EVENT_BUS.register(ClientEvents.class);
        // 1.12.2 requires key bindings to exist during preInit.
        ClientRegistry.registerKeyBinding(ClientEvents.RECORD_KEY);
        ClientRegistry.registerKeyBinding(ClientEvents.CONFIG_KEY);
        AvifSupport.prepareAsync();
    }
}
