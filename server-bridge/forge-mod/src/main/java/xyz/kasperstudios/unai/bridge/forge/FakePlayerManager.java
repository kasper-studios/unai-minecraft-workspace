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
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.util.*;

/**
 * FakePlayerManager - manages the virtual AI avatar "bot player" in Forge 1.21.1.
 * Features:
 * - Offline-mode fake player entity with custom skin injection & full 3D layer visibility
 * - Full TabList synchronization via PlayerList injection & ClientboundPlayerInfoUpdatePacket
 * - Real in-world physics (gravity, jump arcs, damage red flash, knockback, crouching)
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
    private static final int STUCK_THRESHOLD = 30;

    private float targetYaw = Float.NaN;
    private float targetPitch = Float.NaN;

    private volatile boolean isGuardMode = false;
    private volatile String guardTargetPlayer = null;

    // Chunk loader state
    private volatile int chunkLoadRadius = 0;
    private final Set<ChunkPos> loadedChunks = Collections.synchronizedSet(new HashSet<>());
    private ChunkPos lastBotChunkPos = null;

    // Autonomous Living & Idle Life Engine
    private volatile boolean autonomousMode = true;
    private BlockPos tetherHomePos = null;
    private int autonomousIdleTicks = 0;
    private int nextIdleActionTicks = 80;
    private int socialMirrorCooldown = 0;
    private final Random random = new Random();

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
                if (bot != null) {
                    int oldId = bot.getId();
                    removeFromPlayerList(server.getPlayerList(), bot);
                    bot.discard();
                    try {
                        fLevel.removePlayerImmediately(bot, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
                    } catch (Throwable ignored) {}
                    server.getPlayerList().broadcastAll(new net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket(oldId));
                }

                DummyConnection dummyConnection = new DummyConnection();
                bot = new ServerPlayer(server, fLevel, fProfile,
                        net.minecraft.server.level.ClientInformation.createDefault()) {
                    @Override
                    public boolean isSpectator() { return false; }
                    @Override
                    public boolean isCreative() { return false; }
                    @Override
                    public boolean isInvulnerable() { return false; }
                    @Override
                    public boolean isInvulnerableTo(DamageSource source) { return false; }
                    @Override
                    public boolean isAttackable() { return true; }
                    @Override
                    public void knockback(double strength, double x, double z) {
                        double len = Math.max(0.001, Math.sqrt(x * x + z * z));
                        Vec3 cur = this.getDeltaMovement();
                        double kx = (x / len) * strength;
                        double kz = (z / len) * strength;
                        this.setDeltaMovement(cur.x / 2.0 - kx, this.onGround() ? Math.min(0.45, cur.y / 2.0 + 0.38) : cur.y + 0.2, cur.z / 2.0 - kz);
                        this.hurtMarked = true;
                        this.hasImpulse = true;
                        server.getPlayerList().broadcastAll(new ClientboundSetEntityMotionPacket(this));
                    }
                    @Override
                    public boolean hurt(DamageSource src, float amount) {
                        boolean res = super.hurt(src, amount);
                        server.getPlayerList().broadcastAll(new ClientboundAnimatePacket(this, 1));
                        if (src.getEntity() != null) {
                            double dx = this.getX() - src.getEntity().getX();
                            double dz = this.getZ() - src.getEntity().getZ();
                            double dist = Math.max(0.1, Math.sqrt(dx * dx + dz * dz));
                            this.setDeltaMovement(dx / dist * 0.52, 0.40, dz / dist * 0.52);
                            this.hurtMarked = true;
                            this.hasImpulse = true;
                            server.getPlayerList().broadcastAll(new ClientboundSetEntityMotionPacket(this));
                        }
                        return res;
                    }
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

                // Broadcast exact entity spawn with double coordinates and head yaw
                server.getPlayerList().broadcastAll(new ClientboundAddEntityPacket(
                        bot.getId(), bot.getUUID(),
                        bot.getX(), bot.getY(), bot.getZ(),
                        bot.getXRot(), bot.getYRot(),
                        bot.getType(), 0,
                        bot.getDeltaMovement(),
                        bot.getYHeadRot()
                ));
                if (bot.getEntityData().isDirty()) {
                    server.getPlayerList().broadcastAll(new ClientboundSetEntityDataPacket(bot.getId(), bot.getEntityData().packDirty()));
                }

                // In-game join message
                server.getPlayerList().broadcastSystemMessage(Component.literal("§e" + fName + " joined the game"), false);

                LOGGER.info("[UnAI-Bridge] Fake player '{}' spawned at {}, {}, {} and registered in TabList", fName, fx, fy, fz);
            } catch (Throwable t) {
                LOGGER.error("[UnAI-Bridge] Spawn failed", t);
            }
        });

        tetherHomePos = new BlockPos((int)Math.floor(fx), (int)Math.floor(fy), (int)Math.floor(fz));
        autonomousIdleTicks = 0;
        autonomousMode = true;
        setStatusIndicator("idle", null, null);

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
                    if (list.isEmpty() || list.get(0) instanceof ServerPlayer) {
                        List<ServerPlayer> playerListCast = (List<ServerPlayer>) list;
                        if (!playerListCast.contains(player)) {
                            playerListCast.add(player);
                        }
                    }
                } else if (val instanceof Map) {
                    Map<?, ?> map = (Map<?, ?>) val;
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

    public void onPlayerJoin(ServerPlayer joiningPlayer) {
        if (bot != null && !bot.isRemoved() && joiningPlayer != null && server != null) {
            server.execute(() -> {
                try {
                    EnumSet<ClientboundPlayerInfoUpdatePacket.Action> actions = EnumSet.of(
                            ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
                            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED,
                            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY,
                            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE,
                            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME
                    );
                    joiningPlayer.connection.send(new ClientboundPlayerInfoUpdatePacket(actions, List.of(bot)));
                } catch (Throwable ignored) {}
            });
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
        final String fName = b.getName().getString();
        server.execute(() -> {
            try {
                server.getPlayerList().broadcastSystemMessage(Component.literal("§e" + fName + " left the game"), false);
                removeFromPlayerList(server.getPlayerList(), b);
                b.discard();
                server.getPlayerList().remove(b);
            } catch (Throwable t) {
                LOGGER.warn("[UnAI-Bridge] despawn error", t);
            }
        });
        bot = null;
        clearLoadedChunks();
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
                bot.setDeltaMovement(bot.getDeltaMovement().x, 0.54, bot.getDeltaMovement().z);
                bot.hasImpulse = true;
                bot.hurtMarked = true;
                server.getPlayerList().broadcastAll(new ClientboundSetEntityMotionPacket(bot));
                server.getPlayerList().broadcastAll(new net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket(bot));
            });
            case "swing", "attack" -> server.execute(() -> {
                bot.swing(InteractionHand.MAIN_HAND, true);
                server.getPlayerList().broadcastAll(new ClientboundAnimatePacket(bot, 0));

                // Perform real melee attack on entity in front of crosshairs
                try {
                    Vec3 eyePos = bot.getEyePosition();
                    Vec3 lookVec = bot.getViewVector(1.0f);
                    Vec3 reachVec = eyePos.add(lookVec.scale(4.0));
                    AABB box = bot.getBoundingBox().expandTowards(lookVec.scale(4.0)).inflate(1.5);
                    var hit = net.minecraft.world.entity.projectile.ProjectileUtil.getEntityHitResult(
                            bot.serverLevel(), bot, eyePos, reachVec, box, e -> !e.isSpectator() && e.isPickable() && e != bot
                    );
                    if (hit != null && hit.getEntity() != null) {
                        bot.attack(hit.getEntity());
                    }
                } catch (Throwable t) {
                    LOGGER.warn("[UnAI-Bridge] Attack error: " + t.getMessage());
                }
            });
            case "sneak" -> server.execute(() -> {
                boolean nextShift = !bot.isShiftKeyDown();
                bot.setShiftKeyDown(nextShift);
                bot.setPose(nextShift ? Pose.CROUCHING : Pose.STANDING);
                if (bot.getEntityData().isDirty()) {
                    server.getPlayerList().broadcastAll(new ClientboundSetEntityDataPacket(bot.getId(), bot.getEntityData().packDirty()));
                }
            });
            case "twerk", "teabag" -> server.execute(() -> {
                new Thread(() -> {
                    try {
                        for (int i = 0; i < 6; i++) {
                            boolean crouch = (i % 2 == 0);
                            server.execute(() -> {
                                bot.setShiftKeyDown(crouch);
                                bot.setPose(crouch ? Pose.CROUCHING : Pose.STANDING);
                                if (bot.getEntityData().isDirty()) {
                                    server.getPlayerList().broadcastAll(new ClientboundSetEntityDataPacket(bot.getId(), bot.getEntityData().packDirty()));
                                }
                            });
                            Thread.sleep(120);
                        }
                        server.execute(() -> {
                            bot.setShiftKeyDown(false);
                            bot.setPose(Pose.STANDING);
                        });
                    } catch (InterruptedException ignored) {}
                }).start();
            });
            case "nod" -> server.execute(() -> {
                new Thread(() -> {
                    try {
                        float originalPitch = bot.getXRot();
                        float originalYaw = bot.getYRot();
                        for (int i = 0; i < 4; i++) {
                            float p = (i % 2 == 0) ? 45.0f : -30.0f;
                            lookAt(null, null, null, originalYaw, p);
                            Thread.sleep(150);
                        }
                        lookAt(null, null, null, originalYaw, originalPitch);
                    } catch (InterruptedException ignored) {}
                }).start();
            });
            case "shake" -> server.execute(() -> {
                new Thread(() -> {
                    try {
                        float originalPitch = bot.getXRot();
                        float originalYaw = bot.getYRot();
                        for (int i = 0; i < 4; i++) {
                            float y = originalYaw + ((i % 2 == 0) ? 35.0f : -35.0f);
                            lookAt(null, null, null, y, originalPitch);
                            Thread.sleep(150);
                        }
                        lookAt(null, null, null, originalYaw, originalPitch);
                    } catch (InterruptedException ignored) {}
                }).start();
            });
            case "spin" -> { targetYaw = bot.getYRot() + 360f; }
            default -> { return "unknown_action"; }
        }
        return "ok";
    }

    private static String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    public synchronized String getInventoryJson() {
        if (!isSpawned()) return "{\"error\":\"bot not spawned\"}";
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"selected_slot\":").append(bot.getInventory().selected).append(",");
        sb.append("\"mainhand\":\"").append(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(bot.getMainHandItem().getItem()).toString()).append("\",");
        sb.append("\"offhand\":\"").append(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(bot.getOffhandItem().getItem()).toString()).append("\",");
        sb.append("\"armor\":{");
        sb.append("\"head\":\"").append(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(bot.getItemBySlot(EquipmentSlot.HEAD).getItem()).toString()).append("\",");
        sb.append("\"chest\":\"").append(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(bot.getItemBySlot(EquipmentSlot.CHEST).getItem()).toString()).append("\",");
        sb.append("\"legs\":\"").append(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(bot.getItemBySlot(EquipmentSlot.LEGS).getItem()).toString()).append("\",");
        sb.append("\"feet\":\"").append(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(bot.getItemBySlot(EquipmentSlot.FEET).getItem()).toString()).append("\"");
        sb.append("},\"items\":[");
        boolean first = true;
        for (int i = 0; i < bot.getInventory().getContainerSize(); i++) {
            ItemStack st = bot.getInventory().getItem(i);
            if (!st.isEmpty()) {
                if (!first) sb.append(",");
                first = false;
                sb.append("{\"slot\":").append(i)
                  .append(",\"id\":\"").append(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem()).toString()).append("\"")
                  .append(",\"count\":").append(st.getCount())
                  .append(",\"name\":\"").append(escapeJson(st.getHoverName().getString())).append("\"}");
            }
        }
        sb.append("]}");
        return sb.toString();
    }

    public synchronized String dropItem(int slot, int count) {
        if (!isSpawned()) return "error: bot not spawned";
        server.execute(() -> {
            ItemStack st = (slot >= 0 && slot < bot.getInventory().getContainerSize())
                    ? bot.getInventory().getItem(slot)
                    : bot.getMainHandItem();
            if (!st.isEmpty()) {
                int toDrop = (count <= 0 || count > st.getCount()) ? st.getCount() : count;
                ItemStack dropStack = st.split(toDrop);
                bot.drop(dropStack, true, false);
            }
        });
        return "ok";
    }

    public synchronized String selectSlot(int slot) {
        if (!isSpawned()) return "error: bot not spawned";
        if (slot < 0 || slot > 8) return "error: slot must be 0-8";
        server.execute(() -> {
            bot.getInventory().selected = slot;
            List<com.mojang.datafixers.util.Pair<EquipmentSlot, ItemStack>> list = new ArrayList<>();
            list.add(com.mojang.datafixers.util.Pair.of(EquipmentSlot.MAINHAND, bot.getMainHandItem()));
            server.getPlayerList().broadcastAll(new net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket(bot.getId(), list));
        });
        return "ok";
    }

    public synchronized String swapSlots(int fromSlot, int toSlot) {
        if (!isSpawned()) return "error: bot not spawned";
        int size = bot.getInventory().getContainerSize();
        if (fromSlot < 0 || fromSlot >= size || toSlot < 0 || toSlot >= size) {
            return "error: invalid slot index (0-" + (size - 1) + ")";
        }
        server.execute(() -> {
            ItemStack fromStack = bot.getInventory().getItem(fromSlot);
            ItemStack toStack = bot.getInventory().getItem(toSlot);
            bot.getInventory().setItem(fromSlot, toStack);
            bot.getInventory().setItem(toSlot, fromStack);

            List<com.mojang.datafixers.util.Pair<EquipmentSlot, ItemStack>> list = new ArrayList<>();
            for (EquipmentSlot es : EquipmentSlot.values()) {
                list.add(com.mojang.datafixers.util.Pair.of(es, bot.getItemBySlot(es)));
            }
            server.getPlayerList().broadcastAll(new net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket(bot.getId(), list));
        });
        return "ok";
    }

    public synchronized String useItem(String handStr) {
        if (!isSpawned()) return "error: bot not spawned";
        InteractionHand hand = "offhand".equalsIgnoreCase(handStr) ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        server.execute(() -> {
            ItemStack st = bot.getItemInHand(hand);
            if (!st.isEmpty()) {
                bot.gameMode.useItem(bot, bot.serverLevel(), st, hand);
                server.getPlayerList().broadcastAll(new net.minecraft.network.protocol.game.ClientboundAnimatePacket(bot, hand == InteractionHand.MAIN_HAND ? 0 : 3));
            }
        });
        return "ok";
    }

    public synchronized String clearInventory() {
        if (!isSpawned()) return "error: bot not spawned";
        server.execute(() -> {
            bot.getInventory().clearContent();
            List<com.mojang.datafixers.util.Pair<EquipmentSlot, ItemStack>> list = new ArrayList<>();
            for (EquipmentSlot es : EquipmentSlot.values()) {
                list.add(com.mojang.datafixers.util.Pair.of(es, ItemStack.EMPTY));
            }
            server.getPlayerList().broadcastAll(new net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket(bot.getId(), list));
        });
        return "ok";
    }

    public synchronized String craft(String recipeId) {
        if (!isSpawned()) return "error: bot not spawned";
        if (recipeId == null || recipeId.isBlank()) return "error: missing recipe or item id";
        
        var recipes = server.getRecipeManager().getRecipes();
        net.minecraft.world.item.crafting.RecipeHolder<?> targetHolder = null;
        for (var holder : recipes) {
            String idStr = holder.id().toString();
            var recipe = holder.value();
            ItemStack resStack = recipe.getResultItem(server.registryAccess());
            String resId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(resStack.getItem()).toString();
            if (idStr.equals(recipeId) || idStr.endsWith("/" + recipeId) || resId.equals(recipeId)) {
                targetHolder = holder;
                break;
            }
        }
        if (targetHolder == null) return "error: unknown recipe '" + recipeId + "'";
        
        var recipe = targetHolder.value();
        ItemStack resultStack = recipe.getResultItem(server.registryAccess()).copy();
        var ingredients = recipe.getIngredients();
        
        // Verify ingredients in bot inventory
        boolean canCraft = true;
        int invSize = bot.getInventory().getContainerSize();
        int[] slotCounts = new int[invSize];
        for (int i = 0; i < invSize; i++) {
            slotCounts[i] = bot.getInventory().getItem(i).getCount();
        }

        List<Integer> slotsToConsume = new ArrayList<>();
        for (var ing : ingredients) {
            if (ing.isEmpty()) continue;
            boolean matched = false;
            for (int i = 0; i < invSize; i++) {
                ItemStack invStack = bot.getInventory().getItem(i);
                if (!invStack.isEmpty() && slotCounts[i] > 0 && ing.test(invStack)) {
                    slotCounts[i]--;
                    slotsToConsume.add(i);
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                canCraft = false;
                break;
            }
        }
        
        if (!canCraft) return "error: missing required ingredients in bot inventory";
        
        server.execute(() -> {
            for (int slot : slotsToConsume) {
                ItemStack invStack = bot.getInventory().getItem(slot);
                if (!invStack.isEmpty()) {
                    invStack.shrink(1);
                }
            }
            bot.getInventory().add(resultStack);
        });
        
        return "crafted: " + resultStack.getCount() + "x " + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(resultStack.getItem());
    }

    public synchronized String breakBlock(int x, int y, int z) {
        if (!isSpawned() || server == null) return "error: bot not spawned";
        BlockPos pos = new BlockPos(x, y, z);
        double distSq = bot.distanceToSqr(Vec3.atCenterOf(pos));
        if (distSq > 36.0) return "error: block too far (" + String.format(Locale.ROOT, "%.1fm", Math.sqrt(distSq)) + " > 6m)";

        try {
            return server.submit(() -> {
                if (!isSpawned()) return "error: bot not spawned";
                ServerLevel level = bot.serverLevel();
                var state = level.getBlockState(pos);
                if (state.isAir()) return "error: block is air";
                String blockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();

                lookAt(x + 0.5, y + 0.5, z + 0.5, null, null);
                bot.swing(InteractionHand.MAIN_HAND, true);
                server.getPlayerList().broadcastAll(new ClientboundAnimatePacket(bot, 0));

                level.destroyBlock(pos, true);

                return "mined: " + blockId + " at (" + x + ", " + y + ", " + z + ")";
            }).get(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Throwable t) {
            return "error: breakBlock failed: " + t.getMessage();
        }
    }

    public synchronized String findBlocksJson(String query, int radius) {
        if (!isSpawned() || server == null) return "[]";
        int r = Math.max(1, Math.min(12, radius));
        String q = (query == null) ? "" : query.toLowerCase(Locale.ROOT).trim();

        try {
            return server.submit(() -> {
                if (!isSpawned()) return "[]";
                BlockPos center = bot.blockPosition();
                ServerLevel level = bot.serverLevel();

                record FoundBlock(String id, int x, int y, int z, double dist) {}
                List<FoundBlock> found = new ArrayList<>();

                int minY = Math.max(level.getMinBuildHeight(), center.getY() - Math.min(r, 6));
                int maxY = Math.min(level.getMaxBuildHeight(), center.getY() + Math.min(r, 6));

                for (int dy = minY - center.getY(); dy <= maxY - center.getY(); dy++) {
                    for (int dx = -r; dx <= r; dx++) {
                        for (int dz = -r; dz <= r; dz++) {
                            BlockPos p = center.offset(dx, dy, dz);
                            if (!level.hasChunkAt(p)) continue;
                            var state = level.getBlockState(p);
                            if (state.isAir()) continue;

                            var block = state.getBlock();
                            var key = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block);
                            String path = key.getPath();
                            String fullId = key.toString();

                            if (q.isEmpty() || path.contains(q) || fullId.contains(q)) {
                                double d = Math.sqrt(center.distSqr(p));
                                found.add(new FoundBlock(fullId, p.getX(), p.getY(), p.getZ(), d));
                                if (found.size() >= 40) break;
                            }
                        }
                        if (found.size() >= 40) break;
                    }
                    if (found.size() >= 40) break;
                }

                found.sort(Comparator.comparingDouble(FoundBlock::dist));
                if (found.size() > 20) found = found.subList(0, 20);

                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < found.size(); i++) {
                    if (i > 0) sb.append(",");
                    FoundBlock b = found.get(i);
                    sb.append("{\"id\":\"").append(b.id)
                      .append("\",\"x\":").append(b.x)
                      .append(",\"y\":").append(b.y)
                      .append(",\"z\":").append(b.z)
                      .append(",\"dist\":").append(String.format(Locale.ROOT, "%.1f", b.dist))
                      .append("}");
                }
                sb.append("]");
                return sb.toString();
            }).get(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Throwable t) {
            return "[]";
        }
    }

    public synchronized String placeBlock(int x, int y, int z, String blockId) {
        if (!isSpawned()) return "error: bot not spawned";
        BlockPos pos = new BlockPos(x, y, z);
        double distSq = bot.distanceToSqr(Vec3.atCenterOf(pos));
        if (distSq > 36.0) return "error: block too far (" + String.format(Locale.ROOT, "%.1fm", Math.sqrt(distSq)) + " > 6m)";

        ServerLevel level = bot.serverLevel();
        var currentState = level.getBlockState(pos);
        if (!currentState.isAir() && !currentState.canBeReplaced()) {
            return "error: position (x=" + x + ", y=" + y + ", z=" + z + ") is occupied by " + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(currentState.getBlock());
        }

        net.minecraft.world.item.Item itemToPlace = null;
        if (blockId != null && !blockId.isEmpty()) {
            var resLoc = net.minecraft.resources.ResourceLocation.parse(blockId);
            itemToPlace = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(resLoc);
            if (itemToPlace == net.minecraft.world.item.Items.AIR) {
                var block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(resLoc);
                if (block != net.minecraft.world.level.block.Blocks.AIR) {
                    itemToPlace = block.asItem();
                }
            }
        }

        int slotFound = -1;
        if (itemToPlace != null && itemToPlace != net.minecraft.world.item.Items.AIR) {
            for (int i = 0; i < bot.getInventory().getContainerSize(); i++) {
                ItemStack stack = bot.getInventory().getItem(i);
                if (!stack.isEmpty() && stack.is(itemToPlace)) {
                    slotFound = i;
                    break;
                }
            }
            if (slotFound == -1) {
                return "error: item " + blockId + " not found in bot inventory";
            }
        } else {
            ItemStack mainHand = bot.getMainHandItem();
            if (mainHand.isEmpty()) {
                for (int i = 0; i < bot.getInventory().getContainerSize(); i++) {
                    ItemStack stack = bot.getInventory().getItem(i);
                    if (!stack.isEmpty() && stack.getItem() instanceof net.minecraft.world.item.BlockItem) {
                        slotFound = i;
                        itemToPlace = stack.getItem();
                        break;
                    }
                }
                if (slotFound == -1) return "error: no block item in bot inventory";
            } else {
                itemToPlace = mainHand.getItem();
            }
        }

        final int consumeSlot = slotFound;
        final net.minecraft.world.level.block.Block blockToSet = (itemToPlace instanceof net.minecraft.world.item.BlockItem bi) ? bi.getBlock() : net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(itemToPlace));

        if (blockToSet == net.minecraft.world.level.block.Blocks.AIR) {
            return "error: item is not a placeable block";
        }

        server.execute(() -> {
            lookAt(x + 0.5, y + 0.5, z + 0.5, null, null);
            bot.swing(InteractionHand.MAIN_HAND, true);
            server.getPlayerList().broadcastAll(new ClientboundAnimatePacket(bot, 0));

            var defaultState = blockToSet.defaultBlockState();
            level.setBlock(pos, defaultState, 3);
            level.playSound(null, pos, defaultState.getSoundType().getPlaceSound(), net.minecraft.sounds.SoundSource.BLOCKS, 1.0f, 1.0f);

            if (consumeSlot >= 0) {
                bot.getInventory().getItem(consumeSlot).shrink(1);
            } else if (!bot.getMainHandItem().isEmpty()) {
                bot.getMainHandItem().shrink(1);
            }
        });

        String placedId = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(blockToSet).toString();
        return "placed: " + placedId + " at (" + x + ", " + y + ", " + z + ")";
    }

    public synchronized String fillArea(int x1, int y1, int z1, int x2, int y2, int z2, String blockId, boolean replaceAirOnly) {
        if (!isSpawned() || server == null) return "error: bot not spawned";
        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
        int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);

        int totalVolume = (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (totalVolume > 128) return "error: area too large (" + totalVolume + " blocks > max 128)";

        try {
            return server.submit(() -> {
                if (!isSpawned()) return "error: bot not spawned";
                ServerLevel level = bot.serverLevel();

                net.minecraft.world.item.Item itemToPlace = null;
                if (blockId != null && !blockId.isEmpty()) {
                    String normId = blockId.contains(":") ? blockId : "minecraft:" + blockId;
                    var resLoc = net.minecraft.resources.ResourceLocation.tryParse(normId);
                    if (resLoc != null) {
                        itemToPlace = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(resLoc);
                    }
                }

                int placed = 0;
                for (int y = minY; y <= maxY; y++) {
                    for (int x = minX; x <= maxX; x++) {
                        for (int z = minZ; z <= maxZ; z++) {
                            BlockPos p = new BlockPos(x, y, z);
                            var state = level.getBlockState(p);
                            if (replaceAirOnly && !state.isAir() && !state.canBeReplaced()) continue;

                            int foundSlot = -1;
                            net.minecraft.world.item.Item curItem = itemToPlace;
                            if (curItem != null && curItem != net.minecraft.world.item.Items.AIR) {
                                for (int i = 0; i < bot.getInventory().getContainerSize(); i++) {
                                    ItemStack st = bot.getInventory().getItem(i);
                                    if (!st.isEmpty() && st.is(curItem)) {
                                        foundSlot = i;
                                        break;
                                    }
                                }
                            } else {
                                for (int i = 0; i < bot.getInventory().getContainerSize(); i++) {
                                    ItemStack st = bot.getInventory().getItem(i);
                                    if (!st.isEmpty() && st.getItem() instanceof net.minecraft.world.item.BlockItem) {
                                        foundSlot = i;
                                        curItem = st.getItem();
                                        break;
                                    }
                                }
                            }

                            if (foundSlot == -1) {
                                return "placed " + placed + " blocks (ran out of building items in inventory)";
                            }

                            final net.minecraft.world.level.block.Block blockToSet = (curItem instanceof net.minecraft.world.item.BlockItem bi) ? bi.getBlock() : net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(curItem));
                            if (blockToSet == net.minecraft.world.level.block.Blocks.AIR) continue;

                            var defaultState = blockToSet.defaultBlockState();
                            level.setBlock(p, defaultState, 3);
                            bot.getInventory().getItem(foundSlot).shrink(1);
                            placed++;
                        }
                    }
                }

                if (placed > 0) {
                    bot.swing(InteractionHand.MAIN_HAND, true);
                    server.getPlayerList().broadcastAll(new ClientboundAnimatePacket(bot, 0));
                }

                return "placed " + placed + " blocks in area (" + minX + "," + minY + "," + minZ + " to " + maxX + "," + maxY + "," + maxZ + ")";
            }).get(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Throwable t) {
            return "error: fillArea failed: " + t.getMessage();
        }
    }

    public synchronized String containerInteract(int x, int y, int z, String action, String itemId, Integer count) {
        if (!isSpawned()) return "error: bot not spawned";
        BlockPos pos = new BlockPos(x, y, z);
        if (x == 0 && y == 0 && z == 0) {
            String chestsJson = findBlocksJson("chest", 6);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"x\":(-?\\d+),\"y\":(-?\\d+),\"z\":(-?\\d+)").matcher(chestsJson);
            if (m.find()) {
                pos = new BlockPos(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
            } else {
                return "error: no chest found within 6m radius";
            }
        }
        double distSq = bot.distanceToSqr(Vec3.atCenterOf(pos));
        if (distSq > 36.0) return "error: container too far (" + String.format(Locale.ROOT, "%.1fm", Math.sqrt(distSq)) + " > 6m)";

        String act = (action == null) ? "list" : action.toLowerCase(Locale.ROOT);
        int targetCount = (count == null || count <= 0) ? 64 : count;
        final BlockPos finalPos = pos;

        try {
            return server.submit(() -> {
                ServerLevel level = bot.serverLevel();
                var state = level.getBlockState(finalPos);
                net.minecraft.world.Container container = null;
                if (state.getBlock() instanceof net.minecraft.world.level.block.ChestBlock cb) {
                    container = net.minecraft.world.level.block.ChestBlock.getContainer(cb, state, level, finalPos, false);
                }
                if (container == null) {
                    net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(finalPos);
                    if (be instanceof net.minecraft.world.Container c) {
                        container = c;
                    }
                }
                if (container == null) {
                    return "error: block at (" + finalPos.getX() + ", " + finalPos.getY() + ", " + finalPos.getZ() + ") is not a chest or container";
                }

                switch (act) {
                    case "list" -> {
                        StringBuilder sb = new StringBuilder("[");
                        int added = 0;
                        for (int i = 0; i < container.getContainerSize(); i++) {
                            ItemStack stack = container.getItem(i);
                            if (!stack.isEmpty()) {
                                if (added > 0) sb.append(",");
                                String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                                sb.append("{\"slot\":").append(i)
                                  .append(",\"id\":\"").append(id)
                                  .append("\",\"count\":").append(stack.getCount())
                                  .append("}");
                                added++;
                            }
                        }
                        sb.append("]");
                        return sb.toString();
                    }
                    case "deposit" -> {
                        int transferred = 0;
                        boolean depositAll = (itemId == null || itemId.isEmpty() || "all".equalsIgnoreCase(itemId));
                        String normId = (!depositAll && !itemId.contains(":")) ? "minecraft:" + itemId : itemId;
                        var targetItem = depositAll ? null : net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.tryParse(normId));

                        for (int i = 0; i < bot.getInventory().getContainerSize() && transferred < targetCount; i++) {
                            ItemStack botStack = bot.getInventory().getItem(i);
                            if (botStack.isEmpty()) continue;
                            if (!depositAll && !botStack.is(targetItem)) continue;

                            for (int cSlot = 0; cSlot < container.getContainerSize() && !botStack.isEmpty(); cSlot++) {
                                ItemStack cStack = container.getItem(cSlot);
                                if (cStack.isEmpty()) {
                                    int toMove = Math.min(botStack.getCount(), targetCount - transferred);
                                    container.setItem(cSlot, botStack.split(toMove));
                                    transferred += toMove;
                                } else if (ItemStack.isSameItemSameComponents(botStack, cStack)) {
                                    int space = cStack.getMaxStackSize() - cStack.getCount();
                                    if (space > 0) {
                                        int toMove = Math.min(Math.min(botStack.getCount(), space), targetCount - transferred);
                                        cStack.grow(toMove);
                                        botStack.shrink(toMove);
                                        transferred += toMove;
                                    }
                                }
                            }
                        }
                        container.setChanged();
                        return "deposited " + transferred + " items to container at (" + x + ", " + y + ", " + z + ")";
                    }
                    case "withdraw" -> {
                        int transferred = 0;
                        boolean withdrawAll = (itemId == null || itemId.isEmpty() || "all".equalsIgnoreCase(itemId));
                        String normId = (!withdrawAll && !itemId.contains(":")) ? "minecraft:" + itemId : itemId;
                        var targetItem = withdrawAll ? null : net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.tryParse(normId));

                        for (int cSlot = 0; cSlot < container.getContainerSize() && transferred < targetCount; cSlot++) {
                            ItemStack cStack = container.getItem(cSlot);
                            if (cStack.isEmpty()) continue;
                            if (!withdrawAll && !cStack.is(targetItem)) continue;

                            int toMove = Math.min(cStack.getCount(), targetCount - transferred);
                            ItemStack taken = cStack.split(toMove);
                            if (cStack.isEmpty()) container.setItem(cSlot, ItemStack.EMPTY);

                            bot.getInventory().add(taken);
                            transferred += toMove;
                        }
                        container.setChanged();
                        return "withdrew " + transferred + " items from container at (" + x + ", " + y + ", " + z + ")";
                    }
                    default -> { return "error: unknown action " + act; }
                }
            }).get(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Throwable t) {
            return "error: container interact failed: " + t.getMessage();
        }
    }

    private String currentStatus = "idle";

    public synchronized String setStatusIndicator(String status, String customPrefix, String customSuffix) {
        if (!isSpawned() || server == null) return "error: bot not spawned";
        this.currentStatus = (status == null || status.isBlank()) ? "idle" : status.toLowerCase(Locale.ROOT);

        server.execute(() -> {
            try {
                Scoreboard scoreboard = server.getScoreboard();
                PlayerTeam team = scoreboard.getPlayerTeam("unai_bot");
                boolean isNew = false;
                if (team == null) {
                    team = scoreboard.addPlayerTeam("unai_bot");
                    isNew = true;
                }

                if (!team.getPlayers().contains(bot.getScoreboardName())) {
                    scoreboard.addPlayerToTeam(bot.getScoreboardName(), team);
                    server.getPlayerList().broadcastAll(ClientboundSetPlayerTeamPacket.createPlayerPacket(team, bot.getScoreboardName(), ClientboundSetPlayerTeamPacket.Action.ADD));
                }

                String prefix = "";
                String suffix = "";

                if (customPrefix != null && !customPrefix.isEmpty()) {
                    prefix = customPrefix;
                } else {
                    prefix = switch (currentStatus) {
                        case "thinking", "thought" -> "§e[💭] ";
                        case "mining", "breaking" -> "§6[⛏️] ";
                        case "building", "placing" -> "§a[🔨] ";
                        case "combat", "fight", "attack" -> "§c[⚔️] ";
                        case "navigating", "walking", "running" -> "§b[🏃] ";
                        case "crafting" -> "§d[📦] ";
                        case "speaking", "chatting" -> "§f[💬] ";
                        case "afk", "idle" -> "§7[💤] ";
                        default -> "§f[" + currentStatus + "] ";
                    };
                }

                if (customSuffix != null && !customSuffix.isEmpty()) {
                    suffix = customSuffix;
                }

                team.setPlayerPrefix(Component.literal(prefix));
                team.setPlayerSuffix(Component.literal(suffix));
                server.getPlayerList().broadcastAll(ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team, isNew));

                bot.setCustomName(Component.literal(prefix + bot.getName().getString() + suffix));
                server.getPlayerList().broadcastAll(new ClientboundPlayerInfoUpdatePacket(
                        EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME),
                        List.of(bot)
                ));
            } catch (Throwable t) {
                LOGGER.warn("[UnAI-Bridge] Status indicator error: " + t.getMessage());
            }
        });

        return "status_indicator: " + currentStatus;
    }

    public synchronized String setGuardMode(boolean enabled, String targetPlayer) {
        if (!isSpawned()) return "error: bot not spawned";
        this.isGuardMode = enabled;
        this.guardTargetPlayer = targetPlayer;
        return "guard_mode: " + (enabled ? "enabled (target: " + (targetPlayer == null || targetPlayer.isEmpty() ? "bot" : targetPlayer) + ")" : "disabled");
    }

    public synchronized String autoChop(int count) {
        if (!isSpawned()) return "error: bot not spawned";
        int targetLogs = Math.max(1, Math.min(32, count));
        new Thread(() -> {
            try {
                int chopped = 0;
                for (int attempt = 0; attempt < targetLogs * 2 && chopped < targetLogs; attempt++) {
                    if (!isSpawned()) break;
                    String json = findBlocksJson("log", 16);
                    if (json.equals("[]")) break;

                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{\"id\":\"[^\"]+\",\"x\":(-?\\d+),\"y\":(-?\\d+),\"z\":(-?\\d+),\"dist\":([0-9.]+)\\}").matcher(json);
                    int bestX = 0, bestY = 999, bestZ = 0;
                    boolean found = false;
                    while (m.find()) {
                        int x = Integer.parseInt(m.group(1));
                        int y = Integer.parseInt(m.group(2));
                        int z = Integer.parseInt(m.group(3));
                        if (!found || y < bestY) {
                            bestX = x;
                            bestY = y;
                            bestZ = z;
                            found = true;
                        }
                    }
                    if (!found) break;

                    double dist = Math.sqrt(bot.distanceToSqr(bestX + 0.5, bestY + 0.5, bestZ + 0.5));
                    if (dist > 3.5) {
                        navigateTo(bestX + 0.5, bestY, bestZ + 0.5, 2.0f);
                        int navWait = 0;
                        while (isNavigating && navWait < 40) {
                            Thread.sleep(200);
                            navWait++;
                        }
                    }

                    String breakRes = breakBlock(bestX, bestY, bestZ);
                    if (breakRes.startsWith("mined:")) {
                        chopped++;
                        Thread.sleep(300);
                    }
                }
            } catch (Throwable t) {
                LOGGER.warn("[UnAI-Bridge] AutoChop error: " + t.getMessage());
            }
        }).start();

        return "auto_chop started: target " + targetLogs + " logs";
    }

    public synchronized String setChunkLoaderRadius(int radius) {
        if (!isSpawned()) return "error: bot not spawned";
        int clamped = Math.max(0, Math.min(8, radius));
        this.chunkLoadRadius = clamped;
        this.lastBotChunkPos = null;
        updateChunkLoading();

        int totalChunks = (clamped == 0) ? 0 : (2 * clamped + 1) * (2 * clamped + 1);
        return "chunk_loader: radius=" + clamped + " (" + totalChunks + " chunks in area, " + loadedChunks.size() + " forced)";
    }

    public synchronized String getChunkLoaderStatus() {
        int r = chunkLoadRadius;
        int totalChunks = (r == 0) ? 0 : (2 * r + 1) * (2 * r + 1);
        ChunkPos cp = isSpawned() ? bot.chunkPosition() : null;
        return "{\"enabled\":" + (r > 0) + ",\"radius\":" + r + ",\"total_chunks\":" + totalChunks + ",\"active_forced\":" + loadedChunks.size() + (cp != null ? ",\"center_chunk\":[" + cp.x + "," + cp.z + "]" : "") + "}";
    }

    public synchronized String setAutonomousMode(boolean enabled, Integer radius) {
        if (!isSpawned()) return "error: bot not spawned";
        this.autonomousMode = enabled;
        if (enabled && tetherHomePos == null) {
            this.tetherHomePos = bot.blockPosition();
        }
        return "autonomous_mode: " + (enabled ? "enabled (tether home: " + tetherHomePos.toShortString() + ")" : "disabled");
    }

    public synchronized String getAutonomousStatus() {
        return "{\"enabled\":" + autonomousMode + ",\"home\":" + (tetherHomePos != null ? "[\"" + tetherHomePos.toShortString() + "\"]" : "null") + "}";
    }

    public void clearLoadedChunks() {
        if (loadedChunks.isEmpty()) return;
        if (bot != null && bot.serverLevel() != null) {
            ServerLevel level = bot.serverLevel();
            for (ChunkPos cp : new HashSet<>(loadedChunks)) {
                server.execute(() -> level.setChunkForced(cp.x, cp.z, false));
            }
        }
        loadedChunks.clear();
        lastBotChunkPos = null;
    }

    public void updateChunkLoading() {
        if (!isSpawned() || chunkLoadRadius <= 0) {
            clearLoadedChunks();
            return;
        }

        ChunkPos currentChunk = bot.chunkPosition();
        if (currentChunk.equals(lastBotChunkPos) && !loadedChunks.isEmpty()) return;
        lastBotChunkPos = currentChunk;

        ServerLevel level = bot.serverLevel();
        Set<ChunkPos> newChunks = new HashSet<>();
        for (int dx = -chunkLoadRadius; dx <= chunkLoadRadius; dx++) {
            for (int dz = -chunkLoadRadius; dz <= chunkLoadRadius; dz++) {
                newChunks.add(new ChunkPos(currentChunk.x + dx, currentChunk.z + dz));
            }
        }

        for (ChunkPos cp : new HashSet<>(loadedChunks)) {
            if (!newChunks.contains(cp)) {
                server.execute(() -> level.setChunkForced(cp.x, cp.z, false));
                loadedChunks.remove(cp);
            }
        }

        for (ChunkPos cp : newChunks) {
            if (!loadedChunks.contains(cp)) {
                server.execute(() -> level.setChunkForced(cp.x, cp.z, true));
                loadedChunks.add(cp);
            }
        }
    }

    public synchronized String equip(String slot, String itemId) {
        if (!isSpawned()) return "error: bot not spawned";
        var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                net.minecraft.resources.ResourceLocation.parse(itemId));
        if (item == net.minecraft.world.item.Items.AIR && !"air".equals(itemId) && !"minecraft:air".equals(itemId)) return "unknown_item";
        ItemStack stack = (item == net.minecraft.world.item.Items.AIR) ? ItemStack.EMPTY : new ItemStack(item);
        server.execute(() -> {
            EquipmentSlot eqSlot = switch (slot.toLowerCase()) {
                case "head", "helmet" -> EquipmentSlot.HEAD;
                case "chest", "chestplate" -> EquipmentSlot.CHEST;
                case "legs", "leggings" -> EquipmentSlot.LEGS;
                case "feet", "boots" -> EquipmentSlot.FEET;
                case "offhand" -> EquipmentSlot.OFFHAND;
                default -> EquipmentSlot.MAINHAND;
            };
            bot.setItemSlot(eqSlot, stack);

            // Broadcast official equipment packet to all players
            List<com.mojang.datafixers.util.Pair<EquipmentSlot, ItemStack>> list = new ArrayList<>();
            for (EquipmentSlot es : EquipmentSlot.values()) {
                list.add(com.mojang.datafixers.util.Pair.of(es, bot.getItemBySlot(es)));
            }
            server.getPlayerList().broadcastAll(new net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket(bot.getId(), list));
        });
        return "ok";
    }

    public synchronized String navigateTo(double x, double y, double z, float radius) {
        if (!isSpawned() || server == null) return "error: bot not spawned";
        try {
            return server.submit(() -> {
                if (!isSpawned()) return "error: bot not spawned";
                ServerLevel level = bot.serverLevel();
                BlockPos start = bot.blockPosition();
                BlockPos end = new BlockPos((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));

                AStarPathfinder.PathOptions opt = new AStarPathfinder.PathOptions();
                opt.targetRadius = radius;
                opt.maxIterations = 600;

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
            }).get(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Throwable t) {
            return "error: navigate failed: " + t.getMessage();
        }
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
     * Called on each server tick to process navigation, physics and perception.
     */
    public void tick() {
        if (bot == null || bot.isRemoved()) {
            bot = null;
            return;
        }

        // Tick Perception Engine buffer
        PerceptionEngine.getInstance().tick(bot);

        // Update Chunk Loader
        if (bot.tickCount % 20 == 0) {
            updateChunkLoading();
        }

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

        // Apply physical movement and gravity tick
        Vec3 dm = bot.getDeltaMovement();
        if (!bot.onGround() || dm.y > 0 || Math.abs(dm.x) > 0.001 || Math.abs(dm.z) > 0.001) {
            bot.move(MoverType.SELF, dm);
            double newY = dm.y;
            if (!bot.onGround()) {
                newY = (newY - 0.08) * 0.98; // Gravity & air drag
            } else if (newY < 0) {
                newY = 0;
            }
            bot.setDeltaMovement(dm.x * 0.85, newY, dm.z * 0.85);
            bot.hurtMarked = true;
        }

        // Pick up dropped items nearby (1.8m radius)
        if (bot.tickCount % 2 == 0) {
            try {
                List<net.minecraft.world.entity.item.ItemEntity> items = bot.serverLevel().getEntitiesOfClass(
                        net.minecraft.world.entity.item.ItemEntity.class,
                        bot.getBoundingBox().inflate(1.8, 1.0, 1.8),
                        item -> !item.hasPickUpDelay() && item.isAlive()
                );
                for (net.minecraft.world.entity.item.ItemEntity item : items) {
                    ItemStack st = item.getItem();
                    int count = st.getCount();
                    if (bot.getInventory().add(st)) {
                        bot.take(item, count);
                        bot.serverLevel().playSound(null, bot.getX(), bot.getY(), bot.getZ(),
                                net.minecraft.sounds.SoundEvents.ITEM_PICKUP,
                                net.minecraft.sounds.SoundSource.PLAYERS,
                                0.2F, (float)((Math.random() - Math.random()) * 0.2 + 1.0));
                        if (st.isEmpty()) {
                            item.discard();
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        // Guard mode logic (runs every 8 ticks ~0.4s)
        if (isGuardMode && bot.tickCount % 8 == 0) {
            try {
                if (bot.getHealth() < 10.0f) {
                    for (int i = 0; i < bot.getInventory().getContainerSize(); i++) {
                        ItemStack stack = bot.getInventory().getItem(i);
                        if (!stack.isEmpty() && stack.has(net.minecraft.core.component.DataComponents.FOOD)) {
                            bot.eat(bot.serverLevel(), stack.split(1));
                            bot.serverLevel().playSound(null, bot.getX(), bot.getY(), bot.getZ(),
                                    net.minecraft.sounds.SoundEvents.GENERIC_EAT,
                                    net.minecraft.sounds.SoundSource.PLAYERS, 0.5f, 1.0f);
                            break;
                        }
                    }
                }

                Vec3 scanCenter = bot.position();
                if (guardTargetPlayer != null && !guardTargetPlayer.isEmpty()) {
                    ServerPlayer owner = server.getPlayerList().getPlayerByName(guardTargetPlayer);
                    if (owner != null && owner.level() == bot.level()) {
                        scanCenter = owner.position();
                    }
                }

                List<Monster> monsters = bot.serverLevel().getEntitiesOfClass(
                        Monster.class,
                        new AABB(scanCenter.x - 10, scanCenter.y - 4, scanCenter.z - 10, scanCenter.x + 10, scanCenter.y + 4, scanCenter.z + 10),
                        m -> m.isAlive() && !m.isSpectator()
                             && !(m instanceof net.minecraft.world.entity.monster.Creeper)
                             && (!(m instanceof net.minecraft.world.entity.monster.EnderMan em) || em.isCreepy() || em.getTarget() != null)
                );

                if (!monsters.isEmpty()) {
                    monsters.sort(Comparator.comparingDouble(m -> m.distanceToSqr(bot)));
                    Monster target = monsters.get(0);
                    double dist = bot.distanceTo(target);

                    lookAt(target.getX(), target.getEyeY(), target.getZ(), null, null);

                    if (dist <= 3.5) {
                        bot.swing(InteractionHand.MAIN_HAND, true);
                        server.getPlayerList().broadcastAll(new ClientboundAnimatePacket(bot, 0));
                        bot.attack(target);
                    } else if (!isNavigating) {
                        navigateTo(target.getX(), target.getY(), target.getZ(), 2.0f);
                    }
                }
            } catch (Throwable t) {
                LOGGER.warn("[UnAI-Bridge] Guard tick error: " + t.getMessage());
            }
        }

        // Attentive player tracking
        if (!isNavigating && !isGuardMode) {
            ServerPlayer nearbyPlayer = null;
            double closestDistSq = 36.0; // within 6 blocks
            for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
                if (sp != bot && sp.level() == bot.level()) {
                    double d = sp.distanceToSqr(bot);
                    if (d < closestDistSq) {
                        closestDistSq = d;
                        nearbyPlayer = sp;
                    }
                }
            }

            if (nearbyPlayer != null) {
                lookAt(nearbyPlayer.getX(), nearbyPlayer.getEyeY(), nearbyPlayer.getZ(), null, null);
            }
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
                double speed = 0.28;
                Vec3 vel = new Vec3(dx / hDist * speed, bot.getDeltaMovement().y, dz / hDist * speed);

                // Jump if step up
                if (dy > 0.5 && bot.onGround()) {
                    vel = new Vec3(vel.x, 0.45, vel.z);
                    server.getPlayerList().broadcastAll(new ClientboundSetEntityMotionPacket(bot));
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
