package xyz.kasperstudios.unai.bridge.script;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;

public class ScriptRunner {
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();
    private static volatile Future<?> activeTask = null;
    private static volatile Context activeContext = null;
    private static volatile String activeScriptName = null;

    public static synchronized boolean isRunning() {
        return activeTask != null && !activeTask.isDone();
    }

    public static synchronized String getActiveScriptName() {
        return activeScriptName;
    }

    public static synchronized void stopActiveScript() {
        if (activeContext != null) {
            activeContext.shouldStop = true;
        }
        if (activeTask != null) {
            activeTask.cancel(true);
            activeTask = null;
        }
        activeScriptName = null;
        ScriptLogger.getInstance().info("Active script stopped.");
    }

    public static synchronized CompletableFuture<String> runScriptAsync(String name, String code) {
        if (isRunning()) {
            stopActiveScript();
        }

        ScriptLogger.getInstance().clear();
        Registry.initialize();

        CompletableFuture<String> future = new CompletableFuture<>();
        Context ctx = new Context();
        activeContext = ctx;
        activeScriptName = name;

        activeTask = EXECUTOR.submit(() -> {
            try {
                ScriptLogger.getInstance().info("Starting script " + name + "...");
                List<String> lines = Arrays.asList(code.split("\\r?\\n"));
                Interpreter.execute(lines, ctx);
                ScriptLogger.getInstance().info("Script " + name + " finished successfully.");
                future.complete("ok");
            } catch (Throwable t) {
                ScriptLogger.getInstance().error("Script " + name + " failed: " + t.getMessage());
                future.completeExceptionally(t);
            } finally {
                synchronized (ScriptRunner.class) {
                    if (activeContext == ctx) {
                        activeTask = null;
                        activeScriptName = null;
                    }
                }
            }
        });

        return future;
    }
}
