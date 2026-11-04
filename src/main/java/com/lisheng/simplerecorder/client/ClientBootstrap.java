package com.lisheng.simplerecorder.client;

import com.lisheng.simplerecorder.avif.AvifSupport;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * Client-only wiring. Loaded only through {@code DistExecutor} so a dedicated
 * server never touches client classes. 1.16.5 has no mod-list config factory
 * extension, so the settings screen is opened with F9.
 */
public final class ClientBootstrap {
    private ClientBootstrap() {
    }

    public static void init() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener((FMLClientSetupEvent event) -> {
            ClientEvents.onClientSetup();
            AvifSupport.retryAfterConfig();
        });
        modBus.addListener(ClientEvents::onConfigReloading);
        MinecraftForge.EVENT_BUS.register(ClientEvents.class);
    }
}
