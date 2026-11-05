package com.lisheng.simplerecorder;

import com.lisheng.simplerecorder.client.ClientSetup;
import com.lisheng.simplerecorder.config.RecorderConfig;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

/**
 * Client-only entry point.
 *
 * <p>The mod declares {@code clientSideOnly = true}, so a dedicated server
 * refuses to load it and the client classes below are never touched off the
 * client. The server-side {@code runServer} task exists purely for Forge's
 * build harness.</p>
 */
@Mod(modid = SimpleRecorder.MODID,
        name = "SimpleRecorder",
        version = "1.12.2-1.0.0",
        acceptedMinecraftVersions = "[1.12.2]",
        clientSideOnly = true,
        guiFactory = "com.lisheng.simplerecorder.client.RecorderGuiFactory")
public final class SimpleRecorder {
    public static final String MODID = "simplerecorder";

    @EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        RecorderConfig.load(event.getSuggestedConfigurationFile());
        if (event.getSide().isClient()) {
            ClientSetup.init();
        }
    }
}
