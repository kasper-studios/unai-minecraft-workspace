package xyz.kasperstudios.unai.bridge.script.objects;

import xyz.kasperstudios.unai.bridge.script.Callable;
import xyz.kasperstudios.unai.bridge.script.ScriptLogger;
import xyz.kasperstudios.unai.bridge.script.Value;

import java.util.HashMap;
import java.util.Map;

public class SystemObject extends Value {
    public SystemObject() {
        super(createMethods());
    }

    private static Map<String, Value> createMethods() {
        Map<String, Value> methods = new HashMap<>();
        methods.put("print", Value.of((Callable) (ctx, args) -> {
            String msg = args.isEmpty() ? "" : args.get(0).toString();
            ScriptLogger.getInstance().info(msg);
            return Value.NULL;
        }));
        methods.put("time", Value.of((Callable) (ctx, args) -> Value.of((double) System.currentTimeMillis())));
        return methods;
    }
}
