package com.mireacul.barbridge;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;

/**
 * WitherBar: Fake Wither entity boss bar for 1.8.x
 *
 * Spawns an invisible Wither 256 blocks above the player, well above the
 * 1.8 build height limit, so smoke particles render in dead sky and are
 * invisible from any normal gameplay position.
 * Hitbox and sound (edge cases) optionally handled by ProtocolLib in BarBridge.
 * All via NMS packets. No entity actually exists server-side.
 */
public class WitherBar {

    private static final float MAX_HEALTH = 300.0f;
    private static final int WITHER_TYPE_ID = 64;

    // NMS cached reflection
    private static String nmsVersion;
    private static Class<?> packetSpawnClass;
    private static Class<?> packetDestroyClass;
    private static Class<?> packetMetadataClass;
    private static Class<?> packetTeleportClass;
    private static Class<?> dataWatcherClass;
    private static Class<?> craftPlayerClass;
    private static Method getHandleMethod;
    private static Field playerConnectionField;
    private static Method sendPacketMethod;
    private static Class<?> packetClass;
    private static boolean initialized = false;

    // Instance fields
    private final Player player;
    private final int entityId;
    private final UUID entityUUID;
    private String message;
    private float healthPercent; // 0-100
    private boolean visible;
    private int lastChunkX;
    private int lastChunkZ;
    private double lastX, lastZ;

    public WitherBar(Player player, String message, float healthPercent) {
        this.player = player;
        this.entityId = (int) (Math.random() * Integer.MAX_VALUE);
        this.entityUUID = UUID.randomUUID();
        this.message = message;
        this.healthPercent = healthPercent;
        this.visible = false;
    }

    /**
     * Initialize NMS reflection. Call once on plugin enable.
     */
    public static boolean init() {
        try {
            nmsVersion = org.bukkit.Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3];

            packetSpawnClass = getNMSClass("PacketPlayOutSpawnEntityLiving");
            packetDestroyClass = getNMSClass("PacketPlayOutEntityDestroy");
            packetMetadataClass = getNMSClass("PacketPlayOutEntityMetadata");
            packetTeleportClass = getNMSClass("PacketPlayOutEntityTeleport");
            dataWatcherClass = getNMSClass("DataWatcher");
            packetClass = getNMSClass("Packet");

            craftPlayerClass = Class.forName("org.bukkit.craftbukkit." + nmsVersion + ".entity.CraftPlayer");
            getHandleMethod = craftPlayerClass.getMethod("getHandle");

            Class<?> entityPlayerClass = getNMSClass("EntityPlayer");
            playerConnectionField = entityPlayerClass.getField("playerConnection");
            sendPacketMethod = playerConnectionField.getType().getMethod("sendPacket", packetClass);

            initialized = true;
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public void show() {
        if (!initialized || visible) return;
        try {
            Location loc = player.getLocation();
            lastChunkX = loc.getBlockX() >> 4;
            lastChunkZ = loc.getBlockZ() >> 4;
            lastX = loc.getX();
            lastZ = loc.getZ();

            Location barLoc = getBarLocation();
            Object dataWatcher = buildDataWatcher();
            Object spawnPacket = buildSpawnPacket(barLoc, dataWatcher);
            sendPacket(player, spawnPacket);
            visible = true;
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void hide() {
        if (!initialized || !visible) return;
        try {
            Constructor<?> ctor = packetDestroyClass.getConstructor(int[].class);
            Object destroyPacket = ctor.newInstance((Object) new int[]{entityId});
            sendPacket(player, destroyPacket);
            visible = false;
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Called every tick. Only updates when player moves horizontally.
     * Y changes (jumping, falling) don't matter since Wither is always Y+256.
     * Respawns on chunk boundary crossings.
     */
    public void tick() {
        if (!initialized || !visible) return;

        Location loc = player.getLocation();

        // Check chunk boundary
        int cx = loc.getBlockX() >> 4;
        int cz = loc.getBlockZ() >> 4;
        if (cx != lastChunkX || cz != lastChunkZ) {
            lastChunkX = cx;
            lastChunkZ = cz;
            lastX = loc.getX();
            lastZ = loc.getZ();
            respawn();
            return;
        }

        // Only teleport on meaningful horizontal movement
        double dx = loc.getX() - lastX;
        double dz = loc.getZ() - lastZ;
        if (dx * dx + dz * dz > 0.04) {
            lastX = loc.getX();
            lastZ = loc.getZ();
            teleport();
        }
    }

    /**
     * Destroy and respawn at the player's current position.
     */
    public void respawn() {
        if (!initialized || !visible) return;
        try {
            Constructor<?> ctor = packetDestroyClass.getConstructor(int[].class);
            Object destroyPacket = ctor.newInstance((Object) new int[]{entityId});
            sendPacket(player, destroyPacket);

            Location loc = getBarLocation();
            Object dataWatcher = buildDataWatcher();
            Object spawnPacket = buildSpawnPacket(loc, dataWatcher);
            sendPacket(player, spawnPacket);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Teleport the existing Wither above the player.
     */
    public void teleport() {
        if (!initialized || !visible) return;
        try {
            Location loc = getBarLocation();
            Object packet = packetTeleportClass.newInstance();
            setField(packet, "a", entityId);
            setField(packet, "b", toFixedPoint(loc.getX()));
            setField(packet, "c", toFixedPoint(loc.getY()));
            setField(packet, "d", toFixedPoint(loc.getZ()));
            setField(packet, "e", (byte) 0);
            setField(packet, "f", (byte) 0);
            setField(packet, "g", false);
            sendPacket(player, packet);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void updateMessage(String message) {
        this.message = message;
        if (visible) sendMetadata();
    }

    public void updateHealth(float percent) {
        this.healthPercent = Math.max(0, Math.min(100, percent));
        if (visible) sendMetadata();
    }

    public void updateAll(String message, float percent) {
        this.message = message;
        this.healthPercent = Math.max(0, Math.min(100, percent));
        if (visible) sendMetadata();
    }

    public boolean isVisible() {
        return visible;
    }

    public String getMessage() {
        return message;
    }

    public float getHealthPercent() {
        return healthPercent;
    }

    public Player getPlayer() {
        return player;
    }

    public int getEntityId() {
        return entityId;
    }

    // ==========================================
    // NMS Packet Building
    // ==========================================

    /**
     * 256 blocks directly above the player.
     * Smoke is invisible from gameplay range, and the entity stays in the
     * same chunk as the player so it remains client-loaded.
     */
    private Location getBarLocation() {
        Location loc = player.getLocation().clone();
        // Push the Wither 256 blocks straight up - above build height
        loc.setY(loc.getY() + 256);
        return loc;
    }

    private Object buildDataWatcher() throws Exception {
        Constructor<?> dwCtor = dataWatcherClass.getConstructor(getNMSClass("Entity"));
        Object dw = dwCtor.newInstance((Object) null);
        Method watchMethod = dataWatcherClass.getMethod("a", int.class, Object.class);

        float health = (healthPercent / 100.0f) * MAX_HEALTH;
        if (health <= 0) health = 1;

        // FIXED: 32 is the correct byte flag for Invisibility. (1 means "On Fire")
        watchMethod.invoke(dw, 0, (byte) 32);

        // Custom name
        watchMethod.invoke(dw, 2, message);
        // Custom name visible
        watchMethod.invoke(dw, 3, (byte) 1);
        // Health
        watchMethod.invoke(dw, 6, health);

        // Suppress potion swirl particles
        watchMethod.invoke(dw, 7, 0);
        watchMethod.invoke(dw, 8, (byte) 0);

        // Wither target IDs (0 = no target, prevents skulls)
        watchMethod.invoke(dw, 17, 0);
        watchMethod.invoke(dw, 18, 0);
        watchMethod.invoke(dw, 19, 0);

        // FIXED: Set Invulnerability ticks to 0.
        // 880 forces the Wither into its spawning phase, causing massive black smoke.
        watchMethod.invoke(dw, 20, 0);

        return dw;
    }

    private Object buildSpawnPacket(Location loc, Object dataWatcher) throws Exception {
        Object packet = packetSpawnClass.newInstance();

        setField(packet, "a", entityId);
        setField(packet, "b", WITHER_TYPE_ID);
        setField(packet, "c", toFixedPoint(loc.getX()));
        setField(packet, "d", toFixedPoint(loc.getY()));
        setField(packet, "e", toFixedPoint(loc.getZ()));
        setField(packet, "f", 0);          // velocity x
        setField(packet, "g", 0);          // velocity y
        setField(packet, "h", 0);          // velocity z
        setField(packet, "i", (byte) 0);   // yaw
        setField(packet, "j", (byte) 0);   // pitch
        setField(packet, "k", (byte) 0);   // head rotation
        setField(packet, "l", dataWatcher);

        return packet;
    }

    private void sendMetadata() {
        try {
            Object dataWatcher = buildDataWatcher();
            Constructor<?> ctor = packetMetadataClass.getConstructor(int.class, dataWatcherClass, boolean.class);
            Object packet = ctor.newInstance(entityId, dataWatcher, true);
            sendPacket(player, packet);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ==========================================
    // Utility
    // ==========================================

    private static void sendPacket(Player player, Object packet) {
        try {
            Object handle = getHandleMethod.invoke(player);
            Object connection = playerConnectionField.get(handle);
            sendPacketMethod.invoke(connection, packet);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static Class<?> getNMSClass(String name) throws ClassNotFoundException {
        return Class.forName("net.minecraft.server." + nmsVersion + "." + name);
    }

    private static void setField(Object obj, String fieldName, Object value) throws Exception {
        Field field = obj.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(obj, value);
    }

    private static int toFixedPoint(double value) {
        return (int) (value * 32.0D);
    }
}
