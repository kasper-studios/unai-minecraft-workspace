package xyz.kasperstudios.unai.bridge.forge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Perception Engine for UnAI Minecraft Fake Player.
 * - 3D First-Person ASCII Raymarcher
 * - 2D Heading-Rotated Dynamic Radar with 8-way gaze arrows & LOS
 * - Precise Crosshair Target Inspector
 * - 60-Frame Perception Ring Buffer
 */
public class PerceptionEngine {

    public static class FrameSnapshot {
        public final long timestamp;
        public final double x, y, z;
        public final float yaw, pitch;
        public final String view3D;
        public final String radar2D;
        public final String crosshairTarget;
        public final List<String> poiAlerts;

        public FrameSnapshot(long timestamp, double x, double y, double z, float yaw, float pitch,
                             String view3D, String radar2D, String crosshairTarget, List<String> poiAlerts) {
            this.timestamp = timestamp;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
            this.view3D = view3D;
            this.radar2D = radar2D;
            this.crosshairTarget = crosshairTarget;
            this.poiAlerts = poiAlerts;
        }
    }

    private static final int BUFFER_CAPACITY = 60;
    private final ConcurrentLinkedDeque<FrameSnapshot> frameBuffer = new ConcurrentLinkedDeque<>();
    private long lastFrameTick = 0;

    private static final PerceptionEngine INSTANCE = new PerceptionEngine();

    public static PerceptionEngine getInstance() {
        return INSTANCE;
    }

    private PerceptionEngine() {}

    /**
     * Ticks the perception ring buffer at ~5 FPS (every 4 server ticks).
     */
    public void tick(ServerPlayer bot) {
        if (bot == null || bot.isRemoved() || bot.serverLevel() == null) return;
        long gameTime = bot.serverLevel().getGameTime();
        if (gameTime - lastFrameTick < 4) return;
        lastFrameTick = gameTime;

        try {
            double x = bot.getX(), y = bot.getY(), z = bot.getZ();
            float yaw = bot.getYRot(), pitch = bot.getXRot();

            String target = getTargetJson(bot);
            List<String> pois = detectPOIs(bot);

            FrameSnapshot snap = new FrameSnapshot(
                    System.currentTimeMillis(),
                    x, y, z, yaw, pitch,
                    render3DView(bot, 28, 14, 70),
                    render2DRadar(bot, 8),
                    target,
                    pois
            );

            frameBuffer.addLast(snap);
            while (frameBuffer.size() > BUFFER_CAPACITY) {
                frameBuffer.removeFirst();
            }
        } catch (Throwable ignored) {}
    }

    public List<FrameSnapshot> getRecentFrames(int limit) {
        List<FrameSnapshot> list = new ArrayList<>(frameBuffer);
        if (list.size() <= limit) return list;
        return list.subList(list.size() - limit, list.size());
    }

    /**
     * 3D First-Person ASCII Raymarcher
     */
    public String render3DView(ServerPlayer bot, int width, int height, float fovDeg) {
        if (bot == null || bot.serverLevel() == null) return "Bot not spawned";
        ServerLevel level = bot.serverLevel();
        Vec3 eye = bot.getEyePosition();
        float yaw = bot.getYRot();
        float pitch = bot.getXRot();

        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);

        // Basis vectors
        Vec3 forward = new Vec3(-Math.sin(yawRad) * Math.cos(pitchRad), -Math.sin(pitchRad), Math.cos(yawRad) * Math.cos(pitchRad)).normalize();
        Vec3 right = new Vec3(Math.cos(yawRad), 0, Math.sin(yawRad)).normalize();
        Vec3 up = right.cross(forward).normalize();

        double aspect = (double) width / height * 0.55; // Character aspect correction
        double halfFovTan = Math.tan(Math.toRadians(fovDeg * 0.5));

        List<Entity> nearbyEntities = level.getEntities(bot, bot.getBoundingBox().inflate(24.0));

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("=== 3D FIRST-PERSON VIEW [Pitch: %.1f° | Yaw: %.1f° | Pos: %.1f, %.1f, %.1f] ===\n", pitch, yaw, eye.x, eye.y, eye.z));

        int midX = width / 2;
        int midY = height / 2;
        String crosshairHit = "Air / Sky";

        for (int y = 0; y < height; y++) {
            sb.append("|");
            double screenY = (1.0 - (2.0 * y) / (height - 1)) * halfFovTan;

            for (int x = 0; x < width; x++) {
                double screenX = ((2.0 * x) / (width - 1) - 1.0) * halfFovTan * aspect;

                Vec3 rayDir = forward.add(right.scale(screenX)).add(up.scale(screenY)).normalize();
                Vec3 rayEnd = eye.add(rayDir.scale(28.0));

                // 1. Check entity intersection first
                Entity hitEntity = null;
                double entityDist = Double.MAX_VALUE;
                for (Entity e : nearbyEntities) {
                    if (e == bot) continue;
                    AABB box = e.getBoundingBox().inflate(0.15);
                    Optional<Vec3> hitOpt = box.clip(eye, rayEnd);
                    if (hitOpt.isPresent()) {
                        double d = eye.distanceTo(hitOpt.get());
                        if (d < entityDist) {
                            entityDist = d;
                            hitEntity = e;
                        }
                    }
                }

                // 2. Block raycast
                BlockHitResult blockHit = level.clip(new ClipContext(eye, rayEnd, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, bot));
                double blockDist = (blockHit.getType() != HitResult.Type.MISS) ? eye.distanceTo(blockHit.getLocation()) : Double.MAX_VALUE;

                char c;
                if (hitEntity != null && entityDist < blockDist) {
                    if (hitEntity instanceof Player) c = '@';
                    else if (hitEntity instanceof Monster) c = 'M';
                    else c = 'E';
                    if (x == midX && y == midY) {
                        crosshairHit = hitEntity.getName().getString() + " (Dist: " + String.format("%.1fm", entityDist) + ")";
                    }
                } else if (blockHit.getType() != HitResult.Type.MISS) {
                    BlockState state = level.getBlockState(blockHit.getBlockPos());
                    if (state.getFluidState().is(FluidTags.WATER)) c = '~';
                    else if (state.getFluidState().is(FluidTags.LAVA)) c = '!';
                    else if (blockDist < 2.5) c = '█';
                    else if (blockDist < 6.0) c = '#';
                    else if (blockDist < 12.0) c = ':';
                    else c = '.';

                    if (x == midX && y == midY) {
                        crosshairHit = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath() + " (Dist: " + String.format("%.1fm", blockDist) + ")";
                    }
                } else {
                    c = ' ';
                }

                // Center crosshair mark if empty
                if (x == midX && y == midY && c == ' ') c = '+';

                sb.append(c);
            }
            sb.append("|\n");
        }
        sb.append("=================================================================\n");
        sb.append("[Crosshair Center]: ").append(crosshairHit);
        return sb.toString();
    }

    /**
     * 2D Dynamically Rotated Heading Radar
     */
    public String render2DRadar(ServerPlayer bot, int radius) {
        if (bot == null || bot.serverLevel() == null) return "Bot not spawned";
        ServerLevel level = bot.serverLevel();
        Vec3 botPos = bot.position();
        Vec3 eye = bot.getEyePosition();
        float botYaw = bot.getYRot();
        double botYawRad = Math.toRadians(-botYaw);

        double cos = Math.cos(botYawRad);
        double sin = Math.sin(botYawRad);

        int size = radius * 2 + 1;
        char[][] grid = new char[size][size];
        for (char[] row : grid) Arrays.fill(row, ' ');

        List<Entity> nearbyEntities = level.getEntities(bot, bot.getBoundingBox().inflate(radius * 1.5));
        List<String> entityListLegend = new ArrayList<>();

        for (int lz = -radius; lz <= radius; lz++) {
            for (int lx = -radius; lx <= radius; lx++) {
                if (lx * lx + lz * lz > radius * radius) continue;

                // Local coords (lx = right, lz = forward/UP) -> world coords
                // wx = botX + lx * cos - lz * sin
                // wz = botZ + lx * sin + lz * cos
                double wx = botPos.x + (lx * cos - lz * sin);
                double wz = botPos.z + (lx * sin + lz * cos);

                int gridRow = radius - lz; // lz forward is top row
                int gridCol = radius + lx;

                if (lx == 0 && lz == 0) {
                    grid[gridRow][gridCol] = '▲';
                    continue;
                }

                BlockPos groundPos = new BlockPos((int) Math.floor(wx), (int) Math.floor(botPos.y), (int) Math.floor(wz));
                BlockState state = level.getBlockState(groundPos);
                BlockState stateBelow = level.getBlockState(groundPos.below());
                BlockState stateAbove = level.getBlockState(groundPos.above());

                // Line-of-sight check
                Vec3 targetVec = new Vec3(wx + 0.5, groundPos.getY() + 0.5, wz + 0.5);
                BlockHitResult hit = level.clip(new ClipContext(eye, targetVec, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, bot));
                boolean hasLOS = (hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceTo(targetVec) < 1.0);

                if (!hasLOS) {
                    grid[gridRow][gridCol] = '?'; // Fog of war
                } else if (!state.getCollisionShape(level, groundPos).isEmpty() || !stateAbove.getCollisionShape(level, groundPos.above()).isEmpty()) {
                    grid[gridRow][gridCol] = '#'; // Wall
                } else if (state.getFluidState().is(FluidTags.WATER) || stateBelow.getFluidState().is(FluidTags.WATER)) {
                    grid[gridRow][gridCol] = '~'; // Water
                } else if (state.getFluidState().is(FluidTags.LAVA) || stateBelow.getFluidState().is(FluidTags.LAVA)) {
                    grid[gridRow][gridCol] = '!'; // Lava
                } else {
                    grid[gridRow][gridCol] = '.'; // Walkable floor
                }
            }
        }

        // Overlay entities with 8-way gaze arrows
        for (Entity e : nearbyEntities) {
            if (e == bot) continue;
            double dx = e.getX() - botPos.x;
            double dz = e.getZ() - botPos.z;

            // Rotate into bot camera space
            double lx = dx * cos + dz * sin;
            double lz = -dx * sin + dz * cos;

            int gridRow = (int) Math.round(radius - lz);
            int gridCol = (int) Math.round(radius + lx);

            if (gridRow >= 0 && gridRow < size && gridCol >= 0 && gridCol < size) {
                float relYaw = normAngle(e.getYRot() - botYaw);
                char arrow = getGazeArrow(relYaw);
                char mark = (e instanceof Player) ? e.getName().getString().charAt(0) : 'M';
                grid[gridRow][gridCol] = mark;

                double dist = bot.distanceTo(e);
                entityListLegend.add(String.format("[%c%c] %s (%.1fm | %s | HP: %.0f)",
                        mark, arrow, e.getName().getString(), dist,
                        (e.getY() > botPos.y + 1 ? "above" : e.getY() < botPos.y - 1 ? "below" : "level"),
                        (e instanceof LivingEntity le ? le.getHealth() : 0.0)));
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("=== POV RADAR [Heading: ↑ FORWARD | Pos: %.1f, %.1f, %.1f | Radius: %dm] ===\n", botPos.x, botPos.y, botPos.z, radius));
        sb.append("                  [ ВПЕРЁД (Взгляд бота) ]\n");

        for (int r = 0; r < size; r++) {
            sb.append("         ");
            for (int c = 0; c < size; c++) {
                sb.append(grid[r][c]).append(" ");
            }
            sb.append("\n");
        }
        sb.append("                  [ СЗАДИ (За спиной) ]\n");
        if (!entityListLegend.isEmpty()) {
            sb.append("--- СУЩНОСТИ РЯДОМ ---\n");
            for (String item : entityListLegend) {
                sb.append(item).append("\n");
            }
        }

        return sb.toString();
    }

    private static char getGazeArrow(float relAngle) {
        if (relAngle >= -22.5f && relAngle < 22.5f) return '↑';
        if (relAngle >= 22.5f && relAngle < 67.5f) return '↗';
        if (relAngle >= 67.5f && relAngle < 112.5f) return '→';
        if (relAngle >= 112.5f && relAngle < 157.5f) return '↘';
        if (relAngle >= 157.5f || relAngle < -157.5f) return '↓';
        if (relAngle >= -157.5f && relAngle < -112.5f) return '↙';
        if (relAngle >= -112.5f && relAngle < -67.5f) return '←';
        return '↖';
    }

    private static float normAngle(float a) {
        a = a % 360f;
        if (a > 180f) a -= 360f;
        if (a < -180f) a += 360f;
        return a;
    }

    public String getTargetJson(ServerPlayer bot) {
        if (bot == null || bot.serverLevel() == null) return "{\"type\":\"none\"}";
        ServerLevel level = bot.serverLevel();
        Vec3 eye = bot.getEyePosition();
        Vec3 look = bot.getLookAngle();
        Vec3 reach = eye.add(look.scale(32.0));

        // Entity check
        List<Entity> list = level.getEntities(bot, bot.getBoundingBox().inflate(32.0));
        Entity nearestEntity = null;
        double nearestDist = Double.MAX_VALUE;

        for (Entity e : list) {
            if (e == bot) continue;
            AABB box = e.getBoundingBox().inflate(0.3);
            Optional<Vec3> hit = box.clip(eye, reach);
            if (hit.isPresent()) {
                double d = eye.distanceTo(hit.get());
                if (d < nearestDist) {
                    nearestDist = d;
                    nearestEntity = e;
                }
            }
        }

        BlockHitResult blockHit = level.clip(new ClipContext(eye, reach, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, bot));
        double blockDist = (blockHit.getType() != HitResult.Type.MISS) ? eye.distanceTo(blockHit.getLocation()) : Double.MAX_VALUE;

        if (nearestEntity != null && nearestDist < blockDist) {
            return String.format("{\"type\":\"entity\",\"name\":\"%s\",\"id\":\"%s\",\"dist\":%.2f,\"health\":%.1f,\"x\":%.2f,\"y\":%.2f,\"z\":%.2f}",
                    escapeJson(nearestEntity.getName().getString()),
                    BuiltInRegistries.ENTITY_TYPE.getKey(nearestEntity.getType()),
                    nearestDist,
                    (nearestEntity instanceof LivingEntity le ? le.getHealth() : 0.0),
                    nearestEntity.getX(), nearestEntity.getY(), nearestEntity.getZ());
        } else if (blockHit.getType() != HitResult.Type.MISS) {
            BlockPos bp = blockHit.getBlockPos();
            BlockState state = level.getBlockState(bp);
            return String.format("{\"type\":\"block\",\"id\":\"%s\",\"dist\":%.2f,\"x\":%d,\"y\":%d,\"z\":%d}",
                    BuiltInRegistries.BLOCK.getKey(state.getBlock()),
                    blockDist, bp.getX(), bp.getY(), bp.getZ());
        }

        return "{\"type\":\"miss\"}";
    }

    private List<String> detectPOIs(ServerPlayer bot) {
        List<String> alerts = new ArrayList<>();
        if (bot == null || bot.serverLevel() == null) return alerts;
        ServerLevel level = bot.serverLevel();
        BlockPos pos = bot.blockPosition();

        int r = 12;
        for (int dx = -r; dx <= r; dx += 2) {
            for (int dz = -r; dz <= r; dz += 2) {
                for (int dy = -4; dy <= 4; dy += 2) {
                    BlockPos p = pos.offset(dx, dy, dz);
                    BlockState s = level.getBlockState(p);
                    if (s.is(Blocks.DIAMOND_ORE) || s.is(Blocks.DEEPSLATE_DIAMOND_ORE)) {
                        alerts.add(String.format("Diamond Ore detected at %d, %d, %d", p.getX(), p.getY(), p.getZ()));
                    } else if (s.is(Blocks.SPAWNER)) {
                        alerts.add(String.format("Monster Spawner detected at %d, %d, %d", p.getX(), p.getY(), p.getZ()));
                    }
                }
            }
        }
        return alerts;
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
