package com.mireacul.barbridge;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;

import java.util.Map;
import java.util.UUID;

/**
 * ProtocolLibHook - Optional packet-level edge case handling.
 *
 * Isolated from BarBridge.java so the JVM only loads this class (and therefore
 * resolves the ProtocolLib classes it imports) when BarBridge actually
 * instantiates it. If ProtocolLib isn't installed, BarBridge never references
 * this class and there's no NoClassDefFoundError at startup.
 *
 * Handles two edge cases:
 *   1. Cancels USE_ENTITY packets aimed at the fake wither (would only fire
 *      if a player somehow targeted Y+256, but harmless to guard against).
 *   2. Suppresses "mob.wither.*" sound effects for players with an active bar.
 */
public class ProtocolLibHook {

    private final BarBridge plugin;
    private final Map<UUID, WitherBar> activeBars;
    private ProtocolManager protocolManager;

    public ProtocolLibHook(BarBridge plugin, Map<UUID, WitherBar> activeBars) {
        this.plugin = plugin;
        this.activeBars = activeBars;
    }

    public void register() {
        protocolManager = ProtocolLibrary.getProtocolManager();
        registerHitProtection();
        registerSoundSuppression();
    }

    public void unregister() {
        if (protocolManager != null) {
            protocolManager.removePacketListeners(plugin);
            protocolManager = null;
        }
    }

    /**
     * Cancel any player interaction with the fake wither entity.
     */
    private void registerHitProtection() {
        protocolManager.addPacketListener(new PacketAdapter(plugin, ListenerPriority.NORMAL,
                PacketType.Play.Client.USE_ENTITY) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                int targetId = event.getPacket().getIntegers().read(0);
                WitherBar bar = activeBars.get(event.getPlayer().getUniqueId());
                if (bar != null && bar.getEntityId() == targetId) {
                    event.setCancelled(true);
                }
            }
        });
    }

    /**
     * Suppress all Wither sounds for players with an active bar.
     */
    private void registerSoundSuppression() {
        protocolManager.addPacketListener(new PacketAdapter(plugin, ListenerPriority.NORMAL,
                PacketType.Play.Server.NAMED_SOUND_EFFECT) {
            @Override
            public void onPacketSending(PacketEvent event) {
                WitherBar bar = activeBars.get(event.getPlayer().getUniqueId());
                if (bar != null && bar.isVisible()) {
                    String sound = event.getPacket().getStrings().read(0);
                    if (sound.startsWith("mob.wither")) {
                        event.setCancelled(true);
                    }
                }
            }
        });
    }
}
