package com.irisdlss;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

public class IrisDlss implements ClientModInitializer {

    public static final Logger LOGGER = LogUtils.getLogger();
    public static Path nativeDir;

    @Override
    public void onInitializeClient() {
        DlssConfig.load(FabricLoader.getInstance().getConfigDir().resolve("iris-dlss.properties"));
        nativeDir = FabricLoader.getInstance().getGameDir().resolve("iris-dlss");
        try {
            Files.createDirectories(nativeDir);
            for (String name : new String[] {"irisdlss.dll", "nvngx_dlss.dll", "nvngx_dlssg.dll",
                "NVIDIA_DLSS_LICENSE.txt"}) {
                try (InputStream in = IrisDlss.class.getResourceAsStream("/natives/" + name)) {
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
            System.load(nativeDir.resolve("irisdlss.dll").toAbsolutePath().toString());
            if (DlssConfig.enabled) {
                ShaderPackTweaks.disablePackTaa(FabricLoader.getInstance().getGameDir());
            }
        } catch (IOException | UnsatisfiedLinkError e) {
            LOGGER.error("Iris DLSS: could not load the native bridge, DLSS stays off", e);
            DlssConfig.enabled = false;
        }
    }
}
