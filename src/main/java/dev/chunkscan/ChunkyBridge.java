package dev.chunkscan;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * Reads Chunky's current selection (set via /chunky world, center, shape, radius) through reflection,
 * so ChunkScan has no build or runtime dependency on Chunky.
 * Path: ChunkyBukkit#getChunky() -> Chunky#getSelection() -> Selection.Builder#build() -> Selection.
 */
final class ChunkyBridge {

    static final class Selection {
        final String world, shape;
        final double centerX, centerZ, radiusX, radiusZ;

        Selection(String world, String shape, double centerX, double centerZ, double radiusX, double radiusZ) {
            this.world = world;
            this.shape = shape;
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.radiusX = radiusX;
            this.radiusZ = radiusZ;
        }
    }

    private ChunkyBridge() {}

    /** @return the selection, or null if Chunky is missing/disabled or its internals changed. */
    static Selection read() {
        try {
            Plugin plugin = Bukkit.getPluginManager().getPlugin("Chunky");
            if (plugin == null || !plugin.isEnabled()) return null;
            Object chunky = call(plugin, "getChunky");
            Object builder = call(chunky, "getSelection");
            Object sel = call(builder, "build");
            ClassLoader cl = sel.getClass().getClassLoader();
            Object world = call(sel, "world");
            Method getName = Class.forName("org.popcraft.chunky.platform.World", true, cl).getMethod("getName");
            return new Selection(
                    (String) getName.invoke(world),
                    String.valueOf(call(sel, "shape")),
                    ((Number) call(sel, "centerX")).doubleValue(),
                    ((Number) call(sel, "centerZ")).doubleValue(),
                    ((Number) call(sel, "radiusX")).doubleValue(),
                    ((Number) call(sel, "radiusZ")).doubleValue());
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Worlds in which Chunky is currently generating (keys of Chunky#getGenerationTasks()).
     * @return the world names, or null if Chunky is missing/disabled or its internals changed
     */
    static List<String> runningWorlds() {
        try {
            Object chunky = chunky();
            if (chunky == null) return null;
            Map<?, ?> tasks = (Map<?, ?>) call(chunky, "getGenerationTasks");
            List<String> worlds = new ArrayList<>();
            for (Object k : tasks.keySet()) worlds.add(String.valueOf(k));
            return worlds;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Pauses the task in a world (Chunky saves its progress). Main thread only. */
    static boolean pause(String world) {
        return api("pauseTask", world);
    }

    /** Continues a paused task in a world. Main thread only. */
    static boolean resume(String world) {
        return api("continueTask", world);
    }

    /** Calls a ChunkyAPI method (public API, registered by Chunky as a service). */
    private static boolean api(String method, String world) {
        try {
            Object chunky = chunky();
            if (chunky == null) return false;
            Object api = call(chunky, "getApi");
            Class<?> iface = Class.forName("org.popcraft.chunky.api.ChunkyAPI", true, api.getClass().getClassLoader());
            return (Boolean) iface.getMethod(method, String.class).invoke(api, world);
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object chunky() throws ReflectiveOperationException {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("Chunky");
        if (plugin == null || !plugin.isEnabled()) return null;
        return call(plugin, "getChunky");
    }

    private static Object call(Object target, String method) throws ReflectiveOperationException {
        Method m = target.getClass().getMethod(method);
        m.setAccessible(true);
        return m.invoke(target);
    }
}
