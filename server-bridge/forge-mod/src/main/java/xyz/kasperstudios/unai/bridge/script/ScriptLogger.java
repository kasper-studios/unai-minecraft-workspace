package xyz.kasperstudios.unai.bridge.script;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class ScriptLogger {
    private static final Logger LOGGER = LoggerFactory.getLogger("UnAI-Script");
    private static final ScriptLogger INSTANCE = new ScriptLogger();

    public static class LogEntry {
        public final long timestamp = System.currentTimeMillis();
        public final String level;
        public final String message;

        public LogEntry(String level, String message) {
            this.level = level;
            this.message = message;
        }
    }

    private final List<LogEntry> logs = new CopyOnWriteArrayList<>();
    private static final int MAX_LOGS = 200;

    private ScriptLogger() {}

    public static ScriptLogger getInstance() {
        return INSTANCE;
    }

    public void info(String msg) {
        LOGGER.info("[KHScript] {}", msg);
        addLog("INFO", msg);
    }

    public void warn(String msg) {
        LOGGER.warn("[KHScript] {}", msg);
        addLog("WARN", msg);
    }

    public void error(String msg) {
        LOGGER.error("[KHScript] {}", msg);
        addLog("ERROR", msg);
    }

    public void debug(String msg) {
        LOGGER.debug("[KHScript] {}", msg);
        addLog("DEBUG", msg);
    }

    private void addLog(String level, String msg) {
        logs.add(new LogEntry(level, msg));
        while (logs.size() > MAX_LOGS) {
            logs.remove(0);
        }
    }

    public List<LogEntry> getLogs() {
        return new ArrayList<>(logs);
    }

    public void clear() {
        logs.clear();
    }
}
