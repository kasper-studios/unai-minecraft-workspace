package xyz.kasperstudios.unai.bridge.script.objects;

import net.minecraft.server.level.ServerPlayer;
import xyz.kasperstudios.unai.bridge.forge.FakePlayerManager;
import xyz.kasperstudios.unai.bridge.script.Callable;
import xyz.kasperstudios.unai.bridge.script.Value;

import java.util.HashMap;
import java.util.Map;

public class BotObject extends Value {
    public BotObject() {
        super(createMethods());
    }

    private static Map<String, Value> createMethods() {
        Map<String, Value> methods = new HashMap<>();

        methods.put("pos", Value.of((Callable) (ctx, args) -> {
            ServerPlayer bot = FakePlayerManager.getInstance().getBot();
            if (bot == null) return Value.NULL;
            Map<String, Value> m = new HashMap<>();
            m.put("x", Value.of(bot.getX()));
            m.put("y", Value.of(bot.getY()));
            m.put("z", Value.of(bot.getZ()));
            return Value.of(m);
        }));

        methods.put("x", Value.of((Callable) (ctx, args) -> {
            ServerPlayer bot = FakePlayerManager.getInstance().getBot();
            return bot != null ? Value.of(bot.getX()) : Value.of(0);
        }));

        methods.put("y", Value.of((Callable) (ctx, args) -> {
            ServerPlayer bot = FakePlayerManager.getInstance().getBot();
            return bot != null ? Value.of(bot.getY()) : Value.of(0);
        }));

        methods.put("z", Value.of((Callable) (ctx, args) -> {
            ServerPlayer bot = FakePlayerManager.getInstance().getBot();
            return bot != null ? Value.of(bot.getZ()) : Value.of(0);
        }));

        methods.put("hp", Value.of((Callable) (ctx, args) -> {
            ServerPlayer bot = FakePlayerManager.getInstance().getBot();
            return bot != null ? Value.of((double) bot.getHealth()) : Value.of(0);
        }));

        methods.put("getHealth", Value.of((Callable) (ctx, args) -> {
            ServerPlayer bot = FakePlayerManager.getInstance().getBot();
            return bot != null ? Value.of((double) bot.getHealth()) : Value.of(0);
        }));

        methods.put("food", Value.of((Callable) (ctx, args) -> {
            ServerPlayer bot = FakePlayerManager.getInstance().getBot();
            return bot != null ? Value.of((double) bot.getFoodData().getFoodLevel()) : Value.of(0);
        }));

        methods.put("getHunger", Value.of((Callable) (ctx, args) -> {
            ServerPlayer bot = FakePlayerManager.getInstance().getBot();
            return bot != null ? Value.of((double) bot.getFoodData().getFoodLevel()) : Value.of(0);
        }));

        methods.put("yaw", Value.of((Callable) (ctx, args) -> {
            ServerPlayer bot = FakePlayerManager.getInstance().getBot();
            return bot != null ? Value.of((double) bot.getYRot()) : Value.of(0);
        }));

        methods.put("pitch", Value.of((Callable) (ctx, args) -> {
            ServerPlayer bot = FakePlayerManager.getInstance().getBot();
            return bot != null ? Value.of((double) bot.getXRot()) : Value.of(0);
        }));

        methods.put("navigate", Value.of((Callable) (ctx, args) -> {
            if (args.size() < 3) return Value.FALSE;
            double x = args.get(0).asDouble();
            double y = args.get(1).asDouble();
            double z = args.get(2).asDouble();
            float radius = args.size() > 3 ? (float) args.get(3).asDouble() : 1.2f;
            String res = FakePlayerManager.getInstance().navigateTo(x, y, z, radius);
            return Value.of("ok".equals(res));
        }));

        methods.put("moveTo", Value.of((Callable) (ctx, args) -> {
            if (args.size() < 3) return Value.FALSE;
            double x = args.get(0).asDouble();
            double y = args.get(1).asDouble();
            double z = args.get(2).asDouble();
            float radius = args.size() > 3 ? (float) args.get(3).asDouble() : 1.2f;
            String res = FakePlayerManager.getInstance().navigateTo(x, y, z, radius);
            return Value.of("ok".equals(res));
        }));

        methods.put("stop", Value.of((Callable) (ctx, args) -> {
            FakePlayerManager.getInstance().stopMove();
            return Value.TRUE;
        }));

        methods.put("stopMove", Value.of((Callable) (ctx, args) -> {
            FakePlayerManager.getInstance().stopMove();
            return Value.TRUE;
        }));

        methods.put("distanceTo", Value.of((Callable) (ctx, args) -> {
            if (args.size() < 3) return Value.of(9999.0);
            ServerPlayer bot = FakePlayerManager.getInstance().getBot();
            if (bot == null) return Value.of(9999.0);
            double x = args.get(0).asDouble();
            double y = args.get(1).asDouble();
            double z = args.get(2).asDouble();
            return Value.of(Math.sqrt(bot.distanceToSqr(x, y, z)));
        }));

        methods.put("lookAt", Value.of((Callable) (ctx, args) -> {
            if (args.size() < 3) return Value.FALSE;
            double x = args.get(0).asDouble();
            double y = args.get(1).asDouble();
            double z = args.get(2).asDouble();
            FakePlayerManager.getInstance().lookAt(x, y, z, null, null);
            return Value.TRUE;
        }));

        methods.put("action", Value.of((Callable) (ctx, args) -> {
            if (args.isEmpty()) return Value.FALSE;
            String act = args.get(0).asString();
            String res = FakePlayerManager.getInstance().action(act);
            return Value.of("ok".equals(res));
        }));

        methods.put("eat", Value.of((Callable) (ctx, args) -> {
            String res = FakePlayerManager.getInstance().action("eat");
            return Value.of("ok".equals(res));
        }));

        methods.put("equip", Value.of((Callable) (ctx, args) -> {
            if (args.size() < 2) return Value.FALSE;
            String item = args.get(0).asString();
            String slot = args.get(1).asString();
            String res = FakePlayerManager.getInstance().equip(slot, item);
            return Value.of("ok".equals(res));
        }));

        methods.put("breakBlock", Value.of((Callable) (ctx, args) -> {
            if (args.size() < 3) return Value.FALSE;
            int x = args.get(0).asInt();
            int y = args.get(1).asInt();
            int z = args.get(2).asInt();
            String res = FakePlayerManager.getInstance().breakBlock(x, y, z);
            return Value.of("ok".equals(res));
        }));

        methods.put("mine", Value.of((Callable) (ctx, args) -> {
            if (args.size() < 3) return Value.FALSE;
            int x = args.get(0).asInt();
            int y = args.get(1).asInt();
            int z = args.get(2).asInt();
            String res = FakePlayerManager.getInstance().breakBlock(x, y, z);
            return Value.of("ok".equals(res));
        }));

        methods.put("placeBlock", Value.of((Callable) (ctx, args) -> {
            if (args.size() < 3) return Value.FALSE;
            int x = args.get(0).asInt();
            int y = args.get(1).asInt();
            int z = args.get(2).asInt();
            String blockId = args.size() > 3 ? args.get(3).asString() : "";
            String res = FakePlayerManager.getInstance().placeBlock(x, y, z, blockId);
            return Value.of("ok".equals(res));
        }));

        methods.put("attack", Value.of((Callable) (ctx, args) -> {
            FakePlayerManager.getInstance().action("swing");
            return Value.TRUE;
        }));

        methods.put("inventory", Value.of((Callable) (ctx, args) -> {
            return Value.of(FakePlayerManager.getInstance().getInventoryJson());
        }));

        return methods;
    }
}
