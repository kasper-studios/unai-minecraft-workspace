package xyz.kasperstudios.unai.bridge.forge;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.logging.LogUtils;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.util.*;

/**
 * FakePlayerManager - manages the virtual AI avatar "bot player" in Forge 1.21.1.
 * Features:
 * - Offline-mode fake player entity with custom skin injection & full 3D layer visibility
 * - Full TabList synchronization via PlayerList injection & ClientboundPlayerInfoUpdatePacket
 * - 3D A* Pathfinding (KasHub engine) with jump and obstacle navigation
 * - Perception Engine (3D ASCII View, 2D POV Radar, Crosshair Target, 60-Frame Ring Buffer)
 */
public class FakePlayerManager {

    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String DEFAULT_BOT_NAME = "DiromPrime";

    private MinecraftServer server;
    private ServerPlayer bot;
    private GameProfile profile;

    // Movement & Pathfinding state
    private volatile boolean isNavigating = false;
    private volatile List<BlockPos> currentPath = null;
    private volatile int pathIndex = 0;
    private volatile BlockPos targetBlockPos = null;
    private volatile float targetRadius = 1.2f;
    private String navStatus = "IDLE";

    private Vec3 lastPos = null;
    private int stuckTicks = 0;
    private static final int STUCK_THRESHOLD = 30; // 1.5 seconds

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
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public synchronized String spawn(String name, Double x, Double y, Double z, String skinSpec) {
        if (server == null) return "error: server not ready";
        name = (name == null || name.isBlank()) ? DEFAULT_BOT_NAME : name.trim();
        if (name.length() > 16) name = name.substring(0, 16);

        final String fName = name;
        despawn();

        profile = new GameProfile(offlineUuid(fName), fName);
        applySkin(fName, skinSpec);

        ServerLevel level = server.overworld();
        double sx = x != null ? x : 0.5;
        double sy = y != null ? y : 64.0;
        double sz = z != null ? z : 0.5;

        final double fx = sx, fy = sy, fz = sz;
        final GameProfile fProfile = profile;
        final ServerLevel fLevel = level;

        server.execute(() -> {
            try {
                DummyConnection dummyConnection = new DummyConnection();
                bot = new ServerPlayer(server, fLevel, fProfile,
                        net.minecraft.server.level.ClientInformation.createDefault()) {
                    @Override
                    public boolean isSpectator() { return false; }
                    @Override
                    public boolean isCreative() { return false; }
                };

                CommonListenerCookie cookie = CommonListenerCookie.createInitial(fProfile, false);
                new DummyGamePacketListener(server, dummyConnection, bot, cookie);

                bot.setPosRaw(fx, fy, fz);
                bot.setUUID(profile.getId());
                bot.gameMode.changeGameModeForPlayer(net.minecraft.world.level.GameType.SURVIVAL);
                bot.setInvulnerable(false);

                // Enable all 3D skin model layers (cape, jacket, left/right sleeve, left/right pants, hat/hood)
                try {
                    for (Field f : Player.class.getDeclaredFields()) {
                        if (f.getType() == net.minecraft.network.syncher.EntityDataAccessor.class) {
                            f.setAccessible(true);
                            Object val = f.get(null);
                            if (val instanceof net.minecraft.network.syncher.EntityDataAccessor<?> acc) {
                                if (f.getName().equals("DATA_PLAYER_MODE_CUSTOMISATION") || f.getName().equals("f_36081_")) {
                                    @SuppressWarnings("unchecked")
                                    net.minecraft.network.syncher.EntityDataAccessor<Byte> byteAcc = (net.minecraft.network.syncher.EntityDataAccessor<Byte>) acc;
                                    bot.getEntityData().set(byteAcc, (byte) 127);
                                    break;
                                }
                            }
                        }
                    }
                } catch (Throwable t) {
                    LOGGER.warn("[UnAI-Bridge] Failed to set player model customization: " + t.getMessage());
                }

                // Add to world level
                fLevel.addNewPlayer(bot);

                // Inject strictly into server PlayerList.players and playersByUUID
                injectIntoPlayerList(server.getPlayerList(), bot);

                // Broadcast player info with all actions (ADD_PLAYER, UPDATE_LISTED = true, etc.)
                EnumSet<ClientboundPlayerInfoUpdatePacket.Action> actions = EnumSet.of(
                        ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
                        ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED,
                        ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY,
                        ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE,
                        ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME
                );
                ClientboundPlayerInfoUpdatePacket tabPacket = new ClientboundPlayerInfoUpdatePacket(actions, List.of(bot));
                server.getPlayerList().broadcastAll(tabPacket);

                // Broadcast entity spawn and data to nearby players
                server.getPlayerList().broadcastAll(new ClientboundAddEntityPacket(bot, 0, bot.blockPosition()));
                if (bot.getEntityData().isDirty()) {
                    server.getPlayerList().broadcastAll(new ClientboundSetEntityDataPacket(bot.getId(), bot.getEntityData().packDirty()));
                }

                LOGGER.info("[UnAI-Bridge] Fake player '{}' spawned at {}, {}, {} and registered in TabList", fName, fx, fy, fz);
            } catch (Throwable t) {
                LOGGER.error("[UnAI-Bridge] Spawn failed", t);
            }
        });

        return "spawned:" + fName;
    }

    /**
     * Injects the bot strictly into PlayerList.players and PlayerList.playersByUUID using reflection.
     */
    @SuppressWarnings("unchecked")
    private void injectIntoPlayerList(PlayerList playerList, ServerPlayer player) {
        if (playerList == null || player == null) return;
        try {
            for (Field f : PlayerList.class.getDeclaredFields()) {
                f.setAccessible(true);
                Object val = f.get(playerList);
                if (val instanceof List) {
                    List<?> list = (List<?>) val;
                    // Check if it's the players list (either empty or contains ServerPlayers)
                    if (list.isEmpty() || list.get(0) instanceof ServerPlayer) {
                        List<ServerPlayer> playerListCast = (List<ServerPlayer>) list;
                        if (!playerListCast.contains(player)) {
                            playerListCast.add(player);
                        }
                    }
                } else if (val instanceof Map) {
                    Map<?, ?> map = (Map<?, ?>) val;
                    // Only target maps whose values are ServerPlayer (playersByUUID), NOT ServerStatsCounter or PlayerAdvancements!
                    if (!map.isEmpty()) {
                        Object firstVal = map.values().iterator().next();
                        if (firstVal instanceof ServerPlayer) {
                            Map<Object, Object> castMap = (Map<Object, Object>) map;
                            castMap.put(player.getUUID(), player);
                        }
                    } else if (f.getName().toLowerCase(Locale.ROOT).contains("uuid")) {
                        Map<Object, Object> castMap = (Map<Object, Object>) map;
                        castMap.put(player.getUUID(), player);
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("[UnAI-Bridge] Reflection injection into PlayerList failed: " + t.getMessage());
        }
    }

    /**
     * Removes the bot from PlayerList using reflection.
     */
    @SuppressWarnings("unchecked")
    private void removeFromPlayerList(PlayerList playerList, ServerPlayer player) {
        if (playerList == null || player == null) return;
        try {
            for (Field f : PlayerList.class.getDeclaredFields()) {
                f.setAccessible(true);
                Object val = f.get(playerList);
                if (val instanceof List) {
                    List<?> list = (List<?>) val;
                    if (!list.isEmpty() && list.get(0) instanceof ServerPlayer) {
                        List<ServerPlayer> castList = (List<ServerPlayer>) list;
                        castList.remove(player);
                    }
                } else if (val instanceof Map) {
                    Map<?, ?> map = (Map<?, ?>) val;
                    if (!map.isEmpty() && map.values().iterator().next() instanceof ServerPlayer) {
                        Map<Object, Object> castMap = (Map<Object, Object>) map;
                        castMap.remove(player.getUUID());
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("[UnAI-Bridge] Reflection removal from PlayerList failed: " + t.getMessage());
        }
    }

    /**
     * Called when a player joins to ensure they get the bot in their TabList.
     */
    public void onPlayerJoin(ServerPlayer joiningPlayer) {
        if (bot != null && !bot.isRemoved() && joiningPlayer != null) {
            try {
                EnumSet<ClientboundPlayerInfoUpdatePacket.Action> actions = EnumSet.of(
                        ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
                        ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED,
                        ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY,
                        ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE,
                        ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME
                );
                joiningPlayer.connection.send(new ClientboundPlayerInfoUpdatePacket(actions, List.of(bot)));
                joiningPlayer.connection.send(new ClientboundAddEntityPacket(bot, 0, bot.blockPosition()));
            } catch (Throwable ignored) {}
        }
    }

    private Property buildLocalDefaultSkinProperty() {
        String value = "ewogICJ0aW1lc3RhbXAiIDogMTc4Nzc2OTc5ODM1MiwKICAicHJvZmlsZUlkIiA6ICJmZDIwMGYwMDE4OTI0NzgxODI5OWIzZjE5Yzc4Y2E3MSIsCiAgInByb2ZpbGVOYW1lIiA6ICJ0dXNnIiwKICAic2lnbmF0dXJlUmVxdWlyZWQiIDogdHJ1ZSwKICAidGV4dHVyZXMiIDogewogICAgIlNLSU4iIDogewogICAgICAidXJsIiA6ICJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzE4MDY0MmRkMjk1Y2EzYjNhODE1NDkyOTQ1YWZjZmQwYmQxNzRjYmQ1MGRjZDU3ZGM0Y2I2YzM0ZTYxZGRhYzUiCiAgICB9CiAgfQp9";
        String sig = "tkp6cXkXK/eRjoFdqgk5eg6YjBWmjwZQfl2DwGb5aXLhYuA+nTLppClSyHdGtYAnn/mzTYmT43e2OtTHH7pM1H5yA9+IC5ByOeX6uSPtw3QwTkwoZhCsWFUxqBD+WFtFCbGyIafZYRqhWw1EriSJN+MFV7FtdudnJbtCZEq6ZO1H1sVSSSLKXcm6MhC6u7BPX61hJaFztAGlYEAefvUnqPOZCw5GfUDS/vmDBEU1uitRxLjd/iUEhwpvjaJe6v4wv78EHWIogGaffb8rRequiPIWp8WCnUDI2tIO71s20A+Kt/tvkfYj9dI4/jjzxQk3cX6mhzMqdB64tZyYeHt4ovVOJV/wybeK7Av5ipe3NZUIh4c7aGHRhZq+Vud/oVKEdIfaBhubVtXv3tLC64NocWfXfmGsvKjP4u5kqtI2L1uttMHlFLKT3B9l0S4Kgbv5I4Z0PT7CpK3jLgSgYUNdCfh6OABV96+YhYRTXhhcJUfG8caC1qk/2juwnXugbiWpaYhsXVTDdrWErBDRh51ppJfMHjUWXOf0Gw2ndMVwuEJAcGjYfpmmR6oB34/4lVndLC27BwDDfTyxM7smrFRFPCzk8yXPGSCWq7/CXi17o1DjtEoH9Wc9Uyaq4Yk5tR8Xl+ao+MO/Xsrq7mmvaCr36r0CwWebjrU5JMe2F1oFcFk=";
        return new Property("textures", value, sig);
    }

    private void applySkin(String name, String skinSpec) {
        String spec = (skinSpec == null || skinSpec.isBlank()) ? "default" : skinSpec.trim();
        Property defaultProp = buildLocalDefaultSkinProperty();
        if (profile != null) {
            profile.getProperties().put("textures", defaultProp);
        }

        if (!"default".equals(spec)) {
            new Thread(() -> {
                try {
                    Property texProp = fetchMojangSkinByName(spec);
                    if (texProp != null && profile != null) {
                        profile.getProperties().removeAll("textures");
                        profile.getProperties().put("textures", texProp);
                        LOGGER.info("[UnAI-Bridge] Custom skin applied for '{}'", spec);

                        if (server != null && bot != null) {
                            server.execute(() -> {
                                server.getPlayerList().broadcastAll(
                                        new ClientboundPlayerInfoUpdatePacket(EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER, ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED), List.of(bot))
                                );
                            });
                        }
                    }
                } catch (Throwable t) {
                    LOGGER.warn("[UnAI-Bridge] Custom skin fetch failed: " + t.getMessage());
                }
            }, "UnAI-SkinLoader").start();
        }
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
                removeFromPlayerList(server.getPlayerList(), b);
                b.discard();
                server.getPlayerList().remove(b);
            } catch (Throwable t) {
                LOGGER.warn("[UnAI-Bridge] despawn error", t);
            }
        });
        bot = null;
        stopMove();
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
            case "jump" -> server.execute(() -> {
                bot.setDeltaMovement(bot.getDeltaMovement().x, 0.48, bot.getDeltaMovement().z);
                bot.hasImpulse = true;
                bot.hurtMarked = true;
                server.getPlayerList().broadcastAll(new net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket(bot));
            });
            case "swing" -> server.execute(() -> {
                bot.swing(InteractionHand.MAIN_HAND, true);
                server.getPlayerList().broadcastAll(new net.minecraft.network.protocol.game.ClientboundAnimatePacket(bot, 0));
            });
            case "sneak" -> server.execute(() -> {
                boolean nextShift = !bot.isShiftKeyDown();
                bot.setShiftKeyDown(nextShift);
                bot.setPose(nextShift ? net.minecraft.world.entity.Pose.CROUCHING : net.minecraft.world.entity.Pose.STANDING);
                if (bot.getEntityData().isDirty()) {
                    server.getPlayerList().broadcastAll(new ClientboundSetEntityDataPacket(bot.getId(), bot.getEntityData().packDirty()));
                }
            });
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

    public synchronized String navigateTo(double x, double y, double z, float radius) {
        if (!isSpawned()) return "error: bot not spawned";
        ServerLevel level = bot.serverLevel();
        BlockPos start = bot.blockPosition();
        BlockPos end = new BlockPos((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));

        AStarPathfinder.PathOptions opt = new AStarPathfinder.PathOptions();
        opt.targetRadius = radius;

        List<BlockPos> path = AStarPathfinder.findPath(level, start, end, opt);
        if (path == null || path.isEmpty()) {
            navStatus = "NO_PATH_FOUND";
            return "error: no path found to target";
        }

        currentPath = path;
        pathIndex = 0;
        targetBlockPos = end;
        targetRadius = radius;
        isNavigating = true;
        navStatus = "NAVIGATING";
        stuckTicks = 0;
        lastPos = bot.position();

        LOGGER.info("[UnAI-Bridge] A* Path computed: {} nodes to ({}, {}, {})", path.size(), end.getX(), end.getY(), end.getZ());
        return "navigating: path length " + path.size();
    }

    public synchronized String stopMove() {
        isNavigating = false;
        currentPath = null;
        pathIndex = 0;
        targetBlockPos = null;
        navStatus = "IDLE";
        stuckTicks = 0;
        if (bot != null && !bot.isRemoved()) {
            bot.setDeltaMovement(Vec3.ZERO);
            bot.hurtMarked = true;
        }
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

    /**
     * Called on each server tick to process navigation and perception.
     */
    public void tick() {
        if (bot == null || bot.isRemoved()) {
            bot = null;
            return;
        }

        // Tick Perception Engine buffer
        PerceptionEngine.getInstance().tick(bot);

        // Rotation easing
        if (!Float.isNaN(targetYaw)) {
            float cur = bot.getYRot();
            float diff = normAngle(targetYaw - cur);
            float step = Math.max(-15f, Math.min(15f, diff * 0.40f));
            float next = cur + step;
            if (Math.abs(diff) < 0.5f) { next = targetYaw; targetYaw = Float.NaN; }
            bot.setYRot(next);
            bot.setYHeadRot(next);
            bot.setYBodyRot(next);
        }
        if (!Float.isNaN(targetPitch)) {
            float cur = bot.getXRot();
            float diff = targetPitch - cur;
            float step = Math.max(-15f, Math.min(15f, diff * 0.40f));
            float next = cur + step;
            if (Math.abs(diff) < 0.5f) { next = targetPitch; targetPitch = Float.NaN; }
            bot.setXRot(next);
        }

        // A* Path execution
        if (isNavigating && currentPath != null && pathIndex < currentPath.size()) {
            Vec3 pos = bot.position();

            // Stuck detection
            if (lastPos != null && pos.distanceToSqr(lastPos) < 0.005) {
                stuckTicks++;
                if (stuckTicks > STUCK_THRESHOLD) {
                    LOGGER.warn("[UnAI-Bridge] Bot stuck during pathfinding. Stopping.");
                    stopMove();
                    navStatus = "STUCK";
                    return;
                }
            } else {
                stuckTicks = 0;
            }
            lastPos = pos;

            BlockPos node = currentPath.get(pathIndex);
            double targetX = node.getX() + 0.5;
            double targetY = node.getY();
            double targetZ = node.getZ() + 0.5;

            double dx = targetX - pos.x;
            double dy = targetY - pos.y;
            double dz = targetZ - pos.z;
            double horizontalDistSq = dx * dx + dz * dz;

            // If close to current waypoint, advance
            if (horizontalDistSq < 0.35 && Math.abs(dy) < 1.2) {
                pathIndex++;
                if (pathIndex >= currentPath.size()) {
                    isNavigating = false;
                    navStatus = "ARRIVED";
                    bot.setDeltaMovement(Vec3.ZERO);
                    bot.hurtMarked = true;
                    return;
                }
                node = currentPath.get(pathIndex);
                targetX = node.getX() + 0.5;
                targetY = node.getY();
                targetZ = node.getZ() + 0.5;
                dx = targetX - pos.x;
                dy = targetY - pos.y;
                dz = targetZ - pos.z;
                horizontalDistSq = dx * dx + dz * dz;
            }

            double hDist = Math.sqrt(horizontalDistSq);
            if (hDist > 0.01) {
                double speed = 0.28; // ~5.6 blocks/second sprint
                Vec3 vel = new Vec3(dx / hDist * speed, bot.getDeltaMovement().y, dz / hDist * speed);

                // Jump if step up
                if (dy > 0.5 && bot.onGround()) {
                    bot.jumpFromGround();
                }

                bot.setDeltaMovement(vel);
                bot.hurtMarked = true;

                float yawToTarget = (float) (-Math.toDegrees(Math.atan2(dx, dz)));
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

    public String navStatusJson() {
        if (!isSpawned()) return "{\"status\":\"NOT_SPAWNED\"}";
        return String.format("{\"status\":\"%s\",\"navigating\":%b,\"path_index\":%d,\"path_total\":%d,\"target\":%s}",
                navStatus, isNavigating, pathIndex, (currentPath != null ? currentPath.size() : 0),
                (targetBlockPos != null ? String.format("{\"x\":%d,\"y\":%d,\"z\":%d}", targetBlockPos.getX(), targetBlockPos.getY(), targetBlockPos.getZ()) : "null"));
    }

    public String statusJson() {
        if (!isSpawned()) return "{\"spawned\": false}";
        Vec3 p = bot.position();
        return String.format("{\"spawned\":true,\"name\":\"%s\",\"x\":%.2f,\"y\":%.2f,\"z\":%.2f,\"yaw\":%.2f,\"pitch\":%.2f,\"nav_status\":\"%s\",\"moving\":%b,\"health\":%.1f,\"hunger\":%d}",
                bot.getName().getString(), p.x, p.y, p.z, bot.getYRot(), bot.getXRot(), navStatus, isNavigating, bot.getHealth(), bot.getFoodData().getFoodLevel());
    }

    /** Dummy Connection for a fake player: safe embedded channel & no-op flush. */
    private static class DummyConnection extends Connection {
        public DummyConnection() {
            super(PacketFlow.SERVERBOUND);
            try {
                for (Field f : Connection.class.getDeclaredFields()) {
                    if (io.netty.channel.Channel.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        f.set(this, new EmbeddedChannel());
                        break;
                    }
                }
            } catch (Throwable ignored) {}
        }

        @Override
        public void flushChannel() {}

        @Override
        public void send(Packet<?> packet, PacketSendListener listener) {}

        @Override
        public void send(Packet<?> packet) {}

        @Override
        public void disconnect(Component reason) {}

        @Override
        public boolean isConnected() { return true; }

        @Override
        public boolean isMemoryConnection() { return true; }

        @Override
        public java.net.SocketAddress getRemoteAddress() {
            return new InetSocketAddress("127.0.0.1", 0);
        }
    }

    /** Dummy GamePacketListener: ignores packet flushes and tick loops. */
    private static class DummyGamePacketListener extends ServerGamePacketListenerImpl {
        public DummyGamePacketListener(MinecraftServer server, Connection connection, ServerPlayer player, CommonListenerCookie cookie) {
            super(server, connection, player, cookie);
        }

        @Override
        public void send(Packet<?> packet, PacketSendListener listener) {}

        @Override
        public void send(Packet<?> packet) {}

        @Override
        public void resumeFlushing() {}

        @Override
        public void suspendFlushing() {}

        @Override
        public void tick() {}
    }
}
