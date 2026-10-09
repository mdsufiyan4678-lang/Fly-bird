package com.example;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.SystemClock;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.TimeZone;

/**
 * GameManager manages persistent player progress, coins, best score, player profile,
 * configurable multiplayer server URL, the 6 original Bird Shop skins, and the
 * anti-exploit Daily Login Reward system using SharedPreferences.
 */
public class GameManager {
    private static final String PREFS_NAME = "fly_bird_game_prefs";
    private static final String KEY_COINS = "coins_balance";
    private static final String KEY_BEST_SCORE = "best_score";
    private static final String KEY_SELECTED_BIRD = "selected_bird_id";
    private static final String KEY_UNLOCKED_BIRDS = "unlocked_bird_ids";
    private static final String KEY_PLAYER_NAME = "player_name";
    private static final String KEY_WS_SERVER_URL = "ws_server_url";
    private static final String KEY_HTTP_SERVER_URL = "http_server_url";
    private static final String KEY_CLIENT_SESSION_ID = "client_session_id";
    private static final String KEY_PROCESSED_MATCH_IDS = "processed_match_ids";

    // Daily Login Reward persistence & anti-exploitation keys
    private static final String KEY_LAST_CLAIM_EPOCH_DAY = "daily_last_claim_epoch_day";
    private static final String KEY_LAST_CLAIM_DATE_STR = "daily_last_claim_date_str";
    private static final String KEY_LAST_CLAIM_WALL_MS = "daily_last_claim_wall_ms";
    private static final String KEY_LAST_CLAIM_ELAPSED_MS = "daily_last_claim_elapsed_ms";
    private static final String KEY_HIGHEST_SEEN_WALL_MS = "daily_highest_seen_wall_ms";
    private static final String KEY_DAILY_STREAK = "daily_login_streak";
    private static final String KEY_DAILY_INTEGRITY_HASH = "daily_integrity_hash";
    private static final String INTEGRITY_SALT = "FlyBird_DailyReward_v1_Salt_9842";

    public static final int DAILY_REWARD_COINS = 50;
    private static final long ONE_DAY_MS = 86_400_000L;
    private static final long MIN_CLAIM_INTERVAL_MS = 20L * 3600L * 1000L; // Minimum 20h between claims across days
    private static final long MAX_CLOCK_DRIFT_MS = 15L * 60L * 1000L; // 15m tolerance between monotonic & wall clock

    public static final String DEFAULT_WS_URL = "wss://fly-bird-1.onrender.com";
    public static final String DEFAULT_HTTP_URL = "https://fly-bird-1.onrender.com";

    /**
     * Clock abstraction to support deterministic Robolectric unit testing of daily rewards
     * and anti-tamper time-travel detection.
     */
    public interface ClockProvider {
        long currentTimeMillis();
        long elapsedRealtime();
    }

    public static class SystemClockProvider implements ClockProvider {
        @Override
        public long currentTimeMillis() {
            return System.currentTimeMillis();
        }

        @Override
        public long elapsedRealtime() {
            return SystemClock.elapsedRealtime();
        }
    }

    public enum DailyRewardStatus {
        AVAILABLE,
        ALREADY_CLAIMED_TODAY,
        CLOCK_TAMPER_DETECTED
    }

    public static class DailyRewardClaimResult {
        public final boolean success;
        public final DailyRewardStatus status;
        public final int coinsAwarded;
        public final int newStreak;
        public final String message;

        public DailyRewardClaimResult(boolean success, DailyRewardStatus status,
                                      int coinsAwarded, int newStreak, String message) {
            this.success = success;
            this.status = status;
            this.coinsAwarded = coinsAwarded;
            this.newStreak = newStreak;
            this.message = message;
        }
    }

    /**
     * Represents one of the 6 original playable bird skins in the Bird Shop.
     */
    public static class BirdSkin {
        public final int id;
        public final String name;
        public final String tagline;
        public final int price;
        public final int primaryColor;
        public final int secondaryColor;
        public final int wingColor;
        public final int glowColor;
        public final int beakColor;

        public BirdSkin(int id, String name, String tagline, int price,
                        int primaryColor, int secondaryColor, int wingColor, int glowColor, int beakColor) {
            this.id = id;
            this.name = name;
            this.tagline = tagline;
            this.price = price;
            this.primaryColor = primaryColor;
            this.secondaryColor = secondaryColor;
            this.wingColor = wingColor;
            this.glowColor = glowColor;
            this.beakColor = beakColor;
        }
    }

    public enum PurchaseResult {
        SUCCESS,
        ALREADY_UNLOCKED,
        INSUFFICIENT_COINS,
        INVALID_BIRD
    }

    private final SharedPreferences prefs;
    private final List<BirdSkin> birdCatalog;
    private final Set<Integer> unlockedBirdIds;
    private final Set<String> processedMatchIds;
    private ClockProvider clockProvider;

    private int coins;
    private int bestScore;
    private int selectedBirdId;
    private String playerName;
    private String wsServerUrl;
    private String httpServerUrl;
    private String clientSessionId;

    // Daily reward state
    private long lastClaimEpochDay;
    private String lastClaimDateStr;
    private long lastClaimWallMs;
    private long lastClaimElapsedMs;
    private long highestSeenWallMs;
    private int dailyStreak;

    public GameManager(Context context) {
        this(context, new SystemClockProvider());
    }

    public GameManager(Context context, ClockProvider clockProvider) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.clockProvider = (clockProvider != null) ? clockProvider : new SystemClockProvider();
        this.birdCatalog = createBirdCatalog();
        this.unlockedBirdIds = new HashSet<>();
        this.processedMatchIds = new HashSet<>();
        loadState();
    }

    public synchronized void setClockProvider(ClockProvider provider) {
        if (provider != null) {
            this.clockProvider = provider;
        }
    }

    private List<BirdSkin> createBirdCatalog() {
        List<BirdSkin> list = new ArrayList<>();
        // 1. Blue Bird — Free
        list.add(new BirdSkin(
                0,
                "Blue Bird",
                "Sky Glider • Free Starter",
                0,
                Color.parseColor("#1E90FF"),
                Color.parseColor("#00E5FF"),
                Color.parseColor("#80D8FF"),
                Color.parseColor("#4000E5FF"),
                Color.parseColor("#FFB300")
        ));
        // 2. Red Bird
        list.add(new BirdSkin(
                1,
                "Red Bird",
                "Crimson Falcon • Agile",
                60,
                Color.parseColor("#E53935"),
                Color.parseColor("#FF5252"),
                Color.parseColor("#FF8A80"),
                Color.parseColor("#40FF5252"),
                Color.parseColor("#FFD54F")
        ));
        // 3. Fire Bird
        list.add(new BirdSkin(
                2,
                "Fire Bird",
                "Solar Phoenix • Flame Aura",
                140,
                Color.parseColor("#FF6D00"),
                Color.parseColor("#FFAB00"),
                Color.parseColor("#FFD740"),
                Color.parseColor("#66FF6D00"),
                Color.parseColor("#FFF59D")
        ));
        // 4. Ice Bird
        list.add(new BirdSkin(
                3,
                "Ice Bird",
                "Frost Crystal • Arctic Trail",
                220,
                Color.parseColor("#00B8D4"),
                Color.parseColor("#84FFFF"),
                Color.parseColor("#E0F7FA"),
                Color.parseColor("#6684FFFF"),
                Color.parseColor("#40C4FF")
        ));
        // 5. Neon Bird
        list.add(new BirdSkin(
                4,
                "Neon Bird",
                "Cyber Pulse • Synth Glow",
                350,
                Color.parseColor("#D500F9"),
                Color.parseColor("#00E5FF"),
                Color.parseColor("#FF80AB"),
                Color.parseColor("#7700E5FF"),
                Color.parseColor("#FFFF00")
        ));
        // 6. Golden Bird
        list.add(new BirdSkin(
                5,
                "Golden Bird",
                "Royal Legend • 24K Crown",
                500,
                Color.parseColor("#FFB300"),
                Color.parseColor("#FFD700"),
                Color.parseColor("#FFF176"),
                Color.parseColor("#88FFD700"),
                Color.parseColor("#FF6F00")
        ));
        return Collections.unmodifiableList(list);
    }

    private void loadState() {
        coins = prefs.getInt(KEY_COINS, 25);
        bestScore = prefs.getInt(KEY_BEST_SCORE, 0);
        selectedBirdId = prefs.getInt(KEY_SELECTED_BIRD, 0);

        unlockedBirdIds.clear();
        unlockedBirdIds.add(0); // Blue Bird is always free and unlocked
        Set<String> storedUnlocked = prefs.getStringSet(KEY_UNLOCKED_BIRDS, null);
        if (storedUnlocked != null) {
            for (String s : storedUnlocked) {
                try {
                    unlockedBirdIds.add(Integer.parseInt(s));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        if (!unlockedBirdIds.contains(selectedBirdId)) {
            selectedBirdId = 0;
        }

        processedMatchIds.clear();
        Set<String> storedMatches = prefs.getStringSet(KEY_PROCESSED_MATCH_IDS, null);
        if (storedMatches != null) {
            processedMatchIds.addAll(storedMatches);
        }

        playerName = prefs.getString(KEY_PLAYER_NAME, null);
        if (playerName == null || playerName.trim().isEmpty()) {
            playerName = "Pilot" + (1000 + new Random().nextInt(9000));
            prefs.edit().putString(KEY_PLAYER_NAME, playerName).apply();
        }

        clientSessionId = prefs.getString(KEY_CLIENT_SESSION_ID, null);
        if (clientSessionId == null || clientSessionId.trim().isEmpty()) {
            clientSessionId = "p_" + Long.toHexString( clockProvider.currentTimeMillis()) + "_" + (100 + new Random().nextInt(900));
            prefs.edit().putString(KEY_CLIENT_SESSION_ID, clientSessionId).apply();
        }

        String defaultWs = DEFAULT_WS_URL;
        String defaultHttp = DEFAULT_HTTP_URL;
        try {
            if (BuildConfig.MULTIPLAYER_WS_URL != null && !BuildConfig.MULTIPLAYER_WS_URL.trim().isEmpty()) {
                defaultWs = BuildConfig.MULTIPLAYER_WS_URL.trim();
            }
            if (BuildConfig.MULTIPLAYER_HTTP_URL != null && !BuildConfig.MULTIPLAYER_HTTP_URL.trim().isEmpty()) {
                defaultHttp = BuildConfig.MULTIPLAYER_HTTP_URL.trim();
            }
        } catch (Exception ignored) {
        }

        wsServerUrl = prefs.getString(KEY_WS_SERVER_URL, defaultWs);
        if (wsServerUrl == null || wsServerUrl.contains("YOUR_SERVER_DOMAIN")) {
            wsServerUrl = defaultWs;
            prefs.edit().putString(KEY_WS_SERVER_URL, wsServerUrl).apply();
        }
        httpServerUrl = prefs.getString(KEY_HTTP_SERVER_URL, defaultHttp);
        if (httpServerUrl == null || httpServerUrl.contains("YOUR_SERVER_DOMAIN")) {
            httpServerUrl = defaultHttp;
            prefs.edit().putString(KEY_HTTP_SERVER_URL, httpServerUrl).apply();
        }

        // Load daily login reward state
        lastClaimEpochDay = prefs.getLong(KEY_LAST_CLAIM_EPOCH_DAY, -1L);
        lastClaimDateStr = prefs.getString(KEY_LAST_CLAIM_DATE_STR, "");
        lastClaimWallMs = prefs.getLong(KEY_LAST_CLAIM_WALL_MS, 0L);
        lastClaimElapsedMs = prefs.getLong(KEY_LAST_CLAIM_ELAPSED_MS, -1L);
        highestSeenWallMs = prefs.getLong(KEY_HIGHEST_SEEN_WALL_MS, 0L);
        dailyStreak = prefs.getInt(KEY_DAILY_STREAK, 0);

        String savedHash = prefs.getString(KEY_DAILY_INTEGRITY_HASH, null);
        if (savedHash != null && lastClaimEpochDay >= 0) {
            String expectedHash = computeDailyIntegrityHash(lastClaimEpochDay, lastClaimWallMs, dailyStreak);
            if (!expectedHash.equals(savedHash)) {
                // Tampered SharedPreferences detected: lock today's claim to current day
                long nowMs = clockProvider.currentTimeMillis();
                lastClaimEpochDay = getUtcEpochDay(nowMs);
                lastClaimDateStr = formatUtcDate(nowMs);
                lastClaimWallMs = nowMs;
                highestSeenWallMs = Math.max(highestSeenWallMs, nowMs);
                dailyStreak = 1;
                persistDailyRewardState();
            }
        }

        long nowWall = clockProvider.currentTimeMillis();
        if (nowWall > highestSeenWallMs) {
            highestSeenWallMs = nowWall;
            prefs.edit().putLong(KEY_HIGHEST_SEEN_WALL_MS, highestSeenWallMs).apply();
        }
    }

    // --- Daily Login Reward Logic & Anti-Exploitation ---

    private long getUtcEpochDay(long wallTimeMs) {
        return Math.max(0L, wallTimeMs / ONE_DAY_MS);
    }

    private String formatUtcDate(long wallTimeMs) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(new Date(wallTimeMs));
    }

    private String computeDailyIntegrityHash(long epochDay, long claimWallMs, int streak) {
        try {
            String payload = clientSessionId + ":" + epochDay + ":" + claimWallMs + ":" + streak + ":" + INTEGRITY_SALT;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16 && i < bytes.length; i++) {
                sb.append(String.format(Locale.US, "%02x", bytes[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(epochDay ^ claimWallMs ^ streak);
        }
    }

    /**
     * Evaluates whether the player is currently eligible to claim the daily login reward.
     * Prevents:
     * 1. Multiple claims on the same UTC calendar day.
     * 2. Rolling the system clock backward prior to highestSeenWallMs or lastClaimWallMs.
     * 3. Rolling the system clock forward artificially within the same device boot session
     *    (compared against monotonic SystemClock.elapsedRealtime()).
     */
    public synchronized DailyRewardStatus getDailyRewardStatus() {
        long nowWallMs = clockProvider.currentTimeMillis();
        long nowElapsedMs = clockProvider.elapsedRealtime();

        // 1. Check for backward clock rollback manipulation
        if (highestSeenWallMs > 0 && nowWallMs + 60_000L < highestSeenWallMs) {
            return DailyRewardStatus.CLOCK_TAMPER_DETECTED;
        }
        if (lastClaimWallMs > 0 && nowWallMs + 60_000L < lastClaimWallMs) {
            return DailyRewardStatus.CLOCK_TAMPER_DETECTED;
        }

        // 2. Check for forward clock jump within the same boot session
        if (lastClaimWallMs > 0 && lastClaimElapsedMs >= 0 && nowElapsedMs >= lastClaimElapsedMs) {
            long wallDelta = nowWallMs - lastClaimWallMs;
            long monotonicDelta = nowElapsedMs - lastClaimElapsedMs;
            if (wallDelta - monotonicDelta > MAX_CLOCK_DRIFT_MS) {
                return DailyRewardStatus.CLOCK_TAMPER_DETECTED;
            }
        }

        // Update high-water mark when clock is valid
        if (nowWallMs > highestSeenWallMs) {
            highestSeenWallMs = nowWallMs;
            prefs.edit().putLong(KEY_HIGHEST_SEEN_WALL_MS, highestSeenWallMs).apply();
        }

        // First-time ever claim
        if (lastClaimEpochDay < 0) {
            return DailyRewardStatus.AVAILABLE;
        }

        long currentEpochDay = getUtcEpochDay(nowWallMs);
        String currentDateStr = formatUtcDate(nowWallMs);

        if (currentEpochDay <= lastClaimEpochDay || currentDateStr.equals(lastClaimDateStr)) {
            return DailyRewardStatus.ALREADY_CLAIMED_TODAY;
        }

        // Also enforce a minimum 20-hour real gap if claimed late on the previous day
        // unless a full calendar day + 20h has elapsed
        if (lastClaimWallMs > 0 && (nowWallMs - lastClaimWallMs) < MIN_CLAIM_INTERVAL_MS) {
            return DailyRewardStatus.ALREADY_CLAIMED_TODAY;
        }

        return DailyRewardStatus.AVAILABLE;
    }

    public synchronized boolean canClaimDailyReward() {
        return getDailyRewardStatus() == DailyRewardStatus.AVAILABLE;
    }

    /**
     * Atomically claims the daily login reward (50 coins), updates the consecutive login streak,
     * and persists the anti-tamper metadata in SharedPreferences.
     */
    public synchronized DailyRewardClaimResult claimDailyReward() {
        DailyRewardStatus status = getDailyRewardStatus();
        if (status == DailyRewardStatus.ALREADY_CLAIMED_TODAY) {
            return new DailyRewardClaimResult(
                    false,
                    status,
                    0,
                    dailyStreak,
                    "Daily reward already claimed today! Come back tomorrow."
            );
        }
        if (status == DailyRewardStatus.CLOCK_TAMPER_DETECTED) {
            return new DailyRewardClaimResult(
                    false,
                    status,
                    0,
                    dailyStreak,
                    "System clock change detected. Please restore automatic date & time."
            );
        }

        long nowWallMs = clockProvider.currentTimeMillis();
        long nowElapsedMs = clockProvider.elapsedRealtime();
        long currentEpochDay = getUtcEpochDay(nowWallMs);

        if (lastClaimEpochDay >= 0 && (currentEpochDay - lastClaimEpochDay) == 1L) {
            dailyStreak = Math.max(1, dailyStreak + 1);
        } else {
            dailyStreak = 1;
        }

        lastClaimEpochDay = currentEpochDay;
        lastClaimDateStr = formatUtcDate(nowWallMs);
        lastClaimWallMs = nowWallMs;
        lastClaimElapsedMs = nowElapsedMs;
        highestSeenWallMs = Math.max(highestSeenWallMs, nowWallMs);

        coins += DAILY_REWARD_COINS;
        persistDailyRewardState();

        String msg = String.format(
                Locale.US,
                "Daily Reward Claimed! +%d Coins (Day %d Streak)",
                DAILY_REWARD_COINS,
                dailyStreak
        );
        return new DailyRewardClaimResult(true, DailyRewardStatus.AVAILABLE, DAILY_REWARD_COINS, dailyStreak, msg);
    }

    private void persistDailyRewardState() {
        String integrityHash = computeDailyIntegrityHash(lastClaimEpochDay, lastClaimWallMs, dailyStreak);
        prefs.edit()
                .putInt(KEY_COINS, coins)
                .putLong(KEY_LAST_CLAIM_EPOCH_DAY, lastClaimEpochDay)
                .putString(KEY_LAST_CLAIM_DATE_STR, lastClaimDateStr)
                .putLong(KEY_LAST_CLAIM_WALL_MS, lastClaimWallMs)
                .putLong(KEY_LAST_CLAIM_ELAPSED_MS, lastClaimElapsedMs)
                .putLong(KEY_HIGHEST_SEEN_WALL_MS, highestSeenWallMs)
                .putInt(KEY_DAILY_STREAK, dailyStreak)
                .putString(KEY_DAILY_INTEGRITY_HASH, integrityHash)
                .apply();
    }

    public synchronized int getDailyStreak() {
        return Math.max(1, dailyStreak);
    }

    public synchronized String getLastClaimDateStr() {
        return lastClaimDateStr;
    }

    /**
     * Returns a human-readable countdown label until the next daily reward becomes available.
     */
    public synchronized String getNextDailyRewardStatusLabel() {
        DailyRewardStatus status = getDailyRewardStatus();
        if (status == DailyRewardStatus.AVAILABLE) {
            return "🎁 CLAIM DAILY +" + DAILY_REWARD_COINS + " COINS!";
        }
        if (status == DailyRewardStatus.CLOCK_TAMPER_DETECTED) {
            return "⚠ CLOCK SYNC REQUIRED";
        }
        long nowWallMs = clockProvider.currentTimeMillis();
        long nextDayStartMs = (getUtcEpochDay(nowWallMs) + 1L) * ONE_DAY_MS;
        long minIntervalReadyMs = lastClaimWallMs + MIN_CLAIM_INTERVAL_MS;
        long targetMs = Math.max(nextDayStartMs, minIntervalReadyMs);
        long remainingMs = Math.max(0L, targetMs - nowWallMs);
        long hours = remainingMs / (3600L * 1000L);
        long minutes = (remainingMs % (3600L * 1000L)) / (60L * 1000L);
        return String.format(Locale.US, "✓ DAILY CLAIMED (%dh %02dm)", hours, minutes);
    }

    // --- Bird Shop & Game Progress ---

    public List<BirdSkin> getBirdCatalog() {
        return birdCatalog;
    }

    public BirdSkin getBirdSkin(int id) {
        for (BirdSkin skin : birdCatalog) {
            if (skin.id == id) {
                return skin;
            }
        }
        return birdCatalog.get(0);
    }

    public BirdSkin getSelectedBirdSkin() {
        return getBirdSkin(selectedBirdId);
    }

    public int getSelectedBirdId() {
        return selectedBirdId;
    }

    public boolean isBirdUnlocked(int birdId) {
        return birdId == 0 || unlockedBirdIds.contains(birdId);
    }

    /**
     * Purchases and unlocks a bird skin if not already owned and player has enough coins.
     * Prevents repeated charges for previously purchased birds.
     */
    public synchronized PurchaseResult purchaseOrSelectBird(int birdId) {
        BirdSkin skin = null;
        for (BirdSkin item : birdCatalog) {
            if (item.id == birdId) {
                skin = item;
                break;
            }
        }
        if (skin == null) {
            return PurchaseResult.INVALID_BIRD;
        }

        if (isBirdUnlocked(birdId)) {
            selectBird(birdId);
            return PurchaseResult.ALREADY_UNLOCKED;
        }

        if (coins < skin.price) {
            return PurchaseResult.INSUFFICIENT_COINS;
        }

        coins -= skin.price;
        unlockedBirdIds.add(birdId);
        selectedBirdId = birdId;
        saveUnlockedBirdsAndCoins();
        return PurchaseResult.SUCCESS;
    }

    public synchronized boolean selectBird(int birdId) {
        if (!isBirdUnlocked(birdId)) {
            return false;
        }
        selectedBirdId = birdId;
        prefs.edit().putInt(KEY_SELECTED_BIRD, selectedBirdId).apply();
        return true;
    }

    private void saveUnlockedBirdsAndCoins() {
        Set<String> serialized = new HashSet<>();
        for (Integer id : unlockedBirdIds) {
            serialized.add(String.valueOf(id));
        }
        prefs.edit()
                .putInt(KEY_COINS, coins)
                .putInt(KEY_SELECTED_BIRD, selectedBirdId)
                .putStringSet(KEY_UNLOCKED_BIRDS, serialized)
                .apply();
    }

    public synchronized int getCoins() {
        return coins;
    }

    public synchronized void addCoins(int amount) {
        if (amount <= 0) {
            return;
        }
        coins += amount;
        prefs.edit().putInt(KEY_COINS, coins).apply();
    }

    /**
     * Credits server-validated multiplayer match rewards once per unique matchId.
     */
    public synchronized boolean claimServerValidatedMatchReward(String matchId, int rewardCoins) {
        if (matchId == null || matchId.trim().isEmpty() || rewardCoins <= 0 || rewardCoins > 100) {
            return false;
        }
        if (processedMatchIds.contains(matchId)) {
            return false;
        }
        processedMatchIds.add(matchId);
        coins += rewardCoins;
        prefs.edit()
                .putInt(KEY_COINS, coins)
                .putStringSet(KEY_PROCESSED_MATCH_IDS, new HashSet<>(processedMatchIds))
                .apply();
        return true;
    }

    public synchronized int getBestScore() {
        return bestScore;
    }

    /**
     * Records a completed single-player or multiplayer run, updating bestScore if beaten.
     * Also awards 1 bonus coin per 5 pipes passed in addition to collected gold coins.
     */
    public synchronized boolean recordCompletedRun(int score, int coinsCollectedInRun) {
        boolean isNewBest = false;
        if (score > bestScore) {
            bestScore = score;
            isNewBest = true;
        }
        int pipeBonusCoins = Math.max(0, score / 5);
        int totalEarned = Math.max(0, coinsCollectedInRun) + pipeBonusCoins;
        coins += totalEarned;

        prefs.edit()
                .putInt(KEY_BEST_SCORE, bestScore)
                .putInt(KEY_COINS, coins)
                .apply();
        return isNewBest;
    }

    public String getPlayerName() {
        return playerName;
    }

    public void setPlayerName(String name) {
        if (name == null) {
            return;
        }
        String cleaned = name.trim().replaceAll("[^a-zA-Z0-9_\\- ]", "");
        if (cleaned.length() > 16) {
            cleaned = cleaned.substring(0, 16);
        }
        if (cleaned.isEmpty()) {
            return;
        }
        this.playerName = cleaned;
        prefs.edit().putString(KEY_PLAYER_NAME, cleaned).apply();
    }

    public String getClientSessionId() {
        return clientSessionId;
    }

    public String getWsServerUrl() {
        return wsServerUrl;
    }

    public String getHttpServerUrl() {
        return httpServerUrl;
    }

    public void setServerUrls(String wsUrl, String httpUrl) {
        if (wsUrl != null && !wsUrl.trim().isEmpty()) {
            this.wsServerUrl = wsUrl.trim();
        }
        if (httpUrl != null && !httpUrl.trim().isEmpty()) {
            this.httpServerUrl = httpUrl.trim();
        } else if (wsUrl != null && !wsUrl.trim().isEmpty()) {
            String trimmed = wsUrl.trim();
            if (trimmed.startsWith("wss://")) {
                this.httpServerUrl = "https://" + trimmed.substring("wss://".length());
            } else if (trimmed.startsWith("ws://")) {
                this.httpServerUrl = "http://" + trimmed.substring("ws://".length());
            }
        }
        prefs.edit()
                .putString(KEY_WS_SERVER_URL, this.wsServerUrl)
                .putString(KEY_HTTP_SERVER_URL, this.httpServerUrl)
                .apply();
    }

    public boolean isServerConfigured() {
        return wsServerUrl != null
                && !wsServerUrl.trim().isEmpty()
                && !wsServerUrl.contains("YOUR_SERVER_DOMAIN")
                && (wsServerUrl.startsWith("ws://") || wsServerUrl.startsWith("wss://"));
    }
}
