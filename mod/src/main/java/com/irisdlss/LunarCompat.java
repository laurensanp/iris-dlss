package com.irisdlss;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Lunar Client fixes, applied before any mixin is: Vitrail's @Redirect of the barrier in
 * VulkanCommandEncoder.submitRenderPass is dropped when another mod (Lunar's lunar$maybeBarrier) redirects the
 * same call, which otherwise is a critical injection failure at start (same change as tools/PatchVitrail.java;
 * Vitrail only narrows the barrier after its own passes, the game's full barrier stays). And Lunar's
 * "Multi-Threaded Rendering" (decoupledPresent) is turned off in its settings file, because with it Lunar
 * presents the swapchain itself and Frame Generation cannot start. Lunar reads that file after the mixin setup.
 * config/iris-dlss.properties: lunarCompat=false turns both off.
 */
public final class LunarCompat implements IMixinConfigPlugin {

    private static final Logger LOGGER = LoggerFactory.getLogger("iris-dlss");
    private static final String VITRAIL_MIXIN = "dev.vitrail.mixin.VulkanCommandEncoderMixin";
    private static final String VITRAIL_HANDLER = "vitrail$afterPass";
    private static final String REDIRECT = "Lorg/spongepowered/asm/mixin/injection/Redirect;";
    private static final String BARRIER = "VulkanCommandEncoder;memoryBarrier(";

    private boolean done;

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return true;
    }

    /** Called once all mixin configs are prepared and before any mixin is applied. */
    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
        if (done) {
            return;
        }
        done = true;
        try {
            if (!enabled()) {
                return;
            }
            boolean lunar = isLunar();
            dropVitrailRedirect(lunar);
            if (lunar || System.getProperty("iris-dlss.lunarDir") != null) {
                turnOffThreadedPresent();
            }
        } catch (Throwable t) {
            LOGGER.warn("Iris DLSS: Lunar Client compatibility step failed", t);
        }
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    private static boolean enabled() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("iris-dlss.properties");
        Properties props = new Properties();
        if (Files.isRegularFile(file)) {
            try (Reader reader = Files.newBufferedReader(file)) {
                props.load(reader);
            } catch (IOException ignored) {
            }
        }
        return !"false".equalsIgnoreCase(props.getProperty("lunarCompat", "true").trim());
    }

    /** Lunar's own mixin configs (mixins.ichor.*), or a game dir inside .lunarclient. */
    private static boolean isLunar() throws ReflectiveOperationException {
        for (IMixinConfig config : configs()) {
            if (config.getName().startsWith("mixins.ichor")) {
                return true;
            }
        }
        return FabricLoader.getInstance().getGameDir().toAbsolutePath().toString().contains(".lunarclient");
    }

    // --- Vitrail's barrier redirect ---------------------------------------------------------------

    private static void dropVitrailRedirect(boolean lunar) throws ReflectiveOperationException {
        List<ClassNode> vitrail = new ArrayList<>();
        boolean otherRedirect = false;
        for (IMixinConfig config : configs()) {
            for (Object info : mixinInfos(config)) {
                String name = ((IMixinInfo) info).getClassName();
                for (ClassNode node : classNodes(info)) {
                    if (name.equals(VITRAIL_MIXIN)) {
                        vitrail.add(node);
                    } else if (!otherRedirect && redirectsBarrier(node, null)) {
                        otherRedirect = true;
                        LOGGER.info("Iris DLSS: {} also redirects the barrier in submitRenderPass", name);
                    }
                }
            }
        }
        boolean force = Boolean.getBoolean("iris-dlss.forceLunarCompat");
        if (vitrail.isEmpty() || !(otherRedirect || lunar || force)) {
            return;
        }
        int removed = 0;
        for (ClassNode node : vitrail) {
            removed += redirectsBarrier(node, VITRAIL_HANDLER) ? removeRedirect(node) : 0;
        }
        if (removed > 0) {
            LOGGER.info("Iris DLSS: dropped Vitrail's barrier @Redirect in VulkanCommandEncoder (Lunar Client "
                + "redirects the same call; Vitrail credits: github.com/avpbynf/Vitrail-Shaders, LGPL-3.0)");
        }
    }

    /** True when a method of the mixin (only {@code handler} if given) has a @Redirect at the barrier call. */
    private static boolean redirectsBarrier(ClassNode node, String handler) {
        for (MethodNode m : node.methods) {
            if (handler != null && !m.name.equals(handler)) {
                continue;
            }
            for (AnnotationNode a : annotations(m)) {
                if (a.desc.equals(REDIRECT)) {
                    String values = text(a.values);
                    if (values.contains("submitRenderPass") && values.contains(BARRIER)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Annotation values as text, nested annotations (@At) included. */
    private static String text(Object value) {
        if (value instanceof AnnotationNode a) {
            return a.desc + text(a.values);
        }
        if (value instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            for (Object o : list) {
                sb.append(text(o)).append(' ');
            }
            return sb.toString();
        }
        return String.valueOf(value);
    }

    private static int removeRedirect(ClassNode node) {
        int removed = 0;
        for (MethodNode m : node.methods) {
            if (!m.name.equals(VITRAIL_HANDLER)) {
                continue;
            }
            for (List<AnnotationNode> list : Arrays.asList(m.visibleAnnotations, m.invisibleAnnotations)) {
                if (list != null && list.removeIf(a -> a.desc.equals(REDIRECT))) {
                    removed++;
                }
            }
        }
        return removed;
    }

    private static List<AnnotationNode> annotations(MethodNode m) {
        List<AnnotationNode> all = new ArrayList<>();
        if (m.visibleAnnotations != null) {
            all.addAll(m.visibleAnnotations);
        }
        if (m.invisibleAnnotations != null) {
            all.addAll(m.invisibleAnnotations);
        }
        return all;
    }

    /** All mixin configs Mixin has loaded: MixinTransformer.processor → MixinProcessor.configs + pendingConfigs. */
    private static List<IMixinConfig> configs() throws ReflectiveOperationException {
        List<IMixinConfig> configs = new ArrayList<>();
        Object transformer = MixinEnvironment.getCurrentEnvironment().getActiveTransformer();
        Object processor = transformer != null ? field(transformer, "processor") : null;
        for (String name : new String[] {"configs", "pendingConfigs"}) {
            if (processor != null && field(processor, name) instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof IMixinConfig c && !configs.contains(c)) {
                        configs.add(c);
                    }
                }
            }
        }
        return configs;
    }

    /** MixinConfig.pendingMixins + mixins (Mixin internals, same in Fabric's and Lunar's Mixin 0.8.x). */
    private static List<Object> mixinInfos(Object mixinConfig) throws ReflectiveOperationException {
        List<Object> infos = new ArrayList<>();
        for (String field : new String[] {"pendingMixins", "mixins"}) {
            Object list = field(mixinConfig, field);
            if (list instanceof List<?> l) {
                infos.addAll(l);
            }
        }
        return infos;
    }

    /** The class nodes Mixin applies from: MixinInfo.state / pendingState → State.classNode. */
    private static List<ClassNode> classNodes(Object mixinInfo) throws ReflectiveOperationException {
        List<ClassNode> nodes = new ArrayList<>();
        for (String field : new String[] {"pendingState", "state"}) {
            Object state = field(mixinInfo, field);
            if (state != null && field(state, "classNode") instanceof ClassNode node && !nodes.contains(node)) {
                nodes.add(node);
            }
        }
        return nodes;
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        for (Class<?> c = owner.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(owner);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    // --- Lunar's Multi-Threaded Rendering -----------------------------------------------------------

    /** Sets decoupledPresent=false in the active Lunar settings profile (~/.lunarclient/settings/game/<profile>). */
    private static void turnOffThreadedPresent() throws IOException {
        String dir = System.getProperty("iris-dlss.lunarDir");
        Path game = (dir != null ? Path.of(dir) : Path.of(System.getProperty("user.home"), ".lunarclient"))
            .resolve("settings").resolve("game");
        String profile = activeProfile(game);
        if (profile == null) {
            return;
        }
        Path file = game.resolve(profile).resolve("performance.json");
        JsonObject json = Files.isRegularFile(file) ? read(file) : new JsonObject();
        if (json == null) {
            return;
        }
        JsonElement current = json.get("decoupledPresent");
        if (current != null && current.isJsonPrimitive() && !current.getAsBoolean()) {
            return;
        }
        if (!json.has("version")) {
            JsonElement version = otherVersion(game);
            if (version != null) {
                json.add("version", version);
            }
        }
        json.addProperty("decoupledPresent", false);
        Gson gson = new GsonBuilder().disableHtmlEscaping().create();
        Files.createDirectories(file.getParent());
        Files.writeString(file, gson.toJson(json), StandardCharsets.UTF_8);
        LOGGER.info("Iris DLSS: turned off Lunar's Multi-Threaded Rendering in profile '{}' (needed for Frame "
            + "Generation; lunarCompat=false in config/iris-dlss.properties keeps Lunar's setting)", profile);
    }

    private static String activeProfile(Path game) {
        JsonElement profiles = Files.isRegularFile(game.resolve("profile_manager.json"))
            ? readAny(game.resolve("profile_manager.json")) : null;
        if (profiles instanceof JsonArray array) {
            for (JsonElement e : array) {
                if (e instanceof JsonObject p && p.has("active") && p.get("active").getAsBoolean() && p.has("name")) {
                    return p.get("name").getAsString();
                }
            }
        }
        return Files.isDirectory(game.resolve("Default")) ? "Default" : null;
    }

    /** Lunar writes a "version" next to its settings; a new file takes the one of another profile. */
    private static JsonElement otherVersion(Path game) throws IOException {
        try (var dirs = Files.list(game)) {
            for (Path p : (Iterable<Path>) dirs::iterator) {
                JsonObject other = Files.isRegularFile(p.resolve("performance.json")) ? read(p.resolve("performance.json")) : null;
                if (other != null && other.has("version")) {
                    return other.get("version");
                }
            }
        }
        return null;
    }

    private static JsonObject read(Path file) {
        return readAny(file) instanceof JsonObject o ? o : null;
    }

    private static JsonElement readAny(Path file) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Iris DLSS: could not read {}", file, e);
            return null;
        }
    }
}
