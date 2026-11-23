package xyz.kasperstudios.unai.bridge.fabric.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Environment(EnvType.CLIENT)
public class FabricClientBridgeMod implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("unai_client_bridge");
    private static FabricClientBridgeMod instance;

    private String wsUrl;
    private String sessionToken;
    private WebSocket webSocket;
    private final AtomicBoolean isConnecting = new AtomicBoolean(false);
    private ScheduledExecutorService scheduler;

    public static FabricClientBridgeMod getInstance() {
        return instance;
    }

    @Override
    public void onInitializeClient() {
        instance = this;
        LOGGER.info("[UnAI Client Bridge] Initializing Cross-Version Client Bridge (1.16.5+)...");

        this.wsUrl = System.getProperty("unai.ws", "ws://127.0.0.1:25585/game_bridge");
        this.sessionToken = System.getProperty("unai.token", "");

        LOGGER.info("[UnAI Client Bridge] Configured bridge URL: {}", this.wsUrl);

        // Safely try registering chat events if Fabric API has them (1.19+)
        tryRegisterFabricEvents();

        // Start background telemetry & keepalive scheduler (runs independent of tick event)
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "unai-client-bridge-scheduler");
            t.setDaemon(true);
            return t;
        });

        // Periodic reconnect / telemetry check every 1 second
        scheduler.scheduleAtFixedRate(this::tickBridge, 2, 1, TimeUnit.SECONDS);

        // Connect immediately
        connectWebSocket();
    }

    private void tryRegisterFabricEvents() {
        try {
            Class<?> chatEvents = Class.forName("net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents");
            Field chatField = chatEvents.getField("CHAT");
            Object chatEvent = chatField.get(null);
            // We register if class is present
            LOGGER.info("[UnAI Client Bridge] Fabric ClientReceiveMessageEvents detected.");
        } catch (Throwable ignored) {
            LOGGER.info("[UnAI Client Bridge] Legacy Fabric API detected (ClientReceiveMessageEvents not present).");
        }
    }

    private void tickBridge() {
        try {
            if (webSocket == null && !isConnecting.get()) {
                connectWebSocket();
                return;
            }

            if (webSocket != null) {
                // Check if dead / on DeathScreen -> Auto Respawn!
                Object mc = getMinecraftInstance();
                if (mc != null) {
                    checkAndAutoRespawn(mc);
                }

                // Collect player telemetry via reflection
                JsonObject data = getPlayerTelemetry();
                if (data != null) {
                    JsonObject hudEv = new JsonObject();
                    hudEv.addProperty("event", "hud");
                    hudEv.add("data", data);
                    sendRaw(hudEv.toString());
                }
            }
        } catch (Throwable ignored) {}
    }

    public synchronized void connectWebSocket() {
        if (isConnecting.get()) return;
        isConnecting.set(true);

        try {
            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();

            httpClient.newWebSocketBuilder()
                    .buildAsync(URI.create(this.wsUrl), new WebSocketListener())
                    .whenComplete((ws, throwable) -> {
                        isConnecting.set(false);
                        if (throwable != null) {
                            LOGGER.warn("[UnAI Client Bridge] WebSocket connect failed: {}", throwable.getMessage());
                            this.webSocket = null;
                        } else {
                            this.webSocket = ws;
                            LOGGER.info("[UnAI Client Bridge] Connected to Launcher WebSocket!");
                            sendHandshake();
                        }
                    });
        } catch (Throwable e) {
            isConnecting.set(false);
            LOGGER.error("[UnAI Client Bridge] Error creating WebSocket client: {}", e.getMessage());
        }
    }

    private void sendHandshake() {
        JsonObject hs = new JsonObject();
        hs.addProperty("op", "handshake");
        hs.addProperty("side", "client");
        hs.addProperty("token", this.sessionToken);

        String uname = getPlayerUsername();
        hs.addProperty("username", uname != null ? uname : "DiromPrime");

        sendRaw(hs.toString());
    }

    public void sendChatEvent(String sender, String msg) {
        if (webSocket == null) return;
        JsonObject ev = new JsonObject();
        ev.addProperty("event", "chat");
        JsonObject data = new JsonObject();
        data.addProperty("sender", sender);
        data.addProperty("message", msg);
        ev.add("data", data);
        sendRaw(ev.toString());
    }

    public void sendRaw(String text) {
        if (this.webSocket != null) {
            try {
                this.webSocket.sendText(text, true);
            } catch (Throwable e) {
                LOGGER.warn("[UnAI Client Bridge] Failed to send WebSocket message: {}", e.getMessage());
            }
        }
    }

    public void sendResult(String id, boolean ok, JsonObject result) {
        JsonObject resp = new JsonObject();
        resp.addProperty("id", id);
        resp.addProperty("ok", ok);
        resp.add("result", result);
        sendRaw(resp.toString());
    }

    public void sendError(String id, String code, String message) {
        JsonObject resp = new JsonObject();
        resp.addProperty("id", id);
        resp.addProperty("ok", false);
        JsonObject err = new JsonObject();
        err.addProperty("code", code);
        err.addProperty("message", message);
        resp.add("error", err);
        sendRaw(resp.toString());
    }

    private class WebSocketListener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String fullMessage = buffer.toString();
                buffer.setLength(0);
                handleIncomingMessage(fullMessage);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            LOGGER.info("[UnAI Client Bridge] WebSocket closed: {} ({})", reason, statusCode);
            FabricClientBridgeMod.this.webSocket = null;
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            LOGGER.warn("[UnAI Client Bridge] WebSocket error: {}", error.getMessage());
            FabricClientBridgeMod.this.webSocket = null;
        }
    }

    private void handleIncomingMessage(String text) {
        try {
            JsonObject req = JsonParser.parseString(text).getAsJsonObject();
            if (!req.has("id") || !req.has("method")) {
                return;
            }

            String id = req.get("id").getAsString();
            String method = req.get("method").getAsString();
            JsonObject params = req.has("params") && req.get("params").isJsonObject()
                    ? req.getAsJsonObject("params")
                    : new JsonObject();

            executeRpcOnMainThread(id, method, params);
        } catch (Throwable e) {
            LOGGER.error("[UnAI Client Bridge] Error handling incoming RPC: {}", e.getMessage(), e);
        }
    }

    private void executeRpcOnMainThread(String id, String method, JsonObject params) {
        Object mc = getMinecraftInstance();
        if (mc == null) {
            sendError(id, "NO_MINECRAFT", "Minecraft instance is not initialized yet");
            return;
        }

        // Run on Minecraft main thread
        try {
            Method executeMethod = findMethod(mc.getClass(), "execute", "method_18859", "m_18691_");
            if (executeMethod != null) {
                executeMethod.invoke(mc, (Runnable) () -> executeRpcMethod(id, method, params, mc));
            } else {
                // Fallback direct execution
                executeRpcMethod(id, method, params, mc);
            }
        } catch (Throwable t) {
            executeRpcMethod(id, method, params, mc);
        }
    }

    private void executeRpcMethod(String id, String method, JsonObject params, Object mc) {
        try {
            switch (method.toLowerCase()) {
                case "connect_server": {
                    String address = params.has("address") ? params.get("address").getAsString() : "";
                    if (address.isEmpty()) {
                        sendError(id, "INVALID_ARGS", "Missing server 'address'");
                        return;
                    }

                    boolean ok = doConnectServer(mc, address);
                    if (ok) {
                        JsonObject res = new JsonObject();
                        res.addProperty("status", "connecting");
                        res.addProperty("address", address);
                        sendResult(id, true, res);
                    } else {
                        sendError(id, "CONNECT_FAILED", "Failed to initiate connection to " + address);
                    }
                    break;
                }

                case "disconnect": {
                    doDisconnect(mc);
                    JsonObject res = new JsonObject();
                    res.addProperty("status", "disconnected");
                    sendResult(id, true, res);
                    break;
                }

                case "status": {
                    JsonObject res = new JsonObject();
                    JsonObject telem = getPlayerTelemetry();
                    if (telem != null) {
                        res.addProperty("in_game", true);
                        for (String key : telem.keySet()) {
                            res.add(key, telem.get(key));
                        }
                    } else {
                        res.addProperty("in_game", false);
                    }
                    sendResult(id, true, res);
                    break;
                }

                case "say":
                case "chat": {
                    String msg = params.has("message") ? params.get("message").getAsString() : "";
                    boolean sent = doSendChat(mc, msg);
                    if (sent) {
                        JsonObject res = new JsonObject();
                        res.addProperty("sent", true);
                        res.addProperty("message", msg);
                        sendResult(id, true, res);
                    } else {
                        sendError(id, "NOT_IN_GAME", "Cannot send chat while not in game");
                    }
                    break;
                }

                case "inventory": {
                    sendResult(id, true, getInventorySnapshot(mc));
                    break;
                }

                case "screen": {
                    sendResult(id, true, getScreenSnapshot(mc));
                    break;
                }

                case "players": {
                    sendResult(id, true, getPlayerListSnapshot(mc));
                    break;
                }

                case "use_item": {
                    JsonObject res = useHeldItem(mc);
                    sendResult(id, true, res);
                    break;
                }

                case "input": {
                    JsonObject res = applyInput(mc, params);
                    sendResult(id, true, res);
                    break;
                }

                case "click_slot": {
                    int slot = params.has("slot") ? params.get("slot").getAsInt() : -1;
                    int button = params.has("button") ? params.get("button").getAsInt() : 0;
                    String clickType = params.has("click_type") ? params.get("click_type").getAsString() : "PICKUP";
                    JsonObject res = clickContainerSlot(mc, slot, button, clickType);
                    sendResult(id, true, res);
                    break;
                }

                case "respawn":
                case "respawn_internal": {
                    boolean ok = doRespawnDirect(mc);
                    JsonObject res = new JsonObject();
                    res.addProperty("respawned", ok);
                    sendResult(id, true, res);
                    break;
                }

                case "action": {
                    String act = params.has("action") ? params.get("action").getAsString().toLowerCase() : "";
                    if (act.equals("respawn")) {
                        boolean ok = doRespawnDirect(mc);
                        JsonObject res = new JsonObject();
                        res.addProperty("action", "respawn");
                        res.addProperty("executed", ok);
                        sendResult(id, true, res);
                    } else if (act.equals("swing")) {
                        JsonObject res = swingMainHand(mc);
                        res.addProperty("action", act);
                        res.addProperty("executed", true);
                        sendResult(id, true, res);
                    } else if (act.equals("attack")) {
                        JsonObject res = attackCrosshairTarget(mc);
                        res.addProperty("action", act);
                        res.addProperty("executed", true);
                        sendResult(id, true, res);
                    } else {
                        sendError(id, "UNSUPPORTED_ACTION", "Action '" + act + "' is not implemented on client bridge");
                    }
                    break;
                }

                case "set_settings": {
                    // Settings configured
                    JsonObject res = new JsonObject();
                    res.addProperty("configured", true);
                    sendResult(id, true, res);
                    break;
                }

                case "eval": {
                    String code = params.has("code") ? params.get("code").getAsString() : "";
                    if (code.isEmpty()) {
                        sendError(id, "INVALID_ARGS", "Missing 'code' parameter");
                        return;
                    }
                    JsonObject evalRes = doEval(mc, code);
                    sendResult(id, true, evalRes);
                    break;
                }

                default: {
                    sendError(id, "UNKNOWN_METHOD", "Method '" + method + "' is not supported on client bridge");
                    break;
                }
            }
        } catch (Throwable e) {
            sendError(id, "EXECUTION_ERROR", "Exception executing " + method + ": " + e.getMessage());
        }
    }

    private void checkAndAutoRespawn(Object mc) {
        try {
            Object player = getFieldValue(mc, "player", "field_1724");
            if (player != null) {
                boolean dead = false;
                Method isDead = findMethod(player.getClass(), "isDeadOrDying", "method_29504", "m_21224_");
                if (isDead != null) {
                    dead = (boolean) isDead.invoke(player);
                } else {
                    Method getHp = findMethod(player.getClass(), "getHealth", "method_6032", "m_21223_");
                    if (getHp != null && ((Number) getHp.invoke(player)).floatValue() <= 0.0f) {
                        dead = true;
                    }
                }

                Object screen = getFieldValue(mc, "screen", "field_1755");
                if (screen != null) {
                    String sname = screen.getClass().getSimpleName();
                    if (sname.contains("DeathScreen") || screen.getClass().getName().contains("class_418")) {
                        dead = true;
                    }
                }

                if (dead) {
                    LOGGER.info("[UnAI Client Bridge] Player death detected! Invoking auto-respawn...");
                    executeRpcOnMainThread("auto_respawn", "respawn_internal", new JsonObject());
                }
            }
        } catch (Throwable ignored) {}
    }

    private boolean doRespawnDirect(Object mc) {
        try {
            Object player = getFieldValue(mc, "player", "field_1724");
            if (player != null) {
                Method respawnMethod = findMethod(player.getClass(), "requestRespawn", "respawn", "method_3137", "m_6579_");
                if (respawnMethod != null) {
                    respawnMethod.invoke(player);
                    LOGGER.info("[UnAI Client Bridge] Invoked requestRespawn() successfully!");
                }
            }

            Method setScreen = findMethod(mc.getClass(), "setScreen", "method_1507", "m_91152_");
            if (setScreen != null) {
                setScreen.invoke(mc, (Object) null);
                LOGGER.info("[UnAI Client Bridge] Cleared screen after respawn.");
            }
            return true;
        } catch (Throwable t) {
            LOGGER.error("[UnAI Client Bridge] Error in doRespawnDirect: {}", t.getMessage());
            return false;
        }
    }

    // =========================================================================
    // Dynamic Reflection Helpers (Cross-Version 1.16.5 -> 1.21.1+)
    // =========================================================================

    private Object getMinecraftInstance() {
        try {
            Class<?> mcClass = findClass("net.minecraft.client.Minecraft", "net.minecraft.class_310");
            if (mcClass == null) return null;
            Method m = findMethod(mcClass, "getInstance", "method_1551", "m_91087_");
            return m != null ? m.invoke(null) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private String getPlayerUsername() {
        try {
            Object mc = getMinecraftInstance();
            if (mc == null) return null;
            Object user = getFieldValue(mc, "user", "field_1726", "m_91097_");
            if (user != null) {
                Method m = findMethod(user.getClass(), "getName", "method_1676", "m_92546_");
                if (m != null) return (String) m.invoke(user);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private JsonObject getPlayerTelemetry() {
        try {
            Object mc = getMinecraftInstance();
            if (mc == null) return null;
            Object player = getFieldValue(mc, "player", "field_1724");
            if (player == null) return null;

            JsonObject data = new JsonObject();
            data.addProperty("spawned", true);

            // Health
            Method getHp = findMethod(player.getClass(), "getHealth", "method_6032", "m_21223_");
            if (getHp != null) {
                data.addProperty("health", ((Number) getHp.invoke(player)).floatValue());
            }

            // Coordinates
            Method getX = findMethod(player.getClass(), "getX", "method_23317", "m_20185_");
            Method getY = findMethod(player.getClass(), "getY", "method_23318", "m_20186_");
            Method getZ = findMethod(player.getClass(), "getZ", "method_23321", "m_20189_");
            if (getX != null && getY != null && getZ != null) {
                data.addProperty("x", ((Number) getX.invoke(player)).doubleValue());
                data.addProperty("y", ((Number) getY.invoke(player)).doubleValue());
                data.addProperty("z", ((Number) getZ.invoke(player)).doubleValue());
            }

            // Rotation
            Method getYRot = findMethod(player.getClass(), "getYRot", "method_36454", "m_146908_");
            Method getXRot = findMethod(player.getClass(), "getXRot", "method_36455", "m_146909_");
            if (getYRot != null && getXRot != null) {
                data.addProperty("yaw", ((Number) getYRot.invoke(player)).floatValue());
                data.addProperty("pitch", ((Number) getXRot.invoke(player)).floatValue());
            }

            return data;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private JsonObject getInventorySnapshot(Object mc) {
        JsonObject result = new JsonObject();
        Object player = getFieldValue(mc, "player", "field_1724");
        if (player == null) {
            result.addProperty("in_game", false);
            return result;
        }
        result.addProperty("in_game", true);
        Object inventory = invokeNoArgs(player, "getInventory", "method_31548", "m_150109_");
        if (inventory == null) inventory = getFieldValue(player, "inventory", "f_36076_");
        if (inventory == null) throw new IllegalStateException("Player inventory is unavailable");

        int size = 0;
        Object sizeValue = invokeNoArgs(inventory, "getContainerSize", "getSize", "method_5439", "m_6643_");
        if (sizeValue instanceof Number) size = ((Number) sizeValue).intValue();
        if (size <= 0 || size > 128) throw new IllegalStateException("Invalid inventory size: " + size);

        com.google.gson.JsonArray items = new com.google.gson.JsonArray();
        Method getItem = findMethod(inventory.getClass(), "getItem", "method_5438", "m_8020_");
        if (getItem == null) throw new IllegalStateException("Inventory slot access is unavailable");
        for (int i = 0; i < size; i++) {
            try {
                Object stack = getItem.invoke(inventory, i);
                if (stack == null || Boolean.TRUE.equals(invokeNoArgs(stack, "isEmpty", "method_7960", "m_41619_"))) continue;
                JsonObject item = new JsonObject();
                item.addProperty("slot", i);
                item.addProperty("count", numberValue(invokeNoArgs(stack, "getCount", "method_7947", "m_41613_"), 1));
                Object name = invokeNoArgs(stack, "getHoverName", "method_7964", "m_7639_");
                item.addProperty("name", componentText(name));
                item.addProperty("item", itemId(stack));
                items.add(item);
            } catch (Throwable e) {
                throw new IllegalStateException("Failed to read inventory slot " + i, e);
            }
        }
        result.add("items", items);
        Object selected = getFieldValue(inventory, "selected", "selectedSlot", "field_7545");
        if (selected instanceof Number) result.addProperty("selected_slot", ((Number) selected).intValue());
        return result;
    }

    private JsonObject getScreenSnapshot(Object mc) {
        JsonObject result = new JsonObject();
        Object screen = getFieldValue(mc, "screen", "currentScreen", "field_1755");
        result.addProperty("open", screen != null);
        result.addProperty("screen_class", screen == null ? "" : screen.getClass().getName());
        Object title = screen == null ? null : invokeNoArgs(screen, "getTitle", "method_25440", "m_7861_");
        result.addProperty("title", componentText(title));

        Object player = getFieldValue(mc, "player", "field_1724");
        Object menu = player == null ? null : getFieldValue(player, "containerMenu", "currentScreenHandler", "field_7512");
        Object slotsValue = menu == null ? null : getFieldValue(menu, "slots", "field_7761", "f_38839_");
        com.google.gson.JsonArray slots = new com.google.gson.JsonArray();
        if (slotsValue instanceof Iterable<?>) {
            int index = 0;
            for (Object slot : (Iterable<?>) slotsValue) {
                Object stack = invokeNoArgs(slot, "getItem", "getStack", "method_7677", "m_7993_");
                if (stack != null && !Boolean.TRUE.equals(invokeNoArgs(stack, "isEmpty", "method_7960", "m_41619_"))) {
                    JsonObject item = new JsonObject();
                    item.addProperty("slot", index);
                    item.addProperty("name", componentText(invokeNoArgs(stack, "getHoverName", "method_7964", "m_7639_")));
                    item.addProperty("item", itemId(stack));
                    item.addProperty("count", numberValue(invokeNoArgs(stack, "getCount", "method_7947", "m_41613_"), 1));
                    slots.add(item);
                }
                index++;
            }
            result.addProperty("slot_count", index);
        } else {
            result.addProperty("slot_count", 0);
        }
        result.add("items", slots);
        return result;
    }

    private JsonObject getPlayerListSnapshot(Object mc) {
        JsonObject result = new JsonObject();
        Object level = getFieldValue(mc, "level", "field_1687");
        com.google.gson.JsonArray players = new com.google.gson.JsonArray();
        if (level != null) {
            Object values = invokeNoArgs(level, "players", "players", "method_18456", "m_45976_");
            if (values instanceof Iterable<?>) {
                for (Object player : (Iterable<?>) values) {
                    JsonObject entry = new JsonObject();
                    Object profile = invokeNoArgs(player, "getGameProfile", "method_7334");
                    Object name = profile == null ? null : invokeNoArgs(profile, "getName", "getName");
                    entry.addProperty("name", name == null ? "" : name.toString());
                    entry.addProperty("x", numberValue(invokeNoArgs(player, "getX", "method_23317", "m_20185_"), 0));
                    entry.addProperty("y", numberValue(invokeNoArgs(player, "getY", "method_23318", "m_20186_"), 0));
                    entry.addProperty("z", numberValue(invokeNoArgs(player, "getZ", "method_23321", "m_20189_"), 0));
                    players.add(entry);
                }
            }
        }
        result.addProperty("in_game", level != null);
        result.add("players", players);
        Object connection = invokeNoArgs(mc, "getConnection", "getNetworkHandler", "method_1562", "m_91403_");
        Object onlineValue = connection == null ? null : invokeNoArgs(connection, "getOnlinePlayers", "getPlayerList", "method_2880");
        com.google.gson.JsonArray onlinePlayers = new com.google.gson.JsonArray();
        if (onlineValue instanceof Iterable<?>) {
            for (Object info : (Iterable<?>) onlineValue) {
                Object profile = invokeNoArgs(info, "getProfile", "method_2966", "m_105297_");
                Object nameValue = profile == null ? null : invokeNoArgs(profile, "getName", "getName");
                if (nameValue != null) onlinePlayers.add(nameValue.toString());
            }
        }
        result.add("online_players", onlinePlayers);
        return result;
    }

    private JsonObject swingMainHand(Object mc) {
        Object player = getFieldValue(mc, "player", "field_1724");
        if (player == null) throw new IllegalStateException("Player is not in a world");
        Class<?> handClass = findClass("net.minecraft.world.InteractionHand", "net.minecraft.class_1268");
        if (handClass == null || !handClass.isEnum()) throw new IllegalStateException("InteractionHand enum is unavailable");
        @SuppressWarnings({"rawtypes", "unchecked"}) Object hand = Enum.valueOf((Class<? extends Enum>) handClass, "MAIN_HAND");
        Method swing = findMethodWithArity(player.getClass(), 1, "swingHand", "method_6104");
        if (swing == null) throw new IllegalStateException("Player swing method is unavailable");
        try {
            swing.invoke(player, hand);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to swing main hand", e);
        }
        JsonObject result = new JsonObject();
        result.addProperty("swing", true);
        return result;
    }

    private JsonObject attackCrosshairTarget(Object mc) {
        Object player = getFieldValue(mc, "player", "field_1724");
        Object gameMode = getFieldValue(mc, "gameMode", "interactionManager", "field_1761");
        Object hit = getFieldValue(mc, "hitResult", "crosshairTarget", "field_1765");
        if (player == null || gameMode == null || hit == null) throw new IllegalStateException("No attack target in game");
        Object target = invokeNoArgs(hit, "getEntity", "method_17782", "m_82434_");
        if (target == null) throw new IllegalStateException("Crosshair is not on an entity");
        Method attack = findCompatibleMethod(gameMode.getClass(), new String[]{"attack", "attackEntity", "method_2918", "m_105131_"});
        if (attack == null) throw new IllegalStateException("Client attack method is unavailable");
        try {
            attack.invoke(gameMode, player, target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to attack crosshair target", e);
        }
        JsonObject result = new JsonObject();
        result.addProperty("attacked", true);
        result.addProperty("target", componentText(invokeNoArgs(target, "getName", "method_5667", "m_5446_")));
        return result;
    }

    private JsonObject applyInput(Object mc, JsonObject params) {
        if (scheduler == null || scheduler.isShutdown()) throw new IllegalStateException("Input scheduler is unavailable");
        com.google.gson.JsonArray requested = params.has("keys") && params.get("keys").isJsonArray()
                ? params.getAsJsonArray("keys") : new com.google.gson.JsonArray();
        if (requested.size() == 0 || requested.size() > 8) throw new IllegalArgumentException("Provide 1-8 input keys");
        int holdMs = params.has("hold_ms") ? params.get("hold_ms").getAsInt() : 150;
        if (holdMs < 50 || holdMs > 5000) throw new IllegalArgumentException("hold_ms must be between 50 and 5000");

        Object options = getFieldValue(mc, "options", "options", "field_1690");
        if (options == null) throw new IllegalStateException("Minecraft key mappings are unavailable");
        java.util.List<Object> pressed = new java.util.ArrayList<>();
        for (com.google.gson.JsonElement element : requested) {
            String key = element.getAsString().toLowerCase();
            Object mapping;
            if (key.matches("hotbar_[1-9]")) {
                Object hotbar = getFieldValue(options, "keyHotbarSlots", "field_1852");
                int index = Character.digit(key.charAt(key.length() - 1), 10) - 1;
                mapping = hotbar != null && hotbar.getClass().isArray() && java.lang.reflect.Array.getLength(hotbar) > index
                        ? java.lang.reflect.Array.get(hotbar, index) : null;
            } else {
                String[] fields;
                switch (key) {
                    case "forward": fields = new String[]{"keyUp", "field_1894"}; break;
                    case "back": fields = new String[]{"keyDown", "field_1881"}; break;
                    case "left": fields = new String[]{"keyLeft", "field_1913"}; break;
                    case "right": fields = new String[]{"keyRight", "field_1849"}; break;
                    case "jump": fields = new String[]{"keyJump", "field_1903"}; break;
                    case "sneak": fields = new String[]{"keyShift", "field_1832"}; break;
                    case "sprint": fields = new String[]{"keySprint", "field_1867"}; break;
                    case "attack": fields = new String[]{"keyAttack", "field_1886"}; break;
                    case "use": fields = new String[]{"keyUse", "field_1904"}; break;
                    case "drop": fields = new String[]{"keyDrop", "field_1869"}; break;
                    case "inventory": fields = new String[]{"keyInventory", "field_1822"}; break;
                    default: throw new IllegalArgumentException("Unsupported input key: " + key);
                }
                mapping = getFieldValue(options, fields);
            }
            if (mapping == null) throw new IllegalStateException("Key mapping is unavailable for: " + key);
            setKeyDown(mapping, true);
            pressed.add(mapping);
        }
        scheduler.schedule(() -> {
            Runnable release = () -> {
                for (Object mapping : pressed) {
                    try { setKeyDown(mapping, false); } catch (Throwable ignored) {}
                }
            };
            try {
                Method execute = findMethod(mc.getClass(), "execute", "method_18859", "m_18691_");
                if (execute != null) execute.invoke(mc, release);
                else release.run();
            } catch (Throwable e) {
                LOGGER.warn("[UnAI Client Bridge] Failed to release held keys: {}", e.getMessage());
            }
        }, holdMs, TimeUnit.MILLISECONDS);

        JsonObject result = new JsonObject();
        com.google.gson.JsonArray names = new com.google.gson.JsonArray();
        for (com.google.gson.JsonElement element : requested) names.add(element.getAsString().toLowerCase());
        result.add("pressed", names);
        result.addProperty("hold_ms", holdMs);
        result.addProperty("release_scheduled", true);
        return result;
    }

    private void setKeyDown(Object mapping, boolean down) {
        Method setter = findMethod(mapping.getClass(), "setDown", "method_23481", "m_90848_");
        if (setter == null || setter.getParameterCount() != 1 || setter.getParameterTypes()[0] != boolean.class)
            throw new IllegalStateException("KeyMapping.setDown is unavailable");
        try { setter.invoke(mapping, down); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException("Failed to update key state", e); }
    }

    private JsonObject useHeldItem(Object mc) {
        Object player = getFieldValue(mc, "player", "field_1724");
        Object gameMode = getFieldValue(mc, "gameMode", "interactionManager", "field_1761");
        if (player == null || gameMode == null) throw new IllegalStateException("Player is not in a world");
        Class<?> handClass = findClass("net.minecraft.world.InteractionHand", "net.minecraft.class_1268");
        if (handClass == null || !handClass.isEnum()) throw new IllegalStateException("InteractionHand enum is unavailable");
        @SuppressWarnings({"rawtypes", "unchecked"}) Object hand = Enum.valueOf((Class<? extends Enum>) handClass, "MAIN_HAND");
        Method use = findCompatibleMethod(gameMode.getClass(), new String[]{"useItem", "interactItem", "method_2919"});
        if (use == null) throw new IllegalStateException("Client item-use method is unavailable");
        Object outcome;
        try {
            outcome = use.invoke(gameMode, player, hand);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to use held item", e);
        }
        String outcomeText = outcome == null ? "unknown" : outcome.toString();
        JsonObject result = new JsonObject();
        result.addProperty("used", !"PASS".equalsIgnoreCase(outcomeText) && !"FAIL".equalsIgnoreCase(outcomeText));
        result.addProperty("outcome", outcomeText);
        return result;
    }

    private JsonObject clickContainerSlot(Object mc, int slot, int button, String clickTypeName) {
        Object player = getFieldValue(mc, "player", "field_1724");
        Object gameMode = getFieldValue(mc, "gameMode", "interactionManager", "field_1761");
        Object menu = player == null ? null : getFieldValue(player, "containerMenu", "currentScreenHandler", "field_7512");
        if (player == null || gameMode == null || menu == null) throw new IllegalStateException("No active container screen");
        Object containerId = getFieldValue(menu, "containerId", "syncId", "field_7763", "f_38844_");
        if (!(containerId instanceof Number)) throw new IllegalStateException("Container id is unavailable");
        Class<?> clickClass = findClass("net.minecraft.world.inventory.ClickType", "net.minecraft.class_1713");
        if (clickClass == null || !clickClass.isEnum()) throw new IllegalStateException("ClickType enum is unavailable");
        @SuppressWarnings({"rawtypes", "unchecked"}) Object clickType = Enum.valueOf((Class<? extends Enum>) clickClass, clickTypeName.toUpperCase());
        Method click = findMethodWithArity(gameMode.getClass(), 5, "handleInventoryMouseClick", "clickSlot", "method_2906", "m_171	");
        if (click == null) throw new IllegalStateException("Container click method is unavailable");
        try {
            click.invoke(gameMode, ((Number) containerId).intValue(), slot, button, clickType, player);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to click container slot", e);
        }
        JsonObject result = new JsonObject();
        result.addProperty("clicked", true);
        result.addProperty("slot", slot);
        result.addProperty("button", button);
        result.addProperty("click_type", clickTypeName.toUpperCase());
        return result;
    }

    private Object invokeNoArgs(Object target, String... names) {
        if (target == null) return null;
        Method method = findMethod(target.getClass(), names);
        if (method == null || method.getParameterCount() != 0) return null;
        try { return method.invoke(target); } catch (Throwable ignored) { return null; }
    }

    private Method findMethodWithArity(Class<?> type, int arity, String... names) {
        for (String name : names) {
            for (Method method : type.getMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == arity) return method;
            }
            for (Method method : type.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == arity) {
                    method.setAccessible(true);
                    return method;
                }
            }
        }
        return null;
    }

    private Method findCompatibleMethod(Class<?> type, String[] names) {
        for (String name : names) {
            for (Method method : type.getMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == 2) return method;
            }
            for (Method method : type.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == 2) {
                    method.setAccessible(true);
                    return method;
                }
            }
        }
        return null;
    }

    private double numberValue(Object value, double fallback) {
        return value instanceof Number ? ((Number) value).doubleValue() : fallback;
    }

    private String componentText(Object component) {
        if (component == null) return "";
        Object text = invokeNoArgs(component, "getString", "getString");
        return text == null ? component.toString() : text.toString();
    }

    private String itemId(Object stack) {
        try {
            Object item = invokeNoArgs(stack, "getItem", "method_7909", "m_41720_");
            Class<?> registries = findClass("net.minecraft.core.registries.BuiltInRegistries");
            Object itemRegistry = registries == null ? null : getStaticFieldValue(registries, "ITEM", "f_256780_");
            if (itemRegistry != null && item != null) {
                Method getKey = findMethodWithArity(itemRegistry.getClass(), 1, "getKey", "method_10221", "m_7981_");
                Object key = getKey == null ? null : getKey.invoke(itemRegistry, item);
                if (key != null) return key.toString();
            }
        } catch (Throwable ignored) {}
        return "unknown";
    }

    private boolean doConnectServer(Object mc, String address) {
        try {
            LOGGER.info("[UnAI Client Bridge] Connecting to server '{}'...", address);

            Class<?> connectScreenClass = findClass("net.minecraft.client.gui.screens.ConnectScreen", "net.minecraft.class_412");
            Class<?> titleScreenClass = findClass("net.minecraft.client.gui.screens.TitleScreen", "net.minecraft.class_442");
            Class<?> serverAddressClass = findClass("net.minecraft.client.multiplayer.resolver.ServerAddress", "net.minecraft.class_639");
            Class<?> serverDataClass = findClass("net.minecraft.client.multiplayer.ServerData", "net.minecraft.class_642");

            Object parentScreen = titleScreenClass != null ? titleScreenClass.getConstructor().newInstance() : null;

            // Parse ServerAddress
            Object sAddr = null;
            if (serverAddressClass != null) {
                Method parseAddr = findMethod(serverAddressClass, "parseString", "method_2950", "m_105389_");
                if (parseAddr != null) {
                    sAddr = parseAddr.invoke(null, address);
                }
            }

            // Create ServerData / ServerInfo
            Object sData = createServerData(serverDataClass, address);

            if (connectScreenClass != null) {
                // 1. Try static methods (startConnecting, connect, method_36877, method_2130, etc.)
                for (Method m : connectScreenClass.getMethods()) {
                    String mname = m.getName();
                    if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                        Class<?>[] ptypes = m.getParameterTypes();
                        try {
                            m.setAccessible(true);
                            if (ptypes.length == 5) {
                                // 1.18 - 1.20.1: (Screen parent, Minecraft client, ServerAddress addr, ServerData info, boolean quickPlay)
                                m.invoke(null, parentScreen, mc, sAddr != null ? sAddr : address, sData, false);
                                LOGGER.info("[UnAI Client Bridge] Invoked 5-arg connect static method: {}", mname);
                                return true;
                            } else if (ptypes.length == 4) {
                                // (Screen parent, Minecraft client, ServerAddress addr, ServerData info)
                                m.invoke(null, parentScreen, mc, sAddr != null ? sAddr : address, sData);
                                LOGGER.info("[UnAI Client Bridge] Invoked 4-arg connect static method: {}", mname);
                                return true;
                            } else if (ptypes.length == 6) {
                                // 1.20.2 - 1.21+: (Screen parent, Minecraft client, ServerAddress addr, ServerData info, boolean quickPlay, TransferData transfer)
                                m.invoke(null, parentScreen, mc, sAddr != null ? sAddr : address, sData, false, null);
                                LOGGER.info("[UnAI Client Bridge] Invoked 6-arg connect static method: {}", mname);
                                return true;
                            } else if (ptypes.length == 3) {
                                try {
                                    m.invoke(null, parentScreen, mc, sAddr != null ? sAddr : sData);
                                    LOGGER.info("[UnAI Client Bridge] Invoked 3-arg connect static method: {}", mname);
                                    return true;
                                } catch (Throwable ignored) {
                                    m.invoke(null, mc, sAddr != null ? sAddr : address, sData);
                                    LOGGER.info("[UnAI Client Bridge] Invoked 3-arg connect static method (variant 2): {}", mname);
                                    return true;
                                }
                            }
                        } catch (Throwable t) {
                            LOGGER.debug("[UnAI Client Bridge] Failed calling {}: {}", mname, t.getMessage());
                        }
                    }
                }

                // 2. Try constructor instantiations (1.16.5 - 1.17 legacy style: mc.setScreen(new ConnectScreen(...)))
                for (Constructor<?> c : connectScreenClass.getConstructors()) {
                    Class<?>[] ptypes = c.getParameterTypes();
                    try {
                        c.setAccessible(true);
                        Object screenInstance = null;
                        if (ptypes.length == 3) {
                            screenInstance = c.newInstance(parentScreen, mc, sData != null ? sData : sAddr);
                        } else if (ptypes.length == 4) {
                            screenInstance = c.newInstance(parentScreen, mc, sAddr, sData);
                        }

                        if (screenInstance != null) {
                            Method setScreen = findMethod(mc.getClass(), "setScreen", "method_1507", "m_91152_");
                            if (setScreen != null) {
                                setScreen.invoke(mc, screenInstance);
                                LOGGER.info("[UnAI Client Bridge] Instantiated ConnectScreen constructor and set screen!");
                                return true;
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            }

            LOGGER.warn("[UnAI Client Bridge] Could not find compatible ConnectScreen method/constructor");
            return false;
        } catch (Throwable t) {
            LOGGER.error("[UnAI Client Bridge] Error in doConnectServer: {}", t.getMessage(), t);
            return false;
        }
    }

    private Object createServerData(Class<?> serverDataClass, String address) {
        if (serverDataClass == null) return null;
        for (Constructor<?> c : serverDataClass.getConstructors()) {
            Class<?>[] ptypes = c.getParameterTypes();
            try {
                c.setAccessible(true);
                if (ptypes.length == 2 && ptypes[0] == String.class && ptypes[1] == String.class) {
                    return c.newInstance("Server", address);
                } else if (ptypes.length == 3 && ptypes[0] == String.class && ptypes[1] == String.class && ptypes[2] == boolean.class) {
                    return c.newInstance("Server", address, false);
                } else if (ptypes.length == 3 && ptypes[0] == String.class && ptypes[1] == String.class) {
                    // Enum server type
                    Object[] consts = ptypes[2].getEnumConstants();
                    Object enumVal = consts != null && consts.length > 0 ? consts[0] : null;
                    return c.newInstance("Server", address, enumVal);
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private void doDisconnect(Object mc) {
        try {
            Method disc = findMethod(mc.getClass(), "disconnect", "method_18096", "m_91396_");
            if (disc != null) {
                Class<?> titleScreenClass = findClass("net.minecraft.client.gui.screens.TitleScreen", "net.minecraft.class_442");
                Object parentScreen = titleScreenClass != null ? titleScreenClass.getConstructor().newInstance() : null;
                if (disc.getParameterCount() == 1) {
                    disc.invoke(mc, parentScreen);
                } else if (disc.getParameterCount() == 0) {
                    disc.invoke(mc);
                }
            }
        } catch (Throwable ignored) {}
    }

    private boolean doSendChat(Object mc, String message) {
        try {
            LOGGER.info("[UnAI Client Bridge] Sending chat input ({} characters)", message.length());
            Object player = getFieldValue(mc, "player", "field_1724");
            Object conn = null;

            if (player != null) {
                // Find any field of type ClientPlayNetworkHandler / class_634
                for (Field f : player.getClass().getFields()) {
                    if (f.getType().getName().contains("NetworkHandler") || f.getType().getName().contains("class_634")) {
                        try {
                            f.setAccessible(true);
                            conn = f.get(player);
                            if (conn != null) break;
                        } catch (Throwable ignored) {}
                    }
                }
                if (conn == null) {
                    for (Field f : player.getClass().getDeclaredFields()) {
                        if (f.getType().getName().contains("NetworkHandler") || f.getType().getName().contains("class_634")) {
                            try {
                                f.setAccessible(true);
                                conn = f.get(player);
                                if (conn != null) break;
                            } catch (Throwable ignored) {}
                        }
                    }
                }
            }

            if (conn == null) {
                Method getNet = findMethod(mc.getClass(), "getNetworkHandler", "method_1562", "m_91403_");
                if (getNet != null) {
                    conn = getNet.invoke(mc);
                }
            }

            if (conn != null) {
                LOGGER.info("[UnAI Client Bridge] Found network handler: {}", conn.getClass().getName());
                if (message.startsWith("/")) {
                    String cmd = message.substring(1);
                    for (Method m : conn.getClass().getMethods()) {
                        Class<?>[] p = m.getParameterTypes();
                        if (p.length == 1 && p[0] == String.class) {
                            if (m.getName().equals("sendCommand") || m.getName().equals("method_44099") || m.getName().equals("method_45730") || m.getName().equals("m_246473_")) {
                                m.setAccessible(true);
                                m.invoke(conn, cmd);
                                LOGGER.info("[UnAI Client Bridge] Invoked sendCommand via {}", m.getName());
                                return true;
                            }
                        }
                    }
                } else {
                    for (Method m : conn.getClass().getMethods()) {
                        Class<?>[] p = m.getParameterTypes();
                        if (p.length == 1 && p[0] == String.class) {
                            if (m.getName().equals("sendChat") || m.getName().equals("sendChatMessage") || m.getName().equals("method_44098") || m.getName().equals("method_45729") || m.getName().equals("m_246472_")) {
                                m.setAccessible(true);
                                m.invoke(conn, message);
                                LOGGER.info("[UnAI Client Bridge] Invoked sendChat via {}", m.getName());
                                return true;
                            }
                        }
                    }
                }
            } else {
                LOGGER.warn("[UnAI Client Bridge] Network handler is NULL!");
            }

            // Fallback try player methods directly
            if (player != null) {
                for (Method m : player.getClass().getMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 1 && p[0] == String.class) {
                        if (m.getName().equals("chat") || m.getName().equals("sendChat") || m.getName().equals("method_44098")) {
                            m.setAccessible(true);
                            m.invoke(player, message);
                            LOGGER.info("[UnAI Client Bridge] Invoked player chat fallback via {}", m.getName());
                            return true;
                        }
                    }
                }
            }
            return false;
        } catch (Throwable t) {
            LOGGER.error("[UnAI Client Bridge] Error sending chat: {}", t.getMessage(), t);
            return false;
        }
    }

    private Class<?> findClass(String... names) {
        for (String n : names) {
            try {
                return Class.forName(n);
            } catch (ClassNotFoundException ignored) {}
        }
        return null;
    }

    private Method findMethod(Class<?> clazz, String... names) {
        for (String n : names) {
            for (Method m : clazz.getMethods()) {
                if (m.getName().equals(n)) return m;
            }
            for (Method m : clazz.getDeclaredMethods()) {
                if (m.getName().equals(n)) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        return null;
    }

    private Object getStaticFieldValue(Class<?> type, String... names) {
        for (String name : names) {
            Class<?> current = type;
            while (current != null && current != Object.class) {
                try {
                    Field field = current.getDeclaredField(name);
                    field.setAccessible(true);
                    return field.get(null);
                } catch (NoSuchFieldException ignored) {
                    current = current.getSuperclass();
                } catch (Throwable ignored) {
                    break;
                }
            }
        }
        return null;
    }

    private Object getFieldValue(Object obj, String... names) {
        Class<?> clz = obj.getClass();
        while (clz != null && clz != Object.class) {
            for (String n : names) {
                try {
                    Field f = clz.getDeclaredField(n);
                    f.setAccessible(true);
                    return f.get(obj);
                } catch (NoSuchFieldException ignored) {}
                catch (IllegalAccessException ignored) {}
            }
            clz = clz.getSuperclass();
        }
        return null;
    }

    private Field findField(Class<?> clazz, String name) {
        Class<?> cur = clazz;
        while (cur != null && cur != Object.class) {
            try {
                Field f = cur.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                cur = cur.getSuperclass();
            }
        }
        return null;
    }

    private JsonObject doEval(Object mc, String code) {
        JsonObject result = new JsonObject();
        long startTime = System.currentTimeMillis();
        try {
            Object player = getFieldValue(mc, "player", "field_1724");
            Object world = getFieldValue(mc, "world", "field_1687");
            Object screen = getFieldValue(mc, "screen", "field_1755");

            Object evalResult = null;
            boolean evaluated = false;

            // 1. Try javax.script.ScriptEngineManager (Rhino, Groovy, Nashorn, JS)
            try {
                javax.script.ScriptEngineManager sem = new javax.script.ScriptEngineManager();
                javax.script.ScriptEngine engine = sem.getEngineByName("JavaScript");
                if (engine == null) engine = sem.getEngineByName("rhino");
                if (engine == null) engine = sem.getEngineByName("nashorn");
                if (engine == null) engine = sem.getEngineByName("groovy");
                if (engine != null) {
                    engine.put("mc", mc);
                    engine.put("player", player);
                    engine.put("world", world);
                    engine.put("screen", screen);
                    evalResult = engine.eval(code);
                    evaluated = true;
                }
            } catch (Throwable ignored) {}

            // 2. Reflection / Property chain introspection
            if (!evaluated) {
                evalResult = evaluateExpression(mc, player, world, screen, code);
            }

            long elapsed = System.currentTimeMillis() - startTime;
            result.addProperty("executed", true);
            result.addProperty("execution_time_ms", elapsed);
            if (evalResult != null) {
                result.addProperty("result", String.valueOf(evalResult));
                result.addProperty("type", evalResult.getClass().getName());
            } else {
                result.add("result", com.google.gson.JsonNull.INSTANCE);
            }
        } catch (Throwable t) {
            long elapsed = System.currentTimeMillis() - startTime;
            result.addProperty("executed", false);
            result.addProperty("execution_time_ms", elapsed);
            result.addProperty("error", t.toString());
            java.io.StringWriter sw = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(sw));
            result.addProperty("stacktrace", sw.toString());
        }
        return result;
    }

    private Object evaluateExpression(Object mc, Object player, Object world, Object screen, String expr) throws Exception {
        String clean = expr.trim();
        Object target = player != null ? player : mc;
        String path = clean;

        if (clean.startsWith("mc.")) {
            target = mc;
            path = clean.substring(3);
        } else if (clean.startsWith("player.")) {
            target = player;
            path = clean.substring(7);
        } else if (clean.startsWith("world.")) {
            target = world;
            path = clean.substring(6);
        } else if (clean.startsWith("screen.")) {
            target = screen;
            path = clean.substring(7);
        }

        if (target == null) {
            return "Target context is null (not in game / no screen)";
        }

        String[] parts = path.split("\\.");
        Object cur = target;
        for (String part : parts) {
            if (cur == null) break;
            part = part.trim();
            if (part.endsWith("()")) {
                String mName = part.substring(0, part.length() - 2);
                Method m = findMethod(cur.getClass(), mName);
                if (m != null) {
                    m.setAccessible(true);
                    cur = m.invoke(cur);
                } else {
                    throw new NoSuchMethodException("Method " + mName + " not found on " + cur.getClass().getName());
                }
            } else if (!part.isEmpty()) {
                Field f = findField(cur.getClass(), part);
                if (f != null) {
                    f.setAccessible(true);
                    cur = f.get(cur);
                } else {
                    String getter = "get" + Character.toUpperCase(part.charAt(0)) + part.substring(1);
                    Method m = findMethod(cur.getClass(), getter);
                    if (m != null) {
                        m.setAccessible(true);
                        cur = m.invoke(cur);
                    } else {
                        throw new NoSuchFieldException("Field/getter " + part + " not found on " + cur.getClass().getName());
                    }
                }
            }
        }
        return cur;
    }
}
