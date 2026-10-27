package com.irisdlss;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * A shader pack's own TAA on top of DLSS jitters and blends a second time: the image gets soft and
 * smeary. Before Iris loads the pack, switch its TAA option off in the pack's settings file
 * (shaderpacks/&lt;pack&gt;.txt), the same file Iris' shader pack screen writes.
 */
public final class ShaderPackTweaks {

    /** Boolean pack options that enable temporal anti-aliasing in common packs. */
    private static final Set<String> TAA_OPTIONS = Set.of("TAA", "TAA_DEFINE", "TEMPORAL_AA", "TAA_ENABLED");
    private static final Pattern BOOLEAN_DEFINE =
        Pattern.compile("^\\s*(?://\\s*)?#define\\s+([A-Za-z0-9_]+)\\s*(?://.*)?$", Pattern.MULTILINE);

    private ShaderPackTweaks() {
    }

    public static void disablePackTaa(Path gameDir) {
        try {
            String packName = selectedPack(gameDir);
            if (packName == null) {
                return;
            }
            Path pack = gameDir.resolve("shaderpacks").resolve(packName);
            if (!Files.exists(pack)) {
                return;
            }
            Set<String> options = findTaaOptions(pack);
            if (options.isEmpty()) {
                return;
            }
            Path settings = gameDir.resolve("shaderpacks").resolve(packName + ".txt");
            List<String> lines = Files.exists(settings)
                ? new ArrayList<>(Files.readAllLines(settings, StandardCharsets.ISO_8859_1)) : new ArrayList<>();
            List<String> changed = new ArrayList<>();
            for (String option : options) {
                boolean found = false;
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    int eq = line.indexOf('=');
                    if (!line.startsWith("#") && eq > 0 && line.substring(0, eq).trim().equals(option)) {
                        found = true;
                        if (!line.substring(eq + 1).trim().equals("false")) {
                            lines.set(i, option + "=false");
                            changed.add(option);
                        }
                    }
                }
                if (!found) {
                    lines.add(option + "=false");
                    changed.add(option);
                }
            }
            if (!changed.isEmpty()) {
                Files.write(settings, lines, StandardCharsets.ISO_8859_1);
                IrisDlss.LOGGER.info("Iris DLSS: turned off {} in shader pack {} (DLSS does the anti-aliasing)",
                    changed, packName);
            }
        } catch (IOException | RuntimeException e) {
            IrisDlss.LOGGER.warn("Iris DLSS: could not check the shader pack's TAA setting", e);
        }
    }

    /**
     * The pack the shader mod will load, or null: Vitrail's vitrail/pack.txt when Vitrail is installed
     * (it draws on Vulkan; "pack=" / "enabled=" lines, or one bare word in older files, "none" = off),
     * else Iris' config/iris.properties. Both read their pack settings from shaderpacks/&lt;pack&gt;.txt.
     */
    private static String selectedPack(Path gameDir) throws IOException {
        Path vitrail = gameDir.resolve("vitrail").resolve("pack.txt");
        if (IrisDlss.isVitrailLoaded()) {
            if (!Files.exists(vitrail)) {
                return null;
            }
            List<String> lines = Files.readAllLines(vitrail, StandardCharsets.UTF_8);
            String name = null;
            boolean enabled = true;
            for (String line : lines) {
                int eq = line.indexOf('=');
                if (eq < 0) {
                    if (!line.isBlank()) {
                        name = line.trim();
                    }
                } else if (line.substring(0, eq).trim().equals("pack")) {
                    name = line.substring(eq + 1).trim();
                } else if (line.substring(0, eq).trim().equals("enabled")) {
                    enabled = Boolean.parseBoolean(line.substring(eq + 1).trim());
                }
            }
            return name == null || name.isEmpty() || name.equals("none") || !enabled ? null : name;
        }
        Path irisConfig = gameDir.resolve("config").resolve("iris.properties");
        if (!Files.exists(irisConfig)) {
            return null;
        }
        Properties iris = new Properties();
        try (var reader = Files.newBufferedReader(irisConfig)) {
            iris.load(reader);
        }
        String packName = iris.getProperty("shaderPack", "").trim();
        if (packName.isEmpty() || !Boolean.parseBoolean(iris.getProperty("enableShaders", "true"))) {
            return null;
        }
        return packName;
    }

    private static Set<String> findTaaOptions(Path pack) throws IOException {
        Set<String> found = new LinkedHashSet<>();
        if (Files.isDirectory(pack)) {
            try (Stream<Path> files = Files.walk(pack.resolve("shaders"))) {
                for (Path file : (Iterable<Path>) files::iterator) {
                    if (isSource(file.toString())) {
                        scan(Files.readString(file, StandardCharsets.ISO_8859_1), found);
                    }
                }
            }
        } else {
            try (ZipFile zip = new ZipFile(pack.toFile())) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (!entry.isDirectory() && isSource(entry.getName())) {
                        try (InputStream in = zip.getInputStream(entry)) {
                            scan(new String(in.readAllBytes(), StandardCharsets.ISO_8859_1), found);
                        }
                    }
                }
            }
        }
        return found;
    }

    private static boolean isSource(String name) {
        return name.endsWith(".glsl") || name.endsWith(".fsh") || name.endsWith(".vsh") || name.endsWith(".csh");
    }

    private static void scan(String source, Set<String> found) {
        Matcher m = BOOLEAN_DEFINE.matcher(source);
        while (m.find()) {
            if (TAA_OPTIONS.contains(m.group(1))) {
                found.add(m.group(1));
            }
        }
    }
}
