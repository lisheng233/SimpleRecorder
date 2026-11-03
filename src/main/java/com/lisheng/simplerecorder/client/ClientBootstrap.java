package com.lisheng.simplerecorder.client;

import com.lisheng.simplerecorder.avif.AvifSupport;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Client-only wiring. Called from the main mod constructor only on the
 * physical client, so a dedicated server never touches client classes.
 */
public final class ClientBootstrap {
    private ClientBootstrap() {
    }

    public static void init(IEventBus modBus, ModContainer modContainer) {
        modBus.addListener(ClientEvents::onRegisterKeyMappings);
        modBus.addListener(ClientEvents::onConfigReloading);
        modBus.addListener((FMLClientSetupEvent event) -> AvifSupport.retryAfterConfig());
        NeoForge.EVENT_BUS.register(ClientEvents.class);

        modContainer.registerExtensionPoint(IConfigScreenFactory.class,
                (container, parent) -> new RecorderConfigScreen(parent));
    }
}
