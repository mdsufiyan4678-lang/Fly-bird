package com.example;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * MultiplayerManager coordinates two-player online multiplayer rooms, 6-digit room codes,
 * player readiness, synchronized match start seeds, real-time opponent score & alive/eliminated
 * states, reconnect handling, and server-authoritative match results.
 *
 * Immediately generates and displays the 6-digit Room Code on room creation so the UI
 * never hangs waiting on cold-starting cloud servers.
 */
public class MultiplayerManager implements WebSocketClient.Listener {
    private static final String TAG = "MultiplayerManager";

    public enum RoomState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED_IDLE,
        WAITING_IN_ROOM,
        READY_TO_START,
        IN_MATCH,
        MATCH_FINISHED
    }

    public static class PlayerSlot {
        public String playerId = "";
        public String playerName = "";
        public int birdSkinId = 0;
        public boolean ready = false;
        public boolean connected = true;
        public int score = 0;
        public boolean alive = true;
        public float normalizedY = 0.5f;
    }

    public static class MatchResult {
        public String matchId = "";
        public String roomCode = "";
        public String winnerId = "";
        public String winnerName = "";
        public boolean isDraw = false;
        public String player1Name = "";
        public int player1Score = 0;
        public String player2Name = "";
        public int player2Score = 0;
        public int rewardCoins = 0;
        public String reason = "";
    }

    public interface MultiplayerListener {
        void onStateChanged(RoomState state, String statusText);
        void onRoomUpdated(String roomCode, List<PlayerSlot> players);
        void onMatchStart(String matchId, long obstacleSeed);
        void onOpponentUpdated(PlayerSlot opponent);
        void onMatchResult(MatchResult result);
        void onMultiplayerError(String code, String message);
    }

    private final GameManager gameManager;
    private final WebSocketClient wsClient;
    private final MultiplayerListener listener;
    private final Handler mainHandler;
    private final Random random = new Random();

    private RoomState currentState = RoomState.DISCONNECTED;
    private String currentRoomCode = "";
    private String currentMatchId = "";
    private String pendingAction = null; // "CREATE", "JOIN", "RECONNECT"
    private String pendingJoinCode = "";
    private boolean isRelayRoomHost = false;
    private final List<PlayerSlot> roomPlayers = new ArrayList<>();
    private MatchResult lastMatchResult = null;
    private String statusBanner = "Ready to create or join a 2-player room";
    private int reconnectAttempts = 0;

    public MultiplayerManager(GameManager gameManager, MultiplayerListener listener) {
        this.gameManager = gameManager;
        this.listener = listener;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.wsClient = new WebSocketClient(this);
    }

    public RoomState getCurrentState() {
        return currentState;
    }

    public String getCurrentRoomCode() {
        return currentRoomCode;
    }

    public String getStatusBanner() {
        return statusBanner;
    }

    public List<PlayerSlot> getRoomPlayers() {
        return new ArrayList<>(roomPlayers);
    }

    public MatchResult getLastMatchResult() {
        return lastMatchResult;
    }

    public PlayerSlot getOpponentSlot() {
        String myId = gameManager.getClientSessionId();
        for (PlayerSlot slot : roomPlayers) {
            if (!slot.playerId.equals(myId)) {
                return slot;
            }
        }
        return null;
    }

    public PlayerSlot getMySlot() {
        String myId = gameManager.getClientSessionId();
        for (PlayerSlot slot : roomPlayers) {
            if (slot.playerId.equals(myId)) {
                return slot;
            }
        }
        return null;
    }

    /**
     * Immediately generates a 6-digit Room Code and opens the Waiting for Opponent screen
     * while establishing the WebSocket room subscription in the background.
     */
    public void createRoom() {
        pendingAction = "CREATE";
        pendingJoinCode = "";
        reconnectAttempts = 0;
        isRelayRoomHost = true;

        // Immediately generate 6-digit room code so the user sees it with zero delay
        currentRoomCode = String.valueOf(100000 + random.nextInt(900000));
        roomPlayers.clear();
        PlayerSlot hostSlot = new PlayerSlot();
        hostSlot.playerId = gameManager.getClientSessionId();
        hostSlot.playerName = gameManager.getPlayerName();
        hostSlot.birdSkinId = gameManager.getSelectedBirdId();
        hostSlot.ready = false;
        hostSlot.connected = true;
        roomPlayers.add(hostSlot);

        updateState(RoomState.WAITING_IN_ROOM,
                "Room #" + currentRoomCode + " Created! Share this 6-digit code with Player 2.");
        if (listener != null) {
            listener.onRoomUpdated(currentRoomCode, getRoomPlayers());
        }

        wsClient.subscribeRelayRoom(currentRoomCode);
        if (wsClient.isConnected()) {
            executeCreateRoom();
        } else {
            wsClient.connect(gameManager.getWsServerUrl());
        }
    }

    /**
     * Validates the 6-digit room code and joins an existing room.
     */
    public void joinRoom(String sixDigitCode) {
        if (sixDigitCode == null) {
            notifyError("INVALID_ROOM_CODE", "Please enter a 6-digit room code.");
            return;
        }
        String cleaned = sixDigitCode.trim();
        if (!cleaned.matches("^\\d{6}$")) {
            notifyError("INVALID_ROOM_CODE", "Room code must be exactly 6 digits (0-9).");
            return;
        }
        pendingAction = "JOIN";
        pendingJoinCode = cleaned;
        reconnectAttempts = 0;
        isRelayRoomHost = false;

        // Immediately enter room screen with code while connecting to host
        currentRoomCode = cleaned;
        roomPlayers.clear();
        PlayerSlot mySlot = new PlayerSlot();
        mySlot.playerId = gameManager.getClientSessionId();
        mySlot.playerName = gameManager.getPlayerName();
        mySlot.birdSkinId = gameManager.getSelectedBirdId();
        mySlot.ready = false;
        mySlot.connected = true;
        roomPlayers.add(mySlot);

        updateState(RoomState.WAITING_IN_ROOM, "Joining Room #" + cleaned + "...");
        if (listener != null) {
            listener.onRoomUpdated(currentRoomCode, getRoomPlayers());
        }

        wsClient.subscribeRelayRoom(cleaned);
        if (wsClient.isConnected()) {
            executeJoinRoom(cleaned);
        } else {
            wsClient.connect(gameManager.getWsServerUrl());
        }
    }

    /**
     * Toggles or sets player readiness in the room waiting screen.
     */
    public void setPlayerReady(boolean ready) {
        if (currentRoomCode.isEmpty()) {
            notifyError("NOT_IN_ROOM", "You are not currently in an active room.");
            return;
        }
        try {
            PlayerSlot mySlot = getMySlot();
            if (mySlot != null) {
                mySlot.ready = ready;
            }
            if (listener != null) {
                listener.onRoomUpdated(currentRoomCode, getRoomPlayers());
            }

            JSONObject msg = new JSONObject();
            msg.put("type", "SET_READY");
            msg.put("roomCode", currentRoomCode);
            msg.put("playerId", gameManager.getClientSessionId());
            msg.put("ready", ready);
            wsClient.sendJson(msg);

            if (wsClient.isUsingRelayMode()) {
                handleRelaySetReady(gameManager.getClientSessionId(), ready);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to send SET_READY: " + e.getMessage());
        }
    }

    /**
     * Sends real-time player score, normalized vertical bird position, and alive state.
     */
    public void sendScoreAndPositionUpdate(int score, int coins, float normalizedY, boolean alive, long elapsedMs) {
        if (currentRoomCode.isEmpty() || currentState != RoomState.IN_MATCH) {
            return;
        }
        try {
            PlayerSlot mySlot = getMySlot();
            if (mySlot != null) {
                mySlot.score = score;
                mySlot.alive = alive;
                mySlot.normalizedY = normalizedY;
            }

            JSONObject msg = new JSONObject();
            msg.put("type", wsClient.isUsingRelayMode() ? "OPPONENT_STATE" : "SCORE_UPDATE");
            msg.put("roomCode", currentRoomCode);
            msg.put("matchId", currentMatchId);
            msg.put("playerId", gameManager.getClientSessionId());
            msg.put("playerName", gameManager.getPlayerName());
            msg.put("score", score);
            msg.put("coins", coins);
            msg.put("normalizedY", Math.max(0f, Math.min(1f, normalizedY)));
            msg.put("alive", alive);
            msg.put("elapsedMs", elapsedMs);
            wsClient.sendJson(msg);
        } catch (Exception e) {
            Log.w(TAG, "Error sending SCORE_UPDATE: " + e.getMessage());
        }
    }

    /**
     * Notifies the server / room coordinator that the local player collided with an obstacle.
     */
    public void sendPlayerEliminated(int finalScore, int coins, long elapsedMs) {
        if (currentRoomCode.isEmpty()) {
            return;
        }
        try {
            PlayerSlot mySlot = getMySlot();
            if (mySlot != null) {
                mySlot.score = finalScore;
                mySlot.alive = false;
            }

            JSONObject msg = new JSONObject();
            msg.put("type", "PLAYER_ELIMINATED");
            msg.put("roomCode", currentRoomCode);
            msg.put("matchId", currentMatchId);
            msg.put("playerId", gameManager.getClientSessionId());
            msg.put("playerName", gameManager.getPlayerName());
            msg.put("finalScore", finalScore);
            msg.put("coins", coins);
            msg.put("elapsedMs", elapsedMs);
            wsClient.sendJson(msg);

            if (wsClient.isUsingRelayMode()) {
                checkRelayMatchCompletion();
            }
        } catch (Exception e) {
            Log.w(TAG, "Error sending PLAYER_ELIMINATED: " + e.getMessage());
        }
    }

    /**
     * Requests a rematch in the current room after a match concludes.
     */
    public void requestPlayAgain() {
        if (currentRoomCode.isEmpty()) {
            return;
        }
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "PLAY_AGAIN");
            msg.put("roomCode", currentRoomCode);
            msg.put("playerId", gameManager.getClientSessionId());
            wsClient.sendJson(msg);

            if (wsClient.isUsingRelayMode()) {
                handleRelaySetReady(gameManager.getClientSessionId(), true);
            }
        } catch (Exception e) {
            Log.w(TAG, "Error sending PLAY_AGAIN: " + e.getMessage());
        }
    }

    /**
     * Leaves the active room and returns to the multiplayer lobby.
     */
    public void leaveRoom() {
        if (wsClient.isConnected() && !currentRoomCode.isEmpty()) {
            try {
                JSONObject msg = new JSONObject();
                msg.put("type", "LEAVE_ROOM");
                msg.put("roomCode", currentRoomCode);
                msg.put("playerId", gameManager.getClientSessionId());
                wsClient.sendJson(msg);
            } catch (Exception ignored) {
            }
        }
        currentRoomCode = "";
        currentMatchId = "";
        pendingAction = null;
        isRelayRoomHost = false;
        roomPlayers.clear();
        lastMatchResult = null;
        wsClient.disconnect();
        updateState(RoomState.DISCONNECTED, "Returned to Multiplayer Lobby");
    }

    public void attemptReconnect() {
        if (currentRoomCode.isEmpty()) {
            return;
        }
        pendingAction = "RECONNECT";
        wsClient.connect(gameManager.getWsServerUrl());
    }

    private void executeCreateRoom() {
        if (wsClient.isUsingRelayMode()) {
            wsClient.subscribeRelayRoom(currentRoomCode);
            updateState(RoomState.WAITING_IN_ROOM,
                    "Room #" + currentRoomCode + " Live! Share 6-digit code with Player 2.");
            if (listener != null) {
                listener.onRoomUpdated(currentRoomCode, getRoomPlayers());
            }
            return;
        }

        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "CREATE_ROOM");
            msg.put("playerId", gameManager.getClientSessionId());
            msg.put("playerName", gameManager.getPlayerName());
            msg.put("birdSkinId", gameManager.getSelectedBirdId());
            wsClient.sendJson(msg);
        } catch (Exception e) {
            notifyError("CLIENT_ERROR", "Failed to create room request: " + e.getMessage());
        }
    }

    private void executeJoinRoom(String code) {
        if (wsClient.isUsingRelayMode()) {
            wsClient.subscribeRelayRoom(code);
        }
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "JOIN_ROOM");
            msg.put("roomCode", code);
            msg.put("playerId", gameManager.getClientSessionId());
            msg.put("playerName", gameManager.getPlayerName());
            msg.put("birdSkinId", gameManager.getSelectedBirdId());
            wsClient.sendJson(msg);
        } catch (Exception e) {
            notifyError("CLIENT_ERROR", "Failed to send join request: " + e.getMessage());
        }
    }

    private void executeReconnectRoom() {
        if (wsClient.isUsingRelayMode()) {
            wsClient.subscribeRelayRoom(currentRoomCode);
        }
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "RECONNECT_ROOM");
            msg.put("roomCode", currentRoomCode);
            msg.put("playerId", gameManager.getClientSessionId());
            msg.put("playerName", gameManager.getPlayerName());
            msg.put("birdSkinId", gameManager.getSelectedBirdId());
            wsClient.sendJson(msg);
        } catch (Exception e) {
            notifyError("CLIENT_ERROR", "Failed to send reconnect request: " + e.getMessage());
        }
    }

    private void handleRelaySetReady(String playerId, boolean ready) {
        for (PlayerSlot slot : roomPlayers) {
            if (slot.playerId.equals(playerId)) {
                slot.ready = ready;
            }
        }
        if (isRelayRoomHost) {
            if (roomPlayers.size() == 2 && roomPlayers.get(0).ready && roomPlayers.get(1).ready) {
                broadcastRelayMatchStart();
            } else {
                broadcastRelayRoomUpdate();
            }
        }
    }

    private void broadcastRelayRoomUpdate() {
        try {
            JSONObject payload = new JSONObject();
            payload.put("type", "ROOM_UPDATE");
            payload.put("roomCode", currentRoomCode);
            payload.put("status", roomPlayers.size() == 2 ? "READY" : "WAITING");
            payload.put("players", serializePlayersArray());
            wsClient.sendJson(payload);
            onMessageReceived(payload);
        } catch (Exception ignored) {
        }
    }

    private void broadcastRelayMatchStart() {
        try {
            for (PlayerSlot s : roomPlayers) {
                s.score = 0;
                s.alive = true;
                s.ready = false;
            }
            JSONObject payload = new JSONObject();
            payload.put("type", "MATCH_START");
            payload.put("roomCode", currentRoomCode);
            payload.put("matchId", "m_" + System.currentTimeMillis());
            payload.put("seed", 100000L + random.nextInt(900000000));
            payload.put("players", serializePlayersArray());
            wsClient.sendJson(payload);
            onMessageReceived(payload);
        } catch (Exception ignored) {
        }
    }

    private void checkRelayMatchCompletion() {
        if (!isRelayRoomHost || roomPlayers.size() < 2 || currentState != RoomState.IN_MATCH) {
            return;
        }
        PlayerSlot p1 = roomPlayers.get(0);
        PlayerSlot p2 = roomPlayers.get(1);
        boolean bothOut = !p1.alive && !p2.alive;
        boolean p1AlreadyWon = p1.alive && !p2.alive && p1.score > p2.score;
        boolean p2AlreadyWon = p2.alive && !p1.alive && p2.score > p1.score;

        if (bothOut || p1AlreadyWon || p2AlreadyWon) {
            try {
                boolean isDraw = (p1.score == p2.score);
                String winnerId = isDraw ? "" : (p1.score > p2.score ? p1.playerId : p2.playerId);
                String winnerName = isDraw ? "Draw" : (p1.score > p2.score ? p1.playerName : p2.playerName);

                JSONObject payload = new JSONObject();
                payload.put("type", "MATCH_OVER");
                payload.put("roomCode", currentRoomCode);
                payload.put("matchId", currentMatchId);
                payload.put("winnerId", winnerId);
                payload.put("winnerName", winnerName);
                payload.put("isDraw", isDraw);
                payload.put("p1Name", p1.playerName);
                payload.put("p1Score", p1.score);
                payload.put("p2Name", p2.playerName);
                payload.put("p2Score", p2.score);
                payload.put("rewardCoins", isDraw ? 10 : 25);
                wsClient.sendJson(payload);
                onMessageReceived(payload);
            } catch (Exception ignored) {
            }
        }
    }

    private JSONArray serializePlayersArray() {
        JSONArray arr = new JSONArray();
        for (PlayerSlot s : roomPlayers) {
            try {
                JSONObject o = new JSONObject();
                o.put("playerId", s.playerId);
                o.put("playerName", s.playerName);
                o.put("birdSkinId", s.birdSkinId);
                o.put("ready", s.ready);
                o.put("connected", s.connected);
                o.put("score", s.score);
                o.put("alive", s.alive);
                o.put("normalizedY", s.normalizedY);
                arr.put(o);
            } catch (Exception ignored) {
            }
        }
        return arr;
    }

    @Override
    public void onConnected(boolean usingGlobalRelay) {
        reconnectAttempts = 0;
        if ("CREATE".equals(pendingAction)) {
            pendingAction = null;
            executeCreateRoom();
        } else if ("JOIN".equals(pendingAction)) {
            pendingAction = null;
            executeJoinRoom(pendingJoinCode);
        } else if ("RECONNECT".equals(pendingAction) && !currentRoomCode.isEmpty()) {
            pendingAction = null;
            executeReconnectRoom();
        }
    }

    @Override
    public void onMessageReceived(JSONObject message) {
        String type = message.optString("type", "");

        if (wsClient.isUsingRelayMode() && isRelayRoomHost) {
            if ("JOIN_ROOM".equals(type)) {
                String joinerId = message.optString("playerId", "");
                if (!joinerId.isEmpty() && !joinerId.equals(gameManager.getClientSessionId())) {
                    if (currentState == RoomState.IN_MATCH) {
                        return;
                    }
                    boolean exists = false;
                    for (PlayerSlot s : roomPlayers) {
                        if (s.playerId.equals(joinerId)) {
                            exists = true;
                            break;
                        }
                    }
                    if (!exists && roomPlayers.size() < 2) {
                        PlayerSlot p2 = new PlayerSlot();
                        p2.playerId = joinerId;
                        p2.playerName = message.optString("playerName", "Player 2");
                        p2.birdSkinId = message.optInt("birdSkinId", 0);
                        p2.ready = false;
                        p2.connected = true;
                        roomPlayers.add(p2);
                    }
                    broadcastRelayRoomUpdate();
                }
                return;
            } else if ("SET_READY".equals(type) || "PLAY_AGAIN".equals(type)) {
                String pId = message.optString("playerId", "");
                boolean rdy = "PLAY_AGAIN".equals(type) || message.optBoolean("ready", true);
                if (!pId.equals(gameManager.getClientSessionId())) {
                    handleRelaySetReady(pId, rdy);
                }
                return;
            }
        }

        switch (type) {
            case "ROOM_CREATED":
            case "ROOM_UPDATE": {
                currentRoomCode = message.optString("roomCode", currentRoomCode);
                String roomStatus = message.optString("status", "WAITING");
                parsePlayersArray(message.optJSONArray("players"));

                if ("WAITING".equals(roomStatus) && roomPlayers.size() < 2) {
                    updateState(RoomState.WAITING_IN_ROOM,
                            "Room #" + currentRoomCode + " • Waiting for Player 2...");
                } else {
                    updateState(RoomState.READY_TO_START,
                            "Both players connected! Tap READY to launch match.");
                }
                if (listener != null) {
                    listener.onRoomUpdated(currentRoomCode, getRoomPlayers());
                }
                break;
            }
            case "MATCH_START": {
                currentRoomCode = message.optString("roomCode", currentRoomCode);
                currentMatchId = message.optString("matchId", "m_" + System.currentTimeMillis());
                long seed = message.optLong("seed", System.currentTimeMillis());
                parsePlayersArray(message.optJSONArray("players"));
                for (PlayerSlot slot : roomPlayers) {
                    slot.score = 0;
                    slot.alive = true;
                }
                updateState(RoomState.IN_MATCH, "LIVE 2P MATCH • ROOM #" + currentRoomCode);
                if (listener != null) {
                    listener.onMatchStart(currentMatchId, seed);
                }
                break;
            }
            case "OPPONENT_STATE": {
                String pId = message.optString("playerId", "");
                if (pId.equals(gameManager.getClientSessionId())) {
                    break;
                }
                int score = message.optInt("score", 0);
                boolean alive = message.optBoolean("alive", true);
                float normY = (float) message.optDouble("normalizedY", 0.5);
                for (PlayerSlot slot : roomPlayers) {
                    if (slot.playerId.equals(pId)) {
                        slot.score = score;
                        slot.alive = alive;
                        slot.normalizedY = normY;
                        if (listener != null) {
                            listener.onOpponentUpdated(slot);
                        }
                        break;
                    }
                }
                if (wsClient.isUsingRelayMode()) {
                    checkRelayMatchCompletion();
                }
                break;
            }
            case "PLAYER_ELIMINATED": {
                String pId = message.optString("playerId", "");
                if (!pId.equals(gameManager.getClientSessionId())) {
                    int finalScore = message.optInt("finalScore", 0);
                    for (PlayerSlot slot : roomPlayers) {
                        if (slot.playerId.equals(pId)) {
                            slot.score = finalScore;
                            slot.alive = false;
                            if (listener != null) {
                                listener.onOpponentUpdated(slot);
                            }
                            break;
                        }
                    }
                    if (wsClient.isUsingRelayMode()) {
                        checkRelayMatchCompletion();
                    }
                }
                break;
            }
            case "MATCH_OVER": {
                if (currentState == RoomState.MATCH_FINISHED) {
                    break;
                }
                MatchResult result = new MatchResult();
                result.matchId = message.optString("matchId", currentMatchId);
                result.roomCode = message.optString("roomCode", currentRoomCode);
                result.winnerId = message.optString("winnerId", "");
                result.winnerName = message.optString("winnerName", "Draw");
                result.isDraw = message.optBoolean("isDraw", false);
                result.player1Name = message.optString("p1Name", "Player 1");
                result.player1Score = message.optInt("p1Score", 0);
                result.player2Name = message.optString("p2Name", "Player 2");
                result.player2Score = message.optInt("p2Score", 0);
                result.rewardCoins = message.optInt("rewardCoins", 0);
                result.reason = message.optString("reason", "Match completed");

                String myId = gameManager.getClientSessionId();
                int myReward = result.isDraw ? 10 : (myId.equals(result.winnerId) ? result.rewardCoins : 5);
                result.rewardCoins = myReward;
                gameManager.claimServerValidatedMatchReward(result.matchId, myReward);

                lastMatchResult = result;
                updateState(RoomState.MATCH_FINISHED, "Match Complete! Winner: " + result.winnerName);
                if (listener != null) {
                    listener.onMatchResult(result);
                }
                break;
            }
            case "LEAVE_ROOM": {
                String leaverId = message.optString("playerId", "");
                if (!leaverId.equals(gameManager.getClientSessionId())) {
                    roomPlayers.removeIf(s -> s.playerId.equals(leaverId));
                    updateState(RoomState.WAITING_IN_ROOM, "Opponent left Room #" + currentRoomCode);
                    if (listener != null) {
                        listener.onRoomUpdated(currentRoomCode, getRoomPlayers());
                    }
                }
                break;
            }
            case "ERROR": {
                String code = message.optString("code", "SERVER_ERROR");
                String errMessage = message.optString("message", "Server rejected request.");
                notifyError(code, errMessage);
                break;
            }
            default:
                break;
        }
    }

    private void parsePlayersArray(JSONArray arr) {
        if (arr == null) {
            return;
        }
        roomPlayers.clear();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject obj = arr.optJSONObject(i);
            if (obj == null) continue;
            PlayerSlot slot = new PlayerSlot();
            slot.playerId = obj.optString("playerId", "");
            slot.playerName = obj.optString("playerName", "Player " + (i + 1));
            slot.birdSkinId = obj.optInt("birdSkinId", 0);
            slot.ready = obj.optBoolean("ready", false);
            slot.connected = obj.optBoolean("connected", true);
            slot.score = obj.optInt("score", 0);
            slot.alive = obj.optBoolean("alive", true);
            slot.normalizedY = (float) obj.optDouble("normalizedY", 0.5);
            roomPlayers.add(slot);
        }
    }

    @Override
    public void onDisconnected(int code, String reason, boolean remote) {
        if (!currentRoomCode.isEmpty() && reconnectAttempts < 2 && remote) {
            reconnectAttempts++;
            mainHandler.postDelayed(this::attemptReconnect, 1500);
        }
    }

    @Override
    public void onError(String errorMessage) {
        notifyError("CONNECTION_ERROR", errorMessage);
    }

    private void updateState(RoomState newState, String bannerText) {
        this.currentState = newState;
        this.statusBanner = bannerText;
        if (listener != null) {
            listener.onStateChanged(newState, bannerText);
        }
    }

    private void notifyError(String code, String message) {
        this.statusBanner = message;
        if (listener != null) {
            listener.onMultiplayerError(code, message);
        }
    }
}
