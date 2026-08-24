package xyz.kasperstudios.unai.bridge.paper;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class PaperBridgePlugin extends JavaPlugin implements Listener {

    private HttpServer httpServer;
    private String apiKey;
    private int port;
    private ExecutorService httpExecutor;

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

    public void addEvent(String type, String sender, String message) {
        long id = eventIdCounter.getAndIncrement();
        long now = System.currentTimeMillis();
        eventHistory.add(new ChatEventItem(id, now, type, sender, message));
        while (eventHistory.size() > 500) {
            eventHistory.remove(0);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        addEvent("chat", event.getPlayer().getName(), event.getMessage());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        addEvent("join", event.getPlayer().getName(), event.getPlayer().getName() + " joined the game");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        addEvent("leave", event.getPlayer().getName(), event.getPlayer().getName() + " left the game");
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        addEvent("death", event.getEntity().getName(), event.getDeathMessage() != null ? event.getDeathMessage() : "died");
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();

        this.port = getConfig().getInt("port", 25585);
        this.apiKey = getConfig().getString("api_key", "");

        if (this.apiKey == null || this.apiKey.trim().isEmpty() || this.apiKey.equals("auto")) {
            this.apiKey = generateRandomKey();
            getConfig().set("api_key", this.apiKey);
            saveConfig();
        }

        this.httpExecutor = Executors.newCachedThreadPool();
        getServer().getPluginManager().registerEvents(this, this);

        try {
            startHttpServer();
            getLogger().info("==================================================");
            getLogger().info("[UnAI-Bridge] Paper/Spigot bridge started!");
            getLogger().info("[UnAI-Bridge] Listening on: http://0.0.0.0:" + port);
            getLogger().info("[UnAI-Bridge] API Key: " + apiKey);
            getLogger().info("==================================================");
        } catch (IOException e) {
            getLogger().severe("[UnAI-Bridge] Failed to start HTTP server: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void onDisable() {
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
        }
        if (httpExecutor != null) {
            httpExecutor.shutdown();
            httpExecutor = null;
        }
        getLogger().info("[UnAI-Bridge] Stopped.");
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
        String pattern = "\"" + field + "\"\\s*:\\s*\"([^\"]*)\"";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(json);
        if (m.find()) {
            return m.group(1).replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n");
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
            try {
                double[] tpsArr = Bukkit.getTPS();
                if (tpsArr.length > 0) {
                    tps = Math.min(20.0, Math.round(tpsArr[0] * 100.0) / 100.0);
                }
            } catch (Throwable ignored) {}

            List<String> playerNames = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                playerNames.add("\"" + escapeJson(p.getName()) + "\"");
            }

            Runtime runtime = Runtime.getRuntime();
            long maxMem = runtime.maxMemory() / (1024 * 1024);
            long totalMem = runtime.totalMemory() / (1024 * 1024);
            long freeMem = runtime.freeMemory() / (1024 * 1024);
            long usedMem = totalMem - freeMem;

            String json = "{"
                    + "\"ok\": true,"
                    + "\"platform\": \"Paper/Spigot\","
                    + "\"version\": \"" + escapeJson(Bukkit.getVersion()) + "\","
                    + "\"bukkit_version\": \"" + escapeJson(Bukkit.getBukkitVersion()) + "\","
                    + "\"tps\": " + tps + ","
                    + "\"online_players\": " + Bukkit.getOnlinePlayers().size() + ","
                    + "\"max_players\": " + Bukkit.getMaxPlayers() + ","
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

            String body = readBody(exchange);
            String command = extractJsonField(body, "command");

            if (command == null || command.trim().isEmpty()) {
                sendJsonResponse(exchange, 400, "{\"ok\": false, \"error\": \"Missing command field\"}");
                return;
            }

            final String cleanCmd = command.startsWith("/") ? command.substring(1) : command;
            final List<String> capturedOutput = new CopyOnWriteArrayList<>();

            CommandSender sender = new CommandSender() {
                @Override
                public void sendMessage(String message) {
                    if (message != null) capturedOutput.add(ChatColor.stripColor(message));
                }

                @Override
                public void sendMessage(String... messages) {
                    for (String m : messages) sendMessage(m);
                }

                @Override
                public void sendMessage(UUID sender, String message) {
                    sendMessage(message);
                }

                @Override
                public void sendMessage(UUID sender, String... messages) {
                    sendMessage(messages);
                }

                @Override
                public Server getServer() {
                    return Bukkit.getServer();
                }

                @Override
                public String getName() {
                    return "UnAI";
                }

                @Override
                public net.kyori.adventure.text.Component name() {
                    return net.kyori.adventure.text.Component.text("UnAI");
                }

                @Override
                public Spigot spigot() {
                    return new Spigot();
                }

                @Override
                public boolean isPermissionSet(String name) { return true; }
                @Override
                public boolean isPermissionSet(Permission perm) { return true; }
                @Override
                public boolean hasPermission(String name) { return true; }
                @Override
                public boolean hasPermission(Permission perm) { return true; }
                @Override
                public PermissionAttachment addAttachment(Plugin plugin, String name, boolean value) { return null; }
                @Override
                public PermissionAttachment addAttachment(Plugin plugin) { return null; }
                @Override
                public PermissionAttachment addAttachment(Plugin plugin, String name, boolean value, int ticks) { return null; }
                @Override
                public PermissionAttachment addAttachment(Plugin plugin, int ticks) { return null; }
                @Override
                public void removeAttachment(PermissionAttachment attachment) {}
                @Override
                public void recalculatePermissions() {}
                @Override
                public Set<PermissionAttachmentInfo> getEffectivePermissions() { return Collections.emptySet(); }
                @Override
                public boolean isOp() { return true; }
                @Override
                public void setOp(boolean value) {}
            };

            CompletableFuture<String> future = new CompletableFuture<>();

            Bukkit.getScheduler().runTask(PaperBridgePlugin.this, () -> {
                try {
                    Bukkit.dispatchCommand(sender, cleanCmd);
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

            Bukkit.getScheduler().runTask(PaperBridgePlugin.this, () -> {
                Bukkit.broadcastMessage(formatted);
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
            for (Player p : Bukkit.getOnlinePlayers()) {
                String entry = "{"
                        + "\"name\": \"" + escapeJson(p.getName()) + "\","
                        + "\"uuid\": \"" + p.getUniqueId() + "\","
                        + "\"ping\": " + p.getPing() + ","
                        + "\"world\": \"" + escapeJson(p.getWorld().getName()) + "\","
                        + "\"x\": " + Math.round(p.getLocation().getX() * 100.0) / 100.0 + ","
                        + "\"y\": " + Math.round(p.getLocation().getY() * 100.0) / 100.0 + ","
                        + "\"z\": " + Math.round(p.getLocation().getZ() * 100.0) / 100.0 + ","
                        + "\"health\": " + Math.round(p.getHealth() * 10.0) / 10.0 + ","
                        + "\"gamemode\": \"" + p.getGameMode().name() + "\""
                        + "}";
                playerJsonList.add(entry);
            }

            String json = "{"
                    + "\"ok\": true,"
                    + "\"count\": " + Bukkit.getOnlinePlayers().size() + ","
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
