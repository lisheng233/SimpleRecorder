package com.lisheng.simplerecorder;

import com.mojang.logging.LogUtils;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;

/**
 * Common entrypoint.
 *
 * <p>SimpleRecorder is client only: nothing is registered on the logical
 * server, and the mod never touches the registry or the network. This class
 * stays deliberately empty so the mod is safe to leave in a server's
 * {@code mods} folder (it loads, does nothing, and the client entrypoint is
 * skipped).</p>
 */
public final class SimpleRecorder implements ModInitializer {
    public static final String MOD_ID = "simplerecorder";
    private static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public void onInitialize() {
        LOGGER.info("SimpleRecorder loaded (client-only; the client entrypoint does the work)");
    }
}
