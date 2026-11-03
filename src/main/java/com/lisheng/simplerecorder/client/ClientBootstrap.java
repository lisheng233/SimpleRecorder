package com.lisheng.simplerecorder.client;

import com.lisheng.simplerecorder.avif.AvifSupport;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * Client-only wiring. Loaded only through {@code DistExecutor} so a dedicated
 * server never touches client classes.
 */
public final class ClientBootstrap {
    private ClientBootstrap() {
    }

    public static void init() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(ClientEvents::onRegisterKeyMappings);
        modBus.addListener(ClientEvents::onConfigReloading);
        modBus.addListener((FMLClientSetupEvent event) -> AvifSupport.retryAfterConfig());
        MinecraftForge.EVENT_BUS.register(ClientEvents.class);

        ModLoadingContext.get().registerExtensionPoint(
                ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory(
                        (minecraft, parent) -> new RecorderConfigScreen(parent)));
    }
}
