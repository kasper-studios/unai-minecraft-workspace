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
import net.minecraft.world.damagesource.CombatTracker;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

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
import java.util.concurrent.atomic.AtomicLong;

@Mod(ForgeBridgeMod.MOD_ID)
public class ForgeBridgeMod {
    public static final String MOD_ID = "unai_bridge";
    public static final Logger LOGGER = LogUtils.getLogger();

    private MinecraftServer server;
    private HttpServer httpServer;
    private ExecutorService httpExecutor;
    private String apiKey;
    private int port = 25585;

    public static class ChatEventItem {
        public final long id;
        public final long timestamp;
        public final String type;
        public final String sender;
        public final String message;
        public volatile boolean read;

        public ChatEventItem(long id, long timestamp, String type, String sender, String message) {
            this.id = id;
            this.timestamp = timestamp;
            this.type = type;
            this.sender = sender;
            this.message = message;
            this.read = false;
        }
    }

    private final List<ChatEventItem> eventHistory = new CopyOnWriteArrayList<>();
    private final AtomicLong eventIdCounter = new AtomicLong(1);

    public ForgeBridgeMod() {
        MinecraftForge.EVENT_BUS.register(this);
        LOGGER.info("[UnAI-Bridge] Forge 1.21.1 Bridge mod registered.");
    }

    public void addEvent(String type, String sender, String message) {
        long id = eventIdCounter.getAndIncrement();
        long now = System.currentTimeMillis();
        eventHistory.add(new ChatEventItem(id, now, type, sender, message));
        while (eventHistory.size() > 500) {
            eventHistory.remove(0);
        }
    }

    @SubscribeEvent
    public void onServerChat(ServerChatEvent event) {
        String sender = event.getPlayer().getName().getString();
        String message = event.getRawText();
        addEvent("chat", sender, message);
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        String name = event.getEntity().getName().getString();
        addEvent("join", name, name + " joined the game");
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        String name = event.getEntity().getName().getString();
        addEvent("leave", name, name + " left the game");
    }

    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            CombatTracker tracker = player.getCombatTracker();
            String deathMsg = tracker.getDeathMessage().getString();
            addEvent("death", player.getName().getString(), deathMsg);
        }
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
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

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
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
        httpServer.createContext("/api/chat/history", new ChatHistoryHandler());
        httpServer.createContext("/api/notifications/feed", new NotificationsFeedHandler());
        httpServer.createContext("/api/notifications/clear", new NotificationsClearHandler());

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

    private String formatEventJson(ChatEventItem item) {
        return "{"
                + "\"id\": " + item.id + ","
                + "\"timestamp\": " + item.timestamp + ","
                + "\"type\": \"" + escapeJson(item.type) + "\","
                + "\"sender\": \"" + escapeJson(item.sender) + "\","
                + "\"message\": \"" + escapeJson(item.message) + "\","
                + "\"read\": " + item.read
                + "}";
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
            final String finalSender = sender;
            final String finalMsg = message;

            server.execute(() -> {
                if (server.getPlayerList() != null) {
                    server.getPlayerList().broadcastSystemMessage(Component.literal(formatted), false);
                }
                addEvent("chat_out", finalSender, finalMsg);
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

    private class ChatHistoryHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!checkAuth(exchange)) return;

            int limit = 50;
            String query = exchange.getRequestURI().getQuery();
            if (query != null && query.contains("limit=")) {
                try {
                    for (String part : query.split("&")) {
                        if (part.startsWith("limit=")) {
                            limit = Integer.parseInt(part.substring(6));
                        }
                    }
                } catch (Exception ignored) {}
            }

            List<String> items = new ArrayList<>();
            int size = eventHistory.size();
            int start = Math.max(0, size - limit);

            for (int i = start; i < size; i++) {
                items.add(formatEventJson(eventHistory.get(i)));
            }

            String json = "{\"ok\": true, \"count\": " + items.size() + ", \"messages\": [" + String.join(",", items) + "]}";
            sendJsonResponse(exchange, 200, json);
        }
    }

    private class NotificationsFeedHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!checkAuth(exchange)) return;

            boolean unreadOnly = true;
            int limit = 20;

            String query = exchange.getRequestURI().getQuery();
            if (query != null) {
                for (String part : query.split("&")) {
                    if (part.startsWith("unread_only=")) {
                        unreadOnly = Boolean.parseBoolean(part.substring(12));
                    } else if (part.startsWith("limit=")) {
                        try {
                            limit = Integer.parseInt(part.substring(6));
                        } catch (Exception ignored) {}
                    }
                }
            }

            List<String> resultList = new ArrayList<>();
            for (ChatEventItem item : eventHistory) {
                if (unreadOnly && item.read) {
                    continue;
                }
                resultList.add(formatEventJson(item));
                item.read = true;
                if (resultList.size() >= limit) {
                    break;
                }
            }

            String json = "{\"ok\": true, \"count\": " + resultList.size() + ", \"notifications\": [" + String.join(",", resultList) + "]}";
            sendJsonResponse(exchange, 200, json);
        }
    }

    private class NotificationsClearHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!checkAuth(exchange)) return;

            eventHistory.clear();
            sendJsonResponse(exchange, 200, "{\"ok\": true, \"message\": \"Notifications cleared.\"}");
        }
    }
}
