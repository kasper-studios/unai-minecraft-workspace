package xyz.kasperstudios.unai.bridge.forge;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.logging.LogUtils;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.util.EnumSet;
import java.util.UUID;

/**
 * FakePlayerManager - manages the virtual AI avatar "bot player".
 *
 * Uses a lightweight offline-mode ServerPlayer subclass. No ClientInformation
 * dependency (offline stub uses defaults).
 */
public class FakePlayerManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final String DEFAULT_BOT_NAME = "DiromPrime";

    private MinecraftServer server;
    private ServerPlayer bot;
    private GameProfile profile;

    // Movement state
    private Vec3 moveTarget = null;
    private double moveSpeed = 4.3; // blocks/~tick scale handled in tick()
    private float targetYaw = Float.NaN;
    private float targetPitch = Float.NaN;

    private static final FakePlayerManager INSTANCE = new FakePlayerManager();

    public static FakePlayerManager getInstance() {
        return INSTANCE;
    }

    private FakePlayerManager() {}

    public void setServer(MinecraftServer server) {
        this.server = server;
    }

    public boolean isSpawned() {
        return bot != null && !bot.isRemoved();
    }

    public ServerPlayer getBot() {
        return bot;
    }

    private static UUID offlineUuid(String name) {
        // Mirror offline-mode UUID derivation used by vanilla
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public synchronized String spawn(String name, Double x, Double y, Double z, String skinSpec) {
        if (server == null) return "error: server not ready";
        name = (name == null || name.isBlank()) ? DEFAULT_BOT_NAME : name.trim();
        if (name.length() > 16) name = name.substring(0, 16);

        final String fName = name;
        despawn(); // ensure single instance

        profile = new GameProfile(offlineUuid(fName), fName);

        // Apply skin: command "skin_spec" semantics:
        //  - null/empty/"default": embedded default skin (resource-packed Bas64? fallback: name-based)
        //  - a Minecraft nickname: fetch textures property from Mojang (async done below)
        applySkin(fName, skinSpec);

        ServerLevel level = server.overworld();
        double sx = x != null ? x : (server.getWorldData().worldGenOptions() != null ? 0.5 : 0.5);
        double sy = y != null ? y : 64.0;
        double sz = z != null ? z : 0.5;

        final double fx = sx, fy = sy, fz = sz;
        final GameProfile fProfile = profile;
        final ServerLevel fLevel = level;

        server.execute(() -> {
            try {
                Connection dummyConnection = new DummyConnection();
                bot = new ServerPlayer(server, fLevel, fProfile,
                        net.minecraft.server.level.ClientInformation.createDefault()) {
                    @Override
                    public boolean isSpectator() { return false; }
                };
                
                try {
                    CommonListenerCookie cookie = CommonListenerCookie.createInitial(fProfile, false);
                    new ServerGamePacketListenerImpl(server, dummyConnection, bot, cookie) {
                        @Override
                        public void send(Packet<?> packet, PacketSendListener listener) {}
                    };
                } catch (Throwable t) {
                    LOGGER.warn("[UnAI-Bridge] Failed to attach dummy listener", t);
                }

                bot.setPosRaw(fx, fy, fz);
                bot.setUUID(profile.getId());
                fLevel.addNewPlayer(bot);
                server.getPlayerList().broadcastAll(
                        ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(java.util.List.of(bot)));
                
                LOGGER.info("[UnAI-Bridge] Fake player '{}' spawned at {}, {}, {}", fName, fx, fy, fz);
            } catch (Throwable t) {
                LOGGER.error("[UnAI-Bridge] Spawn failed", t);
            }
        });

        return "spawned:" + fName;
    }

    private void applySkin(String name, String skinSpec) {
        // Default: embedded skin PNg - we encode it as a textures payload without signature
        // (works on offline-mode servers).
        String spec = (skinSpec == null || skinSpec.isBlank()) ? "default" : skinSpec.trim();
        new Thread(() -> {
            try {
                Property texProp = null;
                if ("default".equals(spec)) {
                    texProp = buildLocalDefaultSkinProperty();
                } else {
                    // Treat as Mojang nickname -> fetch official skin
                    texProp = fetchMojangSkinByName(spec);
                }
                if (texProp != null && profile != null) {
                    profile.getProperties().put("textures", texProp);
                    LOGGER.info("[UnAI-Bridge] Skin applied ({}).", spec);
                } else {
                    LOGGER.warn("[UnAI-Bridge] Skin '{}' not applied, using vanilla fallback", spec);
                }
            } catch (Throwable t) {
                LOGGER.warn("[UnAI-Bridge] Skin fetch failed: " + t.getMessage());
            }
        }, "UnAI-SkinLoader").start();
    }

    private Property buildLocalDefaultSkinProperty() {
        // Build a textures property pointing at our embedded skin via textures.minecraft.net is
        // not possible without Mojang hosting; offline servers rely on signed properties.
        // Fallback: leave null skin (Steve/Alex) and log note; custom nick path works for real skins.
        return null;
    }

    private Property fetchMojangSkinByName(String nick) {
        try {
            String uuidJson = httpGet("https://api.mojang.com/users/profiles/minecraft/" + nick);
            if (uuidJson == null || !uuidJson.contains("\"id\"")) return null;
            String id = uuidJson.replaceAll(".*\"id\"\\s*:\\s*\"([0-9a-f]+)\".*", "$1");
            String sessionJson = httpGet("https://sessionserver.mojang.com/session/minecraft/profile/" + id + "?unsigned=false");
            if (sessionJson == null) return null;
            String value = sessionJson.replaceAll(".*\"value\"\\s*:\\s*\"([^\"]+)\".*", "$1");
            String sig = sessionJson.replaceAll(".*\"signature\"\\s*:\\s*\"([^\"]+)\".*", "$1");
            if (!value.contains("=") && !value.isEmpty()) return new Property("textures", value, sig);
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private String httpGet(String url) {
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            c.setRequestProperty("User-Agent", "UnAI-Bridge/1.1");
            if (c.getResponseCode() != 200) return null;
            try (java.io.InputStream in = c.getInputStream()) {
                return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Throwable t) {
            return null;
        }
    }

    public synchronized String despawn() {
        if (bot == null) return "not_spawned";
        final ServerPlayer b = bot;
        server.execute(() -> {
            try {
                b.discard();
                server.getPlayerList().remove(b);
            } catch (Throwable t) {
                LOGGER.warn("[UnAI-Bridge] despawn error", t);
            }
        });
        bot = null;
        moveTarget = null;
        return "despawned";
    }

    public synchronized String say(String message) {
        if (!isSpawned()) return "error: bot not spawned";
        final String m = message;
        server.execute(() -> server.getPlayerList().broadcastSystemMessage(
                Component.literal("<" + bot.getName().getString() + "> " + m), false));
        return "ok";
    }

    public synchronized String action(String action) {
        if (!isSpawned()) return "error: bot not spawned";
        switch (action.toLowerCase()) {
            case "jump" -> bot.jumpFromGround();
            case "swing" -> server.execute(() -> bot.swing(InteractionHand.MAIN_HAND, true));
            case "sneak" -> bot.setShiftKeyDown(!bot.isShiftKeyDown());
            case "spin" -> { targetYaw = bot.getYRot() + 360f; }
            default -> { return "unknown_action"; }
        }
        return "ok";
    }

    public synchronized String equip(String slot, String itemId) {
        if (!isSpawned()) return "error: bot not spawned";
        var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                net.minecraft.resources.ResourceLocation.parse(itemId));
        if (item == net.minecraft.world.item.Items.AIR && !"air".equals(itemId)) return "unknown_item";
        ItemStack stack = new ItemStack(item);
        server.execute(() -> {
            switch (slot) {
                case "head" -> bot.setItemSlot(EquipmentSlot.HEAD, stack.copy());
                case "chest" -> bot.setItemSlot(EquipmentSlot.CHEST, stack.copy());
                case "legs" -> bot.setItemSlot(EquipmentSlot.LEGS, stack.copy());
                case "feet" -> bot.setItemSlot(EquipmentSlot.FEET, stack.copy());
                case "offhand" -> bot.setItemSlot(EquipmentSlot.OFFHAND, stack.copy());
                default -> bot.setItemSlot(EquipmentSlot.MAINHAND, stack.copy());
            }
        });
        return "ok";
    }

    public synchronized String moveTo(double x, double y, double z) {
        if (!isSpawned()) return "error: bot not spawned";
        moveTarget = new Vec3(x, y, z);
        return "ok";
    }

    public synchronized String stopMove() {
        moveTarget = null;
        return "ok";
    }

    public synchronized String lookAt(Double tx, Double ty, Double tz, Float yaw, Float pitch) {
        if (!isSpawned()) return "error: bot not spawned";
        if (yaw != null) targetYaw = yaw;
        if (pitch != null) targetPitch = pitch;
        if (tx != null && ty != null && tz != null) {
            Vec3 eye = bot.getEyePosition();
            double dx = tx - eye.x, dy = ty - eye.y, dz = tz - eye.z;
            double dist = Math.sqrt(dx*dx + dz*dz);
            targetYaw = (float) (-Math.toDegrees(Math.atan2(dx, dz)));
            targetPitch = (float) (-Math.toDegrees(Math.atan2(dy, dist)));
        }
        return "ok";
    }

    /** Called once per server tick from ForgeBridgeMod. */
    public void tick() {
        if (bot == null || bot.isRemoved()) { bot = null; return; }

        // Rotation easing
        if (!Float.isNaN(targetYaw)) {
            float cur = bot.getYRot();
            float diff = normAngle(targetYaw - cur);
            float step = Math.max(-10f, Math.min(10f, diff * 0.35f));
            float next = cur + step;
            if (Math.abs(diff) < 0.5f) { next = targetYaw; targetYaw = Float.NaN; }
            bot.setYRot(next);
            bot.setYHeadRot(next);
            bot.setYBodyRot(next);
        }
        if (!Float.isNaN(targetPitch)) {
            float cur = bot.getXRot();
            float diff = targetPitch - cur;
            float step = Math.max(-10f, Math.min(10f, diff * 0.35f));
            float next = cur + step;
            if (Math.abs(diff) < 0.5f) { next = targetPitch; targetPitch = Float.NaN; }
            bot.setXRot(next);
        }

        // Straight-line movement (v1 — A* integration replaces this)
        if (moveTarget != null) {
            Vec3 pos = bot.position();
            Vec3 delta = moveTarget.subtract(pos);
            double dist = Math.sqrt(delta.x*delta.x + delta.z*delta.z);
            double vert = delta.y;
            if (dist < 1.2) {
                bot.setDeltaMovement(Vec3.ZERO);
                bot.hurtMarked = true;
                moveTarget = null;
            } else {
                double spd = Math.min(0.30, moveSpeed / 20.0);
                Vec3 vel = new Vec3(delta.x / dist, 0, delta.z / dist).scale(spd);
                if (vert > 0.6 && bot.onGround()) bot.jumpFromGround();
                bot.setDeltaMovement(vel.x, bot.getDeltaMovement().y, vel.z);
                bot.hurtMarked = true;
                float yawToTarget = (float) (-Math.toDegrees(Math.atan2(delta.x, delta.z)));
                bot.setYRot(yawToTarget);
                bot.setYHeadRot(yawToTarget);
                bot.setYBodyRot(yawToTarget);
            }
        }
    }

    private float normAngle(float a) {
        a = a % 360f;
        if (a > 180f) a -= 360f;
        if (a < -180f) a += 360f;
        return a;
    }

    public String statusJson() {
        if (!isSpawned()) return "{\"spawned\": false}";
        Vec3 p = bot.position();
        return "{\"spawned\":true,\"name\":\"" + bot.getName().getString()
                + "\",\"x\":" + round2(p.x) + ",\"y\":" + round2(p.y) + ",\"z\":" + round2(p.z)
                + ",\"yaw\":" + round2(bot.getYRot()) + ",\"pitch\":" + round2(bot.getXRot())
                + ",\"moving\":" + (moveTarget != null) + "}";
    }

    private double round2(double v) { return Math.round(v * 100.0) / 100.0; }

    /** Dummy Connection for a fake player: no socket traffic. */
    private static class DummyConnection extends Connection {
        public DummyConnection() {
            super(PacketFlow.SERVERBOUND);
        }

        @Override
        public void send(Packet<?> packet, PacketSendListener listener) {}

        @Override
        public void send(Packet<?> packet) {}

        @Override
        public void disconnect(Component reason) {}

        @Override
        public boolean isConnected() { return true; }

        @Override
        public java.net.SocketAddress getRemoteAddress() {
            return new InetSocketAddress("127.0.0.1", 0);
        }
    }
}
