package dev.chunkscan;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

/**
 * Keeps Chunky from generating while a scan runs: suspends plugins that would resume Chunky on their own
 * (e.g. ChunkyAutoPause), pauses running Chunky tasks, and undoes both afterwards.
 * All methods must run on the main thread.
 */
final class ScanGuard {

    private static final String P = "§6[ChunkScan] §r";

    private final List<String> pausedWorlds = new ArrayList<>();
    private final List<Plugin> suspendedPlugins = new ArrayList<>();
    private boolean ended;

    private ScanGuard() {}

    static ScanGuard begin(CommandSender sender, boolean pauseChunky, List<String> suspendNames) {
        ScanGuard g = new ScanGuard();
        PluginManager pm = Bukkit.getPluginManager();

        // first stop the auto-resumers, otherwise they could continue Chunky right after we pause it
        for (String name : suspendNames) {
            Plugin p = pm.getPlugin(name);
            if (p == null || !p.isEnabled()) continue;
            try {
                pm.disablePlugin(p);
                g.suspendedPlugins.add(p);
                sender.sendMessage(P + "Disabled " + name + " for the duration of the scan.");
            } catch (Throwable t) {
                sender.sendMessage(P + "§cCould not disable " + name + ": " + t);
            }
        }

        List<String> running = ChunkyBridge.runningWorlds();
        if (running == null) return g; // Chunky not installed
        if (running.isEmpty()) {
            sender.sendMessage(P + "Chunky is not generating.");
        } else if (!pauseChunky) {
            sender.sendMessage(P + "§eChunky is generating in " + String.join(", ", running)
                    + " (pause-chunky is off). The scan may miss chunks generated meanwhile.");
        } else {
            for (String world : running) {
                if (ChunkyBridge.pause(world)) {
                    g.pausedWorlds.add(world);
                    sender.sendMessage(P + "Paused Chunky in " + world + "; it continues after the scan.");
                } else {
                    sender.sendMessage(P + "§cCould not pause Chunky in " + world + ".");
                }
            }
        }
        return g;
    }

    /** Resumes what was paused and re-enables what was disabled. Safe to call more than once. */
    void end(CommandSender sender) {
        if (ended) return;
        ended = true;
        for (String world : pausedWorlds) {
            boolean ok = ChunkyBridge.resume(world);
            sender.sendMessage(P + (ok ? "Resumed Chunky in " + world + "."
                    : "§cCould not resume Chunky in " + world + "; use /chunky continue."));
        }
        PluginManager pm = Bukkit.getPluginManager();
        for (Plugin p : suspendedPlugins) {
            try {
                pm.enablePlugin(p);
                sender.sendMessage(P + "Re-enabled " + p.getName() + ".");
            } catch (Throwable t) {
                sender.sendMessage(P + "§cCould not re-enable " + p.getName() + " (" + t + "); restart the server.");
            }
        }
    }
}
