package com.siris;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

public class Siris implements ClientModInitializer {

    public static final Logger LOGGER = LogUtils.getLogger();
    public static Path nativeDir;
    private static boolean bootstrapped;
    private static boolean nativeLoaded;

    @Override
    public void onInitializeClient() {
        bootstrap();
    }

    /**
     * Config and native bridge. Idempotent: the Vulkan device mixins may need it before the client
     * entrypoint runs.
     */
    public static synchronized void bootstrap() {
        if (bootstrapped) {
            return;
        }
        bootstrapped = true;
        DlssConfig.load(DlssConfig.configFile());
        if (DlssConfig.diagnosticRecording) {
            DiagnosticRecording.start(FabricLoader.getInstance().getGameDir());
        }
        nativeDir = FabricLoader.getInstance().getGameDir().resolve("siris");
        try {
            Files.createDirectories(nativeDir);
            for (String name : new String[] {"siris.dll", "nvngx_dlss.dll", "nvngx_dlssg.dll",
                "NVIDIA_DLSS_LICENSE.txt"}) {
                try (InputStream in = Siris.class.getResourceAsStream("/natives/" + name)) {
                    if (in == null) {
                        throw new IOException("missing /natives/" + name);
                    }
                    Path target = nativeDir.resolve(name);
                    try {
                        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) {
                        if (!Files.exists(target)) {
                            throw e;
                        }
                    }
                }
            }
            System.load(nativeDir.resolve("siris.dll").toAbsolutePath().toString());
            DlssNative.setDataPath(nativeDir.toAbsolutePath().toString());
            nativeLoaded = true;
            if (DlssConfig.enabled && DlssConfig.disablePackTaa) {
                ShaderPackTweaks.disablePackTaa(FabricLoader.getInstance().getGameDir());
            }
        } catch (IOException | UnsatisfiedLinkError e) {
            LOGGER.error("SIRIS: could not load the native bridge, DLSS stays off", e);
            DlssConfig.enabled = false;
        }
    }

    /** Vitrail (OptiFine-format shader packs on Vulkan) is installed. */
    public static boolean isVitrailLoaded() {
        return FabricLoader.getInstance().isModLoaded("vitrail");
    }

    public static boolean isNativeLoaded() {
        return nativeLoaded;
    }

    /** The bridge is loaded and DLSS is wanted: the game's Vulkan device should be made for it. */
    public static boolean nativeReady() {
        bootstrap();
        return nativeLoaded && DlssConfig.enabled;
    }
}
