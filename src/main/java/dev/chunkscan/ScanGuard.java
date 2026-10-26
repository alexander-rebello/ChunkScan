package dev.chunkscan;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

/**
 * Keeps Chunky from generating while a scan runs: pauses running Chunky tasks and continues them afterwards,
 * and warns about plugins that continue Chunky on their own. Other plugins are never disabled: disabling a
 * plugin at runtime closes its jar on Paper and re-enabling it leaves a broken class loader behind.
 * All methods must run on the main thread.
 */
final class ScanGuard {

    private static final String P = "§6[ChunkScan] §r";

    private final List<String> pausedWorlds = new ArrayList<>();
    private boolean ended;

    private ScanGuard() {}

    static ScanGuard begin(CommandSender sender, boolean pauseChunky, List<String> autoContinuePlugins) {
        ScanGuard g = new ScanGuard();
        for (String name : autoContinuePlugins) {
            Plugin p = Bukkit.getPluginManager().getPlugin(name);
            if (p != null && p.isEnabled()) {
                sender.sendMessage(P + "§eWarning: " + name + " is installed and may continue Chunky during the scan,"
                        + " so region files can change while they are read. For an exact result, remove it temporarily"
                        + " (stop the server, move its jar out of plugins/, start again).");
            }
        }

        List<String> running = ChunkyBridge.runningWorlds();
        if (running == null || running.isEmpty()) return g; // Chunky not installed or idle
        if (!pauseChunky) {
            sender.sendMessage(P + "§eChunky is generating in " + String.join(", ", running)
                    + " (pause-chunky is off). The scan may miss chunks generated meanwhile.");
            return g;
        }
        for (String world : running) {
            if (ChunkyBridge.pause(world)) {
                g.pausedWorlds.add(world);
                sender.sendMessage(P + "Paused Chunky in " + world + "; it continues after the scan.");
            } else {
                sender.sendMessage(P + "§cCould not pause Chunky in " + world + ".");
            }
        }
        return g;
    }

    /** Continues the tasks paused at the start. Safe to call more than once. */
    void end(CommandSender sender) {
        if (ended) return;
        ended = true;
        for (String world : pausedWorlds) {
            boolean ok = ChunkyBridge.resume(world);
            sender.sendMessage(P + (ok ? "Continued Chunky in " + world + "."
                    : "§cCould not continue Chunky in " + world + "; use /chunky continue."));
        }
    }
}
