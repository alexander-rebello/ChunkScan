package dev.chunkscan;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Keeps Chunky from generating while a scan runs: pauses running Chunky tasks and, while the scan runs,
 * pauses them again whenever something (e.g. ChunkyAutoPause's idle watcher) continues them.
 * Afterwards every task paused this way is continued. Other plugins are never disabled: disabling a plugin
 * at runtime closes its jar on Paper, and re-enabling it leaves a broken class loader behind.
 * All methods must run on the main thread.
 */
final class ScanGuard {

    private static final String P = "§6[ChunkScan] §r";

    private final Set<String> pausedWorlds = new LinkedHashSet<>();
    private BukkitTask watcher;
    private boolean ended;

    private ScanGuard() {}

    static ScanGuard begin(Plugin plugin, CommandSender sender, boolean pauseChunky) {
        ScanGuard g = new ScanGuard();
        List<String> running = ChunkyBridge.runningWorlds();
        if (running == null) return g; // Chunky not installed

        if (!pauseChunky) {
            if (!running.isEmpty()) {
                sender.sendMessage(P + "§eChunky is generating in " + String.join(", ", running)
                        + " (pause-chunky is off). The scan may miss chunks generated meanwhile.");
            }
            return g;
        }

        if (running.isEmpty()) {
            sender.sendMessage(P + "Chunky is not generating; it is kept paused until the scan is done.");
        }
        for (String world : running) {
            if (ChunkyBridge.pause(world)) {
                g.pausedWorlds.add(world);
                sender.sendMessage(P + "Paused Chunky in " + world + "; it continues after the scan.");
            } else {
                sender.sendMessage(P + "§cCould not pause Chunky in " + world + ".");
            }
        }

        // other plugins may continue Chunky during the scan; pause it again within a second
        g.watcher = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            List<String> now = ChunkyBridge.runningWorlds();
            if (now == null) return;
            for (String world : now) {
                if (ChunkyBridge.pause(world) && g.pausedWorlds.add(world)) {
                    sender.sendMessage(P + "Chunky was continued in " + world
                            + " during the scan; paused it again until the scan is done.");
                }
            }
        }, 20L, 20L);
        return g;
    }

    /** Stops watching and continues every task paused during the scan. Safe to call more than once. */
    void end(CommandSender sender) {
        if (ended) return;
        ended = true;
        if (watcher != null) watcher.cancel();
        for (String world : pausedWorlds) {
            boolean ok = ChunkyBridge.resume(world);
            sender.sendMessage(P + (ok ? "Continued Chunky in " + world + "."
                    : "§cCould not continue Chunky in " + world + "; use /chunky continue."));
        }
    }
}
