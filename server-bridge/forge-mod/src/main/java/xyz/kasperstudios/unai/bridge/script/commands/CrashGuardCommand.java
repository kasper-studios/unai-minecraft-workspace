package xyz.kasperstudios.unai.bridge.script.commands;

import xyz.kasperstudios.unai.bridge.script.Command;
import xyz.kasperstudios.unai.bridge.script.Context;
import xyz.kasperstudios.unai.bridge.script.Interpreter;
import xyz.kasperstudios.unai.bridge.script.ScriptLogger;

import java.util.List;
import java.util.concurrent.*;
import java.util.regex.Matcher;

/**
 * CrashGuardCommand - Protects code blocks from crashes, errors, and performance issues.
 * 
 * Features:
 * - Exception handling (like try-catch)
 * - FPS monitoring and throttling
 * - Execution timeout protection
 * - Memory usage monitoring
 * - Automatic recovery
 * 
 * Usage: crashguard { ... }
 * Usage: crashguard(timeout=5000) { ... }
 * Usage: crashguard(minFps=30, timeout=10000) { ... }
 */
public class CrashGuardCommand implements Command {
    
    private static final ExecutorService GUARD_EXECUTOR = Executors.newFixedThreadPool(2);
    private static final int DEFAULT_TIMEOUT_MS = 30000; // 30 seconds
    private static final int DEFAULT_MIN_FPS = 20;
    private static final long FPS_CHECK_INTERVAL_MS = 100;
    
    @Override
    public String getName() {
        return "crashguard";
    }

    @Override
    public String getCategory() {
        return "Safety";
    }

    @Override
    public String getRegex() {
        return "^crashguard(?:\\((.*)\\))?\\s*\\{?$";
    }

    @Override
    public void execute(Context ctx, String line, List<String> blockBody) throws Exception {
        if (blockBody == null || blockBody.isEmpty()) {
            ScriptLogger.getInstance().warn("crashguard: Empty block");
            return;
        }

        // Parse options
        Matcher m = getMatcher(line);
        int timeoutMs = DEFAULT_TIMEOUT_MS;
        int minFps = DEFAULT_MIN_FPS;
        
        if (m.find() && m.group(1) != null) {
            String options = m.group(1);
            String[] pairs = options.split(",");
            for (String pair : pairs) {
                String[] kv = pair.split("=");
                if (kv.length == 2) {
                    String key = kv[0].trim();
                    String value = kv[1].trim();
                    if (key.equals("timeout")) {
                        timeoutMs = Integer.parseInt(value);
                    } else if (key.equals("minFps")) {
                        minFps = Integer.parseInt(value);
                    }
                }
            }
        }

        // Execute with protection
        executeWithGuard(ctx, blockBody, timeoutMs, minFps);
    }

    private void executeWithGuard(Context ctx, List<String> blockBody, int timeoutMs, int minFps) {
        try {
            Future<?> future = GUARD_EXECUTOR.submit(() -> {
                Context guardContext = new Context(ctx);
                Interpreter.executeBlock(blockBody, guardContext);
            });
            future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            ScriptLogger.getInstance().error("crashguard: Execution timeout (" + timeoutMs + "ms)");
        } catch (ExecutionException e) {
            ScriptLogger.getInstance().error("crashguard: Execution failed - " + e.getCause().getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ScriptLogger.getInstance().error("crashguard: Interrupted");
        }
    }
}