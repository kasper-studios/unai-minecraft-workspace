package xyz.kasperstudios.unai.bridge.forge;

import com.mojang.logging.LogUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

@Mod(ForgeBridgeMod.MOD_ID)
public class ForgeBridgeMod {
    public static final String MOD_ID = "unai_bridge";
    public static final Logger LOGGER = LogUtils.getLogger();

    private MinecraftServer server;
    private HttpServer httpServer;
    private ExecutorService httpExecutor;
    private String apiKey;
    private int port = 25585;

    public ForgeBridgeMod() {
        MinecraftForge.EVENT_BUS.addListener(this::onServerStarted);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStopping);
        LOGGER.info("[UnAI-Bridge] Forge 1.21.1 Bridge mod loaded.");
    }

    private void onServerStarted(ServerStartedEvent event) {
        this.server = event.getServer();
        loadConfig();
        this.httpExecutor = Executors.newCachedThreadPool();

        try {
            startHttpServer();
            LOGGER.info("==================================================");
            LOGGER.info("[UnAI-Bridge] Forge Server Bridge is ACTIVE!");
            LOGGER.info("[UnAI-Bridge] Listening on: http://0.0.0.0:{}", port);
            LOGGER.info("[UnAI-Bridge] API Key: {}", apiKey);
            LOGGER.info("==================================================");
        } catch (IOException e) {
            LOGGER.error("[UnAI-Bridge] Failed to start HTTP server", e);
        }
    }

    private void onServerStopping(ServerStoppingEvent event) {
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
        }
        if (httpExecutor != null) {
            httpExecutor.shutdown();
            httpExecutor = null;
        }
        this.server = null;
        LOGGER.info("[UnAI-Bridge] Stopped.");
    }

    private void loadConfig() {
        try {
            Path configDir = FMLPaths.CONFIGDIR.get();
            if (!Files.exists(configDir)) {
                Files.createDirectories(configDir);
            }
            Path configFile = configDir.resolve("unai-bridge.json");

            if (Files.exists(configFile)) {
                String content = Files.readString(configFile, StandardCharsets.UTF_8);
                String readKey = extractJsonField(content, "api_key");
                String readPort = extractJsonField(content, "port");

                if (readKey != null && !readKey.trim().isEmpty()) {
                    this.apiKey = readKey.trim();
                } else {
                    this.apiKey = generateRandomKey();
                }

                if (readPort != null) {
                    try {
                        this.port = Integer.parseInt(readPort);
                    } catch (NumberFormatException ignored) {}
                }
            } else {
                this.apiKey = generateRandomKey();
                String defaultJson = "{\n  \"port\": 25585,\n  \"api_key\": \"" + this.apiKey + "\"\n}\n";
                Files.writeString(configFile, defaultJson, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            LOGGER.warn("[UnAI-Bridge] Failed to load config, using defaults", e);
            if (this.apiKey == null) {
                this.apiKey = generateRandomKey();
            }
        }
    }

    private void startHttpServer() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress(port), 0);
        httpServer.setExecutor(httpExecutor);

        httpServer.createContext("/api/status", new StatusHandler());
        httpServer.createContext("/api/command", new CommandHandler());
        httpServer.createContext("/api/chat", new ChatHandler());
        httpServer.createContext("/api/players", new PlayersHandler());

        httpServer.start();
    }

    private boolean checkAuth(HttpExchange exchange) throws IOException {
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        String apiKeyHeader = exchange.getRequestHeaders().getFirst("X-API-Key");

        String providedKey = null;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            providedKey = authHeader.substring(7).trim();
        } else if (apiKeyHeader != null) {
            providedKey = apiKeyHeader.trim();
        }

        if (providedKey == null || !providedKey.equals(this.apiKey)) {
            sendJsonResponse(exchange, 401, "{\"ok\": false, \"error\": \"Unauthorized: Invalid or missing API key\"}");
            return false;
        }
        return true;
    }

    private void sendJsonResponse(HttpExchange exchange, int statusCode, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private String readBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String generateRandomKey() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder("unai_mc_");
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private String extractJsonField(String json, String field) {
        if (json == null) return null;
        String pattern = "\"" + field + "\"\\s*:\\s*(?:\"([^\"]*)\"|([0-9]+))";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(json);
        if (m.find()) {
            String strVal = m.group(1);
            if (strVal != null) {
                return strVal.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n");
            }
            return m.group(2);
        }
        return null;
    }

    // ====================================================================
    // Handlers
    // ====================================================================

    private class StatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!checkAuth(exchange)) return;

            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 405, "{\"ok\": false, \"error\": \"Method not allowed\"}");
                return;
            }

            double tps = 20.0;
            if (server != null) {
                try {
                    double meanTickTime = server.getAverageTickTimeNanos() / 1_000_000.0;
                    tps = Math.min(20.0, Math.round((1000.0 / Math.max(1.0, meanTickTime)) * 100.0) / 100.0);
                } catch (Throwable ignored) {}
            }

            List<String> playerNames = new ArrayList<>();
            int onlineCount = 0;
            int maxPlayers = 20;

            if (server != null && server.getPlayerList() != null) {
                onlineCount = server.getPlayerList().getPlayerCount();
                maxPlayers = server.getPlayerList().getMaxPlayers();
                for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                    playerNames.add("\"" + escapeJson(p.getName().getString()) + "\"");
                }
            }

            Runtime runtime = Runtime.getRuntime();
            long maxMem = runtime.maxMemory() / (1024 * 1024);
            long totalMem = runtime.totalMemory() / (1024 * 1024);
            long freeMem = runtime.freeMemory() / (1024 * 1024);
            long usedMem = totalMem - freeMem;

            String versionStr = server != null ? server.getServerVersion() : "1.21.1";

            String json = "{"
                    + "\"ok\": true,"
                    + "\"platform\": \"Forge 1.21.1\","
                    + "\"version\": \"" + escapeJson(versionStr) + "\","
                    + "\"tps\": " + tps + ","
                    + "\"online_players\": " + onlineCount + ","
                    + "\"max_players\": " + maxPlayers + ","
                    + "\"memory\": {"
                    + "\"used_mb\": " + usedMem + ","
                    + "\"total_mb\": " + totalMem + ","
                    + "\"max_mb\": " + maxMem
                    + "},"
                    + "\"players\": [" + String.join(",", playerNames) + "]"
                    + "}";

            sendJsonResponse(exchange, 200, json);
        }
    }

    private class CommandHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!checkAuth(exchange)) return;

            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 405, "{\"ok\": false, \"error\": \"Method not allowed\"}");
                return;
            }

            if (server == null) {
                sendJsonResponse(exchange, 503, "{\"ok\": false, \"error\": \"Minecraft server is not ready yet\"}");
                return;
            }

            String body = readBody(exchange);
            String command = extractJsonField(body, "command");

            if (command == null || command.trim().isEmpty()) {
                sendJsonResponse(exchange, 400, "{\"ok\": false, \"error\": \"Missing command field\"}");
                return;
            }

            final String cleanCmd = command.startsWith("/") ? command.substring(1) : command;
            final List<String> capturedOutput = new CopyOnWriteArrayList<>();

            CommandSource source = new CommandSource() {
                @Override
                public void sendSystemMessage(Component component) {
                    if (component != null) {
                        capturedOutput.add(component.getString());
                    }
                }

                @Override
                public boolean acceptsSuccess() {
                    return true;
                }

                @Override
                public boolean acceptsFailure() {
                    return true;
                }

                @Override
                public boolean shouldInformAdmins() {
                    return false;
                }
            };

            CompletableFuture<String> future = new CompletableFuture<>();

            server.execute(() -> {
                try {
                    CommandSourceStack stack = new CommandSourceStack(
                            source,
                            Vec3.ZERO,
                            Vec2.ZERO,
                            server.overworld(),
                            4,
                            "UnAI",
                            Component.literal("UnAI"),
                            server,
                            null
                    );
                    server.getCommands().performPrefixedCommand(stack, cleanCmd);
                    future.complete(String.join("\n", capturedOutput));
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });

            try {
                String out = future.get(10, TimeUnit.SECONDS);
                String respJson = "{\"ok\": true, \"output\": \"" + escapeJson(out.isEmpty() ? "Command executed." : out) + "\"}";
                sendJsonResponse(exchange, 200, respJson);
            } catch (Exception e) {
                sendJsonResponse(exchange, 500, "{\"ok\": false, \"error\": \"" + escapeJson(e.getMessage()) + "\"}");
            }
        }
    }

    private class ChatHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!checkAuth(exchange)) return;

            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 405, "{\"ok\": false, \"error\": \"Method not allowed\"}");
                return;
            }

            if (server == null) {
                sendJsonResponse(exchange, 503, "{\"ok\": false, \"error\": \"Minecraft server is not ready yet\"}");
                return;
            }

            String body = readBody(exchange);
            String message = extractJsonField(body, "message");
            String sender = extractJsonField(body, "sender");
            if (sender == null || sender.isEmpty()) sender = "Dirom";

            if (message == null || message.trim().isEmpty()) {
                sendJsonResponse(exchange, 400, "{\"ok\": false, \"error\": \"Missing message field\"}");
                return;
            }

            final String formatted = "§b[" + sender + "] §f" + message;
            server.execute(() -> {
                if (server.getPlayerList() != null) {
                    server.getPlayerList().broadcastSystemMessage(Component.literal(formatted), false);
                }
            });

            sendJsonResponse(exchange, 200, "{\"ok\": true}");
        }
    }

    private class PlayersHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!checkAuth(exchange)) return;

            List<String> playerJsonList = new ArrayList<>();
            if (server != null && server.getPlayerList() != null) {
                for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                    String dimName = p.serverLevel().dimension().location().toString();
                    String entry = "{"
                            + "\"name\": \"" + escapeJson(p.getName().getString()) + "\","
                            + "\"uuid\": \"" + p.getUUID() + "\","
                            + "\"ping\": " + p.connection.latency() + ","
                            + "\"world\": \"" + escapeJson(dimName) + "\","
                            + "\"x\": " + Math.round(p.getX() * 100.0) / 100.0 + ","
                            + "\"y\": " + Math.round(p.getY() * 100.0) / 100.0 + ","
                            + "\"z\": " + Math.round(p.getZ() * 100.0) / 100.0 + ","
                            + "\"health\": " + Math.round(p.getHealth() * 10.0) / 10.0 + ","
                            + "\"gamemode\": \"" + p.gameMode.getGameModeForPlayer().getName() + "\""
                            + "}";
                    playerJsonList.add(entry);
                }
            }

            int count = playerJsonList.size();
            String json = "{"
                    + "\"ok\": true,"
                    + "\"count\": " + count + ","
                    + "\"players\": [" + String.join(",", playerJsonList) + "]"
                    + "}";

            sendJsonResponse(exchange, 200, json);
        }
    }
}
