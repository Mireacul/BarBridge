package com.mireacul.barbridge;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BarBridge - Mireacul UHC
 *
 * Self-contained boss bar plugin and API for 1.8.x using NMS Wither entities.
 *
 * <h2>Usage from another plugin</h2>
 * <pre>
 *   // plugin.yml: depend: [BarBridge]
 *   BarBridge api = BarBridge.getInstance();
 *   api.setBar(player, "&amp;cYou took damage!");
 *   api.setTimedBar(player, 30, "&amp;eFight starts in 30s");
 *   api.updateMessage(player, "&amp;aResolved");
 *   api.removeBar(player);
 * </pre>
 *
 * <h2>Color codes</h2>
 * All message strings support {@code &amp;}-style color codes; they are translated
 * automatically before display.
 *
 * <h2>Threading</h2>
 * The internal bar map uses {@link java.util.concurrent.ConcurrentHashMap},
 * so concurrent reads and writes from multiple threads will not corrupt state.
 * The typical caller invokes the API from the main server thread (commands,
 * Bukkit events, sync {@code BukkitRunnable}s), and that remains the
 * recommended pattern since NMS packet sending is most commonly done there.
 *
 * <h2>Commands</h2>
 *   /bar set &lt;player|*&gt; &lt;message&gt;<br>
 *   /bar timed &lt;player|*&gt; &lt;seconds&gt; &lt;message&gt;<br>
 *   /bar msg &lt;player|*&gt; &lt;message&gt;<br>
 *   /bar health &lt;player|*&gt; &lt;percent&gt; [message]<br>
 *   /bar remove &lt;player|*&gt;
 */
public class BarBridge extends JavaPlugin implements Listener {

    private static BarBridge instance;

    private final Map<UUID, WitherBar> activeBars = new ConcurrentHashMap<>();
    private ProtocolLibHook protocolHook; // null if ProtocolLib isn't installed

    /**
     * @return the active BarBridge instance, or null if the plugin isn't enabled.
     */
    public static BarBridge getInstance() {
        return instance;
    }

    @Override
    public void onEnable() {
        if (!WitherBar.init()) {
            getLogger().severe("Failed to hook NMS - BarBridge disabled.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        instance = this;
        Bukkit.getPluginManager().registerEvents(this, this);

        if (Bukkit.getPluginManager().getPlugin("ProtocolLib") != null) {
            try {
                protocolHook = new ProtocolLibHook(this, activeBars);
                protocolHook.register();
                getLogger().info("ProtocolLib detected, hit and sound suppression enabled.");
            } catch (Throwable t) {
                getLogger().warning("ProtocolLib hook failed to load: " + t.getMessage());
                protocolHook = null;
            }
        } else {
            getLogger().info("ProtocolLib not found, running without hit/sound suppression.");
        }

        new BukkitRunnable() {
            @Override
            public void run() {
                for (WitherBar bar : activeBars.values()) {
                    if (bar.isVisible() && bar.getPlayer().isOnline()) {
                        bar.tick();
                    }
                }
            }
        }.runTaskTimer(this, 1L, 1L);

        getLogger().info("BarBridge enabled.");
    }

    @Override
    public void onDisable() {
        for (WitherBar bar : activeBars.values()) {
            bar.hide();
        }
        activeBars.clear();
        if (protocolHook != null) {
            try {
                protocolHook.unregister();
            } catch (Throwable ignored) {}
            protocolHook = null;
        }
        instance = null;
        getLogger().info("BarBridge disabled.");
    }

    // ==========================================
    // PUBLIC API
    // ==========================================

    /**
     * Set a persistent boss bar at 100% fill.
     * Replaces any existing bar for this player.
     *
     * @param player  the recipient (must be online)
     * @param message text to display; supports {@code &}-style color codes
     */
    public void setBar(Player player, String message) {
        setBar(player, message, 100f);
    }

    /**
     * Set a persistent boss bar at a specific fill percentage.
     * Replaces any existing bar for this player.
     *
     * @param player  the recipient (must be online)
     * @param message text to display; supports {@code &}-style color codes
     * @param percent fill percentage 0-100 (clamped)
     */
    public void setBar(Player player, String message, float percent) {
        if (player == null || !player.isOnline()) return;
        percent = clamp(percent);
        removeBar(player);
        WitherBar bar = new WitherBar(player, color(message), percent);
        activeBars.put(player.getUniqueId(), bar);
        bar.show();
    }

    /**
     * Set a boss bar that drains from 100% to 0% over the given duration,
     * then auto-removes itself. Replaces any existing bar.
     *
     * @param player  the recipient (must be online)
     * @param seconds total drain time, must be &gt; 0
     * @param message text to display; supports {@code &}-style color codes
     */
    public void setTimedBar(Player player, int seconds, String message) {
        if (player == null || !player.isOnline() || seconds <= 0) return;
        setBar(player, message, 100f);
        startDrain(player, seconds);
    }

    /**
     * Update only the message text of the player's bar. Health is preserved.
     * If no bar is active, creates one at 100%.
     */
    public void updateMessage(Player player, String message) {
        if (player == null) return;
        WitherBar bar = activeBars.get(player.getUniqueId());
        if (bar != null && bar.isVisible()) {
            bar.updateMessage(color(message));
        } else if (player.isOnline()) {
            setBar(player, message, 100f);
        }
    }

    /**
     * Update only the fill percentage of the player's bar. Message is preserved.
     * Has no effect if there is no active bar.
     */
    public void updateHealth(Player player, float percent) {
        if (player == null) return;
        WitherBar bar = activeBars.get(player.getUniqueId());
        if (bar != null && bar.isVisible()) {
            bar.updateHealth(clamp(percent));
        }
    }

    /**
     * Update both message and fill percentage. If no bar is active, creates one.
     */
    public void updateAll(Player player, String message, float percent) {
        if (player == null) return;
        percent = clamp(percent);
        WitherBar bar = activeBars.get(player.getUniqueId());
        if (bar != null && bar.isVisible()) {
            bar.updateAll(color(message), percent);
        } else if (player.isOnline()) {
            setBar(player, message, percent);
        }
    }

    /**
     * Remove the player's bar if one exists. Safe to call when no bar is active.
     */
    public void removeBar(Player player) {
        if (player == null) return;
        WitherBar bar = activeBars.remove(player.getUniqueId());
        if (bar != null) {
            bar.hide();
        }
    }

    /**
     * @return true if this player currently has a visible bar
     */
    public boolean hasBar(Player player) {
        if (player == null) return false;
        WitherBar bar = activeBars.get(player.getUniqueId());
        return bar != null && bar.isVisible();
    }

    /**
     * @return current bar message for this player, or null if no bar is active
     */
    public String getMessage(Player player) {
        if (player == null) return null;
        WitherBar bar = activeBars.get(player.getUniqueId());
        return bar != null ? bar.getMessage() : null;
    }

    /**
     * @return current fill percentage 0-100 for this player, or -1 if no bar
     */
    public float getHealth(Player player) {
        if (player == null) return -1f;
        WitherBar bar = activeBars.get(player.getUniqueId());
        return bar != null ? bar.getHealthPercent() : -1f;
    }

    // ==========================================
    // Commands (delegate to the API)
    // ==========================================

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("bar")) return false;

        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage:");
            sender.sendMessage(ChatColor.YELLOW + " /bar set <player|*> <message>");
            sender.sendMessage(ChatColor.YELLOW + " /bar timed <player|*> <seconds> <message>");
            sender.sendMessage(ChatColor.YELLOW + " /bar msg <player|*> <message>");
            sender.sendMessage(ChatColor.YELLOW + " /bar health <player|*> <percent> [message]");
            sender.sendMessage(ChatColor.YELLOW + " /bar remove <player|*>");
            return true;
        }

        String sub = args[0].toLowerCase();
        String target = args[1];

        switch (sub) {
            case "set":      return handleSet(sender, target, args);
            case "timed":    return handleTimed(sender, target, args);
            case "msg":
            case "message":
            case "update":   return handleMsg(sender, target, args);
            case "health":   return handleHealth(sender, target, args);
            case "remove":
            case "clear":
                for (Player p : getTargets(target)) removeBar(p);
                return true;
            default:
                sender.sendMessage(ChatColor.RED + "Unknown subcommand: " + sub);
                return true;
        }
    }

    private boolean handleSet(CommandSender sender, String target, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "/bar set <player|*> <message>");
            return true;
        }
        String message = buildMessage(args, 2);
        for (Player p : getTargets(target)) setBar(p, message, 100f);
        return true;
    }

    private boolean handleTimed(CommandSender sender, String target, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(ChatColor.RED + "/bar timed <player|*> <seconds> <message>");
            return true;
        }
        int seconds;
        try {
            seconds = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "Seconds must be a number.");
            return true;
        }
        String message = buildMessage(args, 3);
        for (Player p : getTargets(target)) setTimedBar(p, seconds, message);
        return true;
    }

    private boolean handleMsg(CommandSender sender, String target, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "/bar msg <player|*> <message>");
            return true;
        }
        String message = buildMessage(args, 2);
        for (Player p : getTargets(target)) updateMessage(p, message);
        return true;
    }

    private boolean handleHealth(CommandSender sender, String target, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "/bar health <player|*> <percent> [message]");
            return true;
        }
        float percent;
        try {
            percent = Float.parseFloat(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "Percent must be a number.");
            return true;
        }
        String message = args.length >= 4 ? buildMessage(args, 3) : null;

        for (Player p : getTargets(target)) {
            if (message != null) updateAll(p, message, percent);
            else                 updateHealth(p, percent);
        }
        return true;
    }

    // ==========================================
    // Internals
    // ==========================================

    private void startDrain(Player player, int totalSeconds) {
        UUID uuid = player.getUniqueId();
        final float drainPerSecond = 100.0f / totalSeconds;

        new BukkitRunnable() {
            int elapsed = 0;
            @Override
            public void run() {
                WitherBar bar = activeBars.get(uuid);
                if (bar == null || !bar.isVisible()) {
                    cancel();
                    return;
                }
                elapsed++;
                float newPercent = 100.0f - (drainPerSecond * elapsed);
                if (newPercent <= 0) {
                    Player p = Bukkit.getPlayer(uuid);
                    if (p != null) removeBar(p);
                    cancel();
                    return;
                }
                bar.updateHealth(newPercent);
            }
        }.runTaskTimer(this, 20L, 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        removeBar(event.getPlayer());
    }

    private Player[] getTargets(String target) {
        if (target.equals("*") || target.equalsIgnoreCase("all")) {
            java.util.List<Player> list = new java.util.ArrayList<>();
            for (org.bukkit.World world : Bukkit.getWorlds()) {
                list.addAll(world.getPlayers());
            }
            return list.toArray(new Player[0]);
        } else {
            Player p = Bukkit.getPlayer(target);
            if (p != null && p.isOnline()) return new Player[]{p};
            return new Player[0];
        }
    }

    private String buildMessage(String[] args, int startIndex) {
        StringBuilder sb = new StringBuilder();
        for (int i = startIndex; i < args.length; i++) {
            if (i > startIndex) sb.append(" ");
            sb.append(args[i]);
        }
        return sb.toString();
    }

    private static String color(String s) {
        return s == null ? "" : ChatColor.translateAlternateColorCodes('&', s);
    }

    private static float clamp(float p) {
        if (p < 0) return 0;
        if (p > 100) return 100;
        return p;
    }
}
