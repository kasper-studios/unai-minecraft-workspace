package xyz.kasperstudios.unai.bridge.script.objects;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import xyz.kasperstudios.unai.bridge.forge.FakePlayerManager;
import xyz.kasperstudios.unai.bridge.script.Callable;
import xyz.kasperstudios.unai.bridge.script.Value;

import java.util.*;

public class WorldObject extends Value {
    public WorldObject() {
        super(createMethods());
    }

    private static Map<String, Value> createMethods() {
        Map<String, Value> methods = new HashMap<>();

        methods.put("getBlock", Value.of((Callable) (ctx, args) -> {
            if (args.size() < 3) return Value.NULL;
            int x = args.get(0).asInt();
            int y = args.get(1).asInt();
            int z = args.get(2).asInt();
            ServerPlayer bot = FakePlayerManager.getInstance().getBot();
            if (bot == null) return Value.NULL;
            BlockState state = bot.serverLevel().getBlockState(new BlockPos(x, y, z));
            return Value.of(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        }));

        methods.put("findBlocks", Value.of((Callable) (ctx, args) -> {
            if (args.isEmpty()) return Value.of(Collections.emptyList());
            List<String> targets = new ArrayList<>();
            if (args.get(0).isList()) {
                for (Value v : args.get(0).asList()) {
                    targets.add(v.asString());
                }
            } else {
                targets.add(args.get(0).asString());
            }
            int radius = args.size() > 1 ? args.get(1).asInt() : 16;
            ServerPlayer bot = FakePlayerManager.getInstance().getBot();
            if (bot == null) return Value.of(Collections.emptyList());
            ServerLevel level = bot.serverLevel();
            BlockPos bpos = bot.blockPosition();

            List<Value> results = new ArrayList<>();
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        BlockPos check = bpos.offset(dx, dy, dz);
                        BlockState state = level.getBlockState(check);
                        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                        for (String t : targets) {
                            if (id.contains(t)) {
                                Map<String, Value> item = new HashMap<>();
                                item.put("x", Value.of(check.getX()));
                                item.put("y", Value.of(check.getY()));
                                item.put("z", Value.of(check.getZ()));
                                item.put("name", Value.of(id));
                                results.add(Value.of(item));
                                break;
                            }
                        }
                    }
                }
            }
            return Value.of(results);
        }));

        methods.put("findEntities", Value.of((Callable) (ctx, args) -> {
            int radius = args.size() > 1 ? args.get(1).asInt() : 32;
            ServerPlayer bot = FakePlayerManager.getInstance().getBot();
            if (bot == null) return Value.of(Collections.emptyList());
            ServerLevel level = bot.serverLevel();
            AABB aabb = bot.getBoundingBox().inflate(radius);
            List<Entity> list = level.getEntities(bot, aabb, e -> e instanceof LivingEntity);

            List<Value> results = new ArrayList<>();
            for (Entity e : list) {
                Map<String, Value> item = new HashMap<>();
                item.put("id", Value.of(e.getId()));
                item.put("name", Value.of(e.getName().getString()));
                item.put("type", Value.of(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString()));
                item.put("x", Value.of(e.getX()));
                item.put("y", Value.of(e.getY()));
                item.put("z", Value.of(e.getZ()));
                if (e instanceof LivingEntity le) {
                    item.put("hp", Value.of((double) le.getHealth()));
                }
                results.add(Value.of(item));
            }
            return Value.of(results);
        }));

        methods.put("entities", methods.get("findEntities"));

        return methods;
    }
}
