package xyz.kasperstudios.unai.bridge.script;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Environment {
    private static final Environment INSTANCE = new Environment();
    private final Map<String, Value> globals = new ConcurrentHashMap<>();

    private Environment() {}

    public static Environment getInstance() {
        return INSTANCE;
    }

    public Value getValue(String name) {
        return globals.getOrDefault(name, Value.NULL);
    }

    public void setValue(String name, Value val) {
        globals.put(name, val);
    }
}
