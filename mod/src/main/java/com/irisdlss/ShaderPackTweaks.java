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
 * smeary. Before the shader mod (Vitrail, or Iris) loads the pack, switch its TAA option off in the
 * pack's settings file (shaderpacks/&lt;pack&gt;.txt), the same file their shader pack screens write.
 */
public final class ShaderPackTweaks {

    /** Boolean pack options that enable temporal anti-aliasing in common packs. */
    private static final Set<String> TAA_OPTIONS = Set.of("TAA", "TAA_DEFINE", "TEMPORAL_AA", "TAA_ENABLED");
    private static final Pattern BOOLEAN_DEFINE =
        Pattern.compile("^\\s*(?://\\s*)?#define\\s+([A-Za-z0-9_]+)\\s*(?://.*)?$", Pattern.MULTILINE);

    /** "#ifdef TAA" and friends, alone on the line (comments after it allowed). */
    private static final Pattern TAA_CONDITION = Pattern.compile(
        "^\\s*#\\s*(?:ifdef\\s+(\\w+)|if\\s+defined\\s*\\(?\\s*(\\w+)\\s*\\)?)\\s*(?://.*)?$");
    private static final Pattern FRAME_COUNTER = Pattern.compile("\\bframe(?:Time)?Counter\\b");

    /** An enabled "#define TAA" option line (the pack's default), comment after it allowed. */
    private static final Pattern TAA_DEFINE = Pattern.compile("^\\s*#define\\s+(\\w+)\\s*(?://.*)?$");

    private static int animatedDitherBlocks;
    private static boolean taaDefaultLogged;

    private ShaderPackTweaks() {
    }

    /**
     * Packs animate their dither (shadows, AO, volumetric light, reflections) only with their TAA on:
     * {@code #ifdef TAA  dither = fract(dither + frameCounter * ...);  #endif}. With TAA off (DLSS does
     * the anti-aliasing) the noise is a fixed screen pattern that DLSS can't average away and that
     * crawls against the jittered world. Turns on just those blocks: a TAA condition whose body is at
     * most four plain statements that each use frameCounter / frameTimeCounter and touch no position.
     * The pack's own jitter and TAA resolve stay off. Line count is unchanged. Vitrail, per file.
     */
    public static List<String> animateDither(List<String> lines) {
        if (!DlssConfig.enabled || !DlssConfig.disablePackTaa) {
            return lines;
        }
        List<String> result = null;
        for (int i = 0; i < lines.size(); i++) {
            Matcher define = TAA_DEFINE.matcher(lines.get(i));
            if (define.matches() && TAA_OPTIONS.contains(define.group(1))) {
                // The pack's TAA option defaults to on: off while DLSS runs, also for a pack picked in game
                // (the settings file is only written at start). The option stays in the pack's screen.
                if (result == null) {
                    result = new ArrayList<>(lines);
                }
                result.set(i, "//" + lines.get(i).stripLeading());
                if (!taaDefaultLogged) {
                    taaDefaultLogged = true;
                    IrisDlss.LOGGER.info("Iris DLSS: the shader pack's {} defaults to off while DLSS runs",
                        define.group(1));
                }
                continue;
            }
            Matcher condition = TAA_CONDITION.matcher(lines.get(i));
            if (!condition.matches()) {
                continue;
            }
            String name = condition.group(1) != null ? condition.group(1) : condition.group(2);
            if (!TAA_OPTIONS.contains(name)) {
                continue;
            }
            int statements = 0;
            boolean animatesOnly = true;
            int end = -1;
            for (int j = i + 1; j < lines.size() && j <= i + 8; j++) {
                String body = lines.get(j).trim();
                if (body.startsWith("#")) {
                    end = body.matches("#\\s*endif\\b.*") ? j : -1;
                    break;
                }
                if (body.isEmpty() || body.startsWith("//")) {
                    continue;
                }
                statements++;
                if (!FRAME_COUNTER.matcher(body).find() || body.contains("gl_Position") || body.contains("Jitter")
                    || !body.endsWith(";")) {
                    animatesOnly = false;
                }
            }
            if (end < 0 || statements == 0 || statements > 4 || !animatesOnly) {
                continue;
            }
            if (result == null) {
                result = new ArrayList<>(lines);
            }
            result.set(i, "#if 1 // iris-dlss: dither animated for DLSS (" + name + " is off)");
            if (animatedDitherBlocks++ < 3) {
                IrisDlss.LOGGER.info("Iris DLSS: animating the pack's dither without its {}: {}", name,
                    lines.get(i + 1).trim());
            }
        }
        return result != null ? result : lines;
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
            for (String line : lines) {
                int eq = line.indexOf('=');
                if (eq < 0) {
                    if (!line.isBlank()) {
                        name = line.trim();
                    }
                } else if (line.substring(0, eq).trim().equals("pack")) {
                    name = line.substring(eq + 1).trim();
                }
            }
            // A disabled pack still counts: it can be switched on in game (Vitrail's toggle key).
            return name == null || name.isEmpty() || name.equals("none") ? null : name;
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
        if (packName.isEmpty()) {
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
