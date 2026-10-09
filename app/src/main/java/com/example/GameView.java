package com.example;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * GameView renders the entire 2D arcade experience at 60 FPS using Android Canvas:
 * - Animated parallax sky, clouds, mountains, and scrolling ground
 * - Main Menu, Single-Player Gameplay, Pause Modal, Game Over Screen
 * - Bird Shop with 6 original animated bird skins
 * - Local & Online Leaderboard Screen
 * - 2-Player Online Multiplayer Lobby, Room Waiting Screen, Synchronized Match HUD, and Match Results
 */
public class GameView extends View implements MultiplayerManager.MultiplayerListener {

    public enum ScreenState {
        MAIN_MENU,
        SINGLE_PLAYER_GAME,
        GAME_OVER,
        BIRD_SHOP,
        LEADERBOARD,
        MULTIPLAYER_LOBBY,
        MULTIPLAYER_GAME,
        MULTIPLAYER_RESULT
    }

    public interface HostUiCallbacks {
        void onRequestJoinRoomDialog();
        void onRequestServerAndProfileDialog();
        void onRequestRewardedAd();
        void onMenuStateChanged(boolean isMenuScreen);
        void onShowToast(String message);
    }

    private static class PipeObstacle {
        float x;
        float gapCenterY;
        float baseGapCenterY;
        float gapHeight;
        boolean passed;
        boolean oscillates;
        float oscPhase;
        float oscSpeed;
    }

    private static class GoldCoin {
        float x;
        float y;
        float radius;
        boolean collected;
        float spinPhase;
    }

    private static class Particle {
        float x;
        float y;
        float vx;
        float vy;
        float radius;
        int color;
        float alpha;
        float decay;
    }

    private static class Cloud {
        float x;
        float y;
        float scale;
        float speed;
    }

    private final GameManager gameManager;
    private final SoundManager soundManager;
    private final LeaderboardManager leaderboardManager;
    private final MultiplayerManager multiplayerManager;
    private final HostUiCallbacks hostCallbacks;

    private ScreenState screenState = ScreenState.MAIN_MENU;
    private boolean isPaused = false;
    private int leaderboardTab = 0; // 0 = Local, 1 = Online Scores, 2 = 2P Matches

    // Paint objects
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tempRect = new RectF();
    private final Path tempPath = new Path();

    // Screen dimensions & scaling
    private int viewWidth = 1080;
    private int viewHeight = 1920;
    private float dp = 2.75f;
    private float groundTopY = 1680f;
    private LinearGradient skyGradient;
    private LinearGradient groundGradient;

    // Timing & animation
    private long lastFrameUptimeMs = 0L;
    private float animTimeSec = 0f;
    private String toastBannerText = "";
    private float toastBannerTimer = 0f;

    // Background scenery
    private final List<Cloud> clouds = new ArrayList<>();
    private float groundScrollX = 0f;

    // Gameplay physics & entities
    private Random obstacleRandom = new Random();
    private float birdX;
    private float birdY;
    private float birdVelocityY;
    private float birdRadius;
    private float birdRotationDeg;
    private boolean localPlayerAlive = true;
    private boolean waitingReadyTap = true;
    private int currentScore = 0;
    private int coinsCollectedInRun = 0;
    private int lastEarnedTotalCoins = 0;
    private boolean lastRunWasNewBest = false;
    private long runStartTimeMs = 0L;
    private long lastMultiplayerSyncMs = 0L;

    private final List<PipeObstacle> pipes = new ArrayList<>();
    private final List<GoldCoin> coins = new ArrayList<>();
    private final List<Particle> particles = new ArrayList<>();
    private float pipeSpawnTimer = 0f;

    // Interactive touch hitboxes
    private final RectF btnSoundToggle = new RectF();
    private final RectF btnMusicToggle = new RectF();
    private final RectF btnServerConfig = new RectF();

    private final RectF btnPlaySingle = new RectF();
    private final RectF btnPlayMulti = new RectF();
    private final RectF btnBirdShop = new RectF();
    private final RectF btnLeaderboard = new RectF();
    private final RectF btnDailyReward = new RectF();
    private final RectF btnWatchAd = new RectF();

    private boolean showDailyRewardModal = false;
    private final RectF btnDailyModalClaim = new RectF();
    private final RectF btnDailyModalClose = new RectF();

    private final RectF btnPauseGame = new RectF();
    private final RectF btnResumeGame = new RectF();
    private final RectF btnRestartGame = new RectF();
    private final RectF btnBackToMenu = new RectF();

    private final RectF[] shopCardRects = new RectF[6];
    private final RectF btnShopWatchAd = new RectF();
    private final RectF btnHeaderBack = new RectF();

    private final RectF btnTabLocal = new RectF();
    private final RectF btnTabOnline = new RectF();
    private final RectF btnTabMatches = new RectF();
    private final RectF btnRefreshOnline = new RectF();

    private final RectF btnMpCreateRoom = new RectF();
    private final RectF btnMpJoinRoom = new RectF();
    private final RectF btnMpEditServer = new RectF();
    private final RectF btnMpReadyToggle = new RectF();
    private final RectF btnMpLeaveRoom = new RectF();
    private final RectF btnMpPlayAgain = new RectF();

    public GameView(Context context,
                    GameManager gameManager,
                    SoundManager soundManager,
                    LeaderboardManager leaderboardManager,
                    HostUiCallbacks hostCallbacks) {
        super(context);
        this.gameManager = gameManager;
        this.soundManager = soundManager;
        this.leaderboardManager = leaderboardManager;
        this.hostCallbacks = hostCallbacks;
        this.multiplayerManager = new MultiplayerManager(gameManager, this);

        this.dp = context.getResources().getDisplayMetrics().density;
        for (int i = 0; i < shopCardRects.length; i++) {
            shopCardRects[i] = new RectF();
        }
        strokePaint.setStyle(Paint.Style.STROKE);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));

        initClouds();
        this.showDailyRewardModal = gameManager.canClaimDailyReward();
        setFocusable(true);
        setClickable(true);
    }

    public boolean isDailyRewardModalVisible() {
        return showDailyRewardModal;
    }

    public MultiplayerManager getMultiplayerManager() {
        return multiplayerManager;
    }

    public ScreenState getScreenState() {
        return screenState;
    }

    public boolean handleBackPress() {
        if (showDailyRewardModal) {
            showDailyRewardModal = false;
            invalidate();
            return true;
        }
        if (screenState == ScreenState.SINGLE_PLAYER_GAME) {
            if (!isPaused) {
                isPaused = true;
                invalidate();
                return true;
            } else {
                isPaused = false;
                switchScreen(ScreenState.MAIN_MENU);
                return true;
            }
        } else if (screenState == ScreenState.MULTIPLAYER_GAME || screenState == ScreenState.MULTIPLAYER_RESULT) {
            multiplayerManager.leaveRoom();
            switchScreen(ScreenState.MULTIPLAYER_LOBBY);
            return true;
        } else if (screenState != ScreenState.MAIN_MENU) {
            if (screenState == ScreenState.MULTIPLAYER_LOBBY
                    && !multiplayerManager.getCurrentRoomCode().isEmpty()) {
                multiplayerManager.leaveRoom();
                invalidate();
                return true;
            }
            switchScreen(ScreenState.MAIN_MENU);
            return true;
        }
        return false;
    }

    public void showBannerNotice(String message) {
        this.toastBannerText = message != null ? message : "";
        this.toastBannerTimer = 3.5f;
        invalidate();
    }

    private void switchScreen(ScreenState next) {
        this.screenState = next;
        this.isPaused = false;
        boolean isMenu = (next != ScreenState.SINGLE_PLAYER_GAME && next != ScreenState.MULTIPLAYER_GAME);
        if (hostCallbacks != null) {
            hostCallbacks.onMenuStateChanged(isMenu);
        }
        if (next == ScreenState.LEADERBOARD && leaderboardTab > 0) {
            refreshOnlineLeaderboard();
        }
        invalidate();
    }

    private void initClouds() {
        clouds.clear();
        Random rng = new Random(42L);
        for (int i = 0; i < 6; i++) {
            Cloud c = new Cloud();
            c.x = rng.nextFloat() * 1080f;
            c.y = 120f + rng.nextFloat() * 520f;
            c.scale = 0.7f + rng.nextFloat() * 0.7f;
            c.speed = 18f + rng.nextFloat() * 25f;
            clouds.add(c);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        this.viewWidth = Math.max(320, w);
        this.viewHeight = Math.max(480, h);
        this.groundTopY = viewHeight * 0.86f;
        this.birdRadius = Math.max(16f * dp, viewWidth * 0.042f);

        skyGradient = new LinearGradient(
                0, 0, 0, groundTopY,
                new int[]{
                        Color.parseColor("#07162C"),
                        Color.parseColor("#0F3460"),
                        Color.parseColor("#1E6B9B"),
                        Color.parseColor("#64C4ED")
                },
                new float[]{0f, 0.35f, 0.72f, 1f},
                Shader.TileMode.CLAMP
        );
        groundGradient = new LinearGradient(
                0, groundTopY, 0, viewHeight,
                Color.parseColor("#2E7D32"),
                Color.parseColor("#1B4332"),
                Shader.TileMode.CLAMP
        );
    }

    private void startNewRun(boolean multiplayer, long seed) {
        this.obstacleRandom = new Random(seed);
        this.birdX = viewWidth * 0.26f;
        this.birdY = groundTopY * 0.46f;
        this.birdVelocityY = 0f;
        this.birdRotationDeg = 0f;
        this.localPlayerAlive = true;
        this.waitingReadyTap = !multiplayer; // Multiplayer starts immediately after room ready
        this.currentScore = 0;
        this.coinsCollectedInRun = 0;
        this.lastEarnedTotalCoins = 0;
        this.lastRunWasNewBest = false;
        this.pipes.clear();
        this.coins.clear();
        this.particles.clear();
        this.pipeSpawnTimer = 0.4f;
        this.runStartTimeMs = SystemClock.uptimeMillis();
        this.lastMultiplayerSyncMs = 0L;
        switchScreen(multiplayer ? ScreenState.MULTIPLAYER_GAME : ScreenState.SINGLE_PLAYER_GAME);
    }

    private void performBirdFlap() {
        if (!localPlayerAlive) {
            return;
        }
        if (waitingReadyTap) {
            waitingReadyTap = false;
            runStartTimeMs = SystemClock.uptimeMillis();
        }
        birdVelocityY = -410f * dp;
        soundManager.playFlap();

        GameManager.BirdSkin skin = gameManager.getSelectedBirdSkin();
        for (int i = 0; i < 6; i++) {
            Particle p = new Particle();
            p.x = birdX - birdRadius * 0.6f;
            p.y = birdY + (obstacleRandom.nextFloat() - 0.5f) * birdRadius;
            p.vx = -(60f + obstacleRandom.nextFloat() * 90f) * dp;
            p.vy = (obstacleRandom.nextFloat() - 0.5f) * 80f * dp;
            p.radius = (3f + obstacleRandom.nextFloat() * 4f) * dp;
            p.color = (i % 2 == 0) ? skin.secondaryColor : skin.wingColor;
            p.alpha = 1f;
            p.decay = 2.2f;
            particles.add(p);
        }
    }

    private void spawnCoinBurst(float cx, float cy) {
        for (int i = 0; i < 12; i++) {
            double angle = (Math.PI * 2.0 * i) / 12.0;
            Particle p = new Particle();
            p.x = cx;
            p.y = cy;
            p.vx = (float) Math.cos(angle) * 110f * dp;
            p.vy = (float) Math.sin(angle) * 110f * dp;
            p.radius = 4.5f * dp;
            p.color = (i % 2 == 0) ? Color.parseColor("#FFD700") : Color.parseColor("#00E5FF");
            p.alpha = 1f;
            p.decay = 2.0f;
            particles.add(p);
        }
    }

    private void updateFrame(float dt) {
        animTimeSec += dt;
        if (toastBannerTimer > 0f) {
            toastBannerTimer = Math.max(0f, toastBannerTimer - dt);
        }

        // Scroll clouds and ground in all non-paused states
        if (!isPaused) {
            for (Cloud c : clouds) {
                c.x -= c.speed * dp * dt * 0.45f;
                if (c.x < -160f * dp) {
                    c.x = viewWidth + 120f * dp;
                }
            }
            groundScrollX = (groundScrollX - 165f * dp * dt) % (48f * dp);
        }

        // Update particles
        Iterator<Particle> pIt = particles.iterator();
        while (pIt.hasNext()) {
            Particle p = pIt.next();
            p.x += p.vx * dt;
            p.y += p.vy * dt;
            p.alpha -= p.decay * dt;
            if (p.alpha <= 0f) {
                pIt.remove();
            }
        }

        if (screenState != ScreenState.SINGLE_PLAYER_GAME && screenState != ScreenState.MULTIPLAYER_GAME) {
            return;
        }
        if (isPaused || waitingReadyTap) {
            return;
        }

        float speedMultiplier = 1.0f + Math.min(0.45f, currentScore * 0.012f);
        float horizontalSpeed = 185f * dp * speedMultiplier;

        if (localPlayerAlive) {
            float gravity = 1120f * dp;
            birdVelocityY += gravity * dt;
            birdVelocityY = Math.min(birdVelocityY, 640f * dp);
            birdY += birdVelocityY * dt;

            // Smooth bird pitch rotation
            float targetAngle = Math.max(-28f, Math.min(75f, (birdVelocityY / (480f * dp)) * 65f));
            birdRotationDeg += (targetAngle - birdRotationDeg) * Math.min(1f, dt * 12f);

            // Prevent flying above ceiling
            if (birdY - birdRadius < 24f * dp) {
                birdY = 24f * dp + birdRadius;
                birdVelocityY = 0f;
            }

            // Ground collision check
            if (birdY + birdRadius >= groundTopY) {
                birdY = groundTopY - birdRadius;
                handleLocalPlayerCrash();
                return;
            }
        }

        // Spawn pipes and collectible gold coins
        pipeSpawnTimer -= dt;
        if (pipeSpawnTimer <= 0f) {
            pipeSpawnTimer = Math.max(1.25f, 1.85f - currentScore * 0.015f);
            spawnPipeAndCoin();
        }

        float pipeWidth = 68f * dp;
        Iterator<PipeObstacle> pipeIt = pipes.iterator();
        while (pipeIt.hasNext()) {
            PipeObstacle pipe = pipeIt.next();
            pipe.x -= horizontalSpeed * dt;

            if (pipe.oscillates) {
                pipe.oscPhase += pipe.oscSpeed * dt;
                pipe.gapCenterY = pipe.baseGapCenterY + (float) Math.sin(pipe.oscPhase) * 42f * dp;
            }

            float topPipeBottom = pipe.gapCenterY - pipe.gapHeight * 0.5f;
            float bottomPipeTop = pipe.gapCenterY + pipe.gapHeight * 0.5f;

            // Score increment when passing pipe center
            if (localPlayerAlive && !pipe.passed && (pipe.x + pipeWidth * 0.5f) < birdX) {
                pipe.passed = true;
                currentScore++;
                if (screenState == ScreenState.MULTIPLAYER_GAME) {
                    syncMultiplayerProgress(true);
                }
            }

            // Collision check with top or bottom pipe
            if (localPlayerAlive) {
                float hitRadius = birdRadius * 0.82f;
                if (circleIntersectsRect(birdX, birdY, hitRadius, pipe.x, 0f, pipe.x + pipeWidth, topPipeBottom)
                        || circleIntersectsRect(birdX, birdY, hitRadius, pipe.x, bottomPipeTop, pipe.x + pipeWidth, groundTopY)) {
                    handleLocalPlayerCrash();
                    return;
                }
            }

            if (pipe.x + pipeWidth < -60f * dp) {
                pipeIt.remove();
            }
        }

        // Update & collect gold coins
        Iterator<GoldCoin> coinIt = coins.iterator();
        while (coinIt.hasNext()) {
            GoldCoin coin = coinIt.next();
            coin.x -= horizontalSpeed * dt;
            coin.spinPhase += dt * 6.0f;

            if (localPlayerAlive && !coin.collected) {
                float dx = birdX - coin.x;
                float dy = birdY - coin.y;
                float distSq = dx * dx + dy * dy;
                float sumR = birdRadius + coin.radius;
                if (distSq <= sumR * sumR) {
                    coin.collected = true;
                    coinsCollectedInRun++;
                    soundManager.playCoin();
                    spawnCoinBurst(coin.x, coin.y);
                    coinIt.remove();
                    continue;
                }
            }

            if (coin.x + coin.radius < -40f * dp) {
                coinIt.remove();
            }
        }

        if (screenState == ScreenState.MULTIPLAYER_GAME && localPlayerAlive) {
            long now = SystemClock.uptimeMillis();
            if (now - lastMultiplayerSyncMs >= 120L) {
                syncMultiplayerProgress(false);
            }
        }
    }

    private void syncMultiplayerProgress(boolean force) {
        long now = SystemClock.uptimeMillis();
        if (!force && now - lastMultiplayerSyncMs < 110L) {
            return;
        }
        lastMultiplayerSyncMs = now;
        long elapsedMs = Math.max(0L, now - runStartTimeMs);
        float normY = birdY / Math.max(1f, groundTopY);
        multiplayerManager.sendScoreAndPositionUpdate(currentScore, coinsCollectedInRun, normY, localPlayerAlive, elapsedMs);
    }

    private void handleLocalPlayerCrash() {
        if (!localPlayerAlive) {
            return;
        }
        localPlayerAlive = false;
        soundManager.playHit();
        long elapsedMs = Math.max(100L, SystemClock.uptimeMillis() - runStartTimeMs);

        int pipeBonus = currentScore / 5;
        lastEarnedTotalCoins = coinsCollectedInRun + pipeBonus;
        lastRunWasNewBest = gameManager.recordCompletedRun(currentScore, coinsCollectedInRun);
        leaderboardManager.recordScore(currentScore, coinsCollectedInRun, elapsedMs);

        if (screenState == ScreenState.SINGLE_PLAYER_GAME) {
            switchScreen(ScreenState.GAME_OVER);
        } else if (screenState == ScreenState.MULTIPLAYER_GAME) {
            multiplayerManager.sendPlayerEliminated(currentScore, coinsCollectedInRun, elapsedMs);
            showBannerNotice("You crashed at score " + currentScore + "! Waiting for final match result...");
        }
    }

    private void spawnPipeAndCoin() {
        PipeObstacle pipe = new PipeObstacle();
        pipe.x = viewWidth + 40f * dp;
        pipe.gapHeight = Math.max(132f * dp, (175f - currentScore * 0.7f) * dp);

        float minCenterY = 160f * dp + pipe.gapHeight * 0.5f;
        float maxCenterY = groundTopY - 120f * dp - pipe.gapHeight * 0.5f;
        if (maxCenterY <= minCenterY) {
            maxCenterY = minCenterY + 40f * dp;
        }
        pipe.baseGapCenterY = minCenterY + obstacleRandom.nextFloat() * (maxCenterY - minCenterY);
        pipe.gapCenterY = pipe.baseGapCenterY;
        pipe.passed = false;
        pipe.oscillates = currentScore >= 5 && (obstacleRandom.nextInt(3) == 0);
        pipe.oscPhase = obstacleRandom.nextFloat() * 6.28f;
        pipe.oscSpeed = 1.8f + obstacleRandom.nextFloat() * 1.1f;
        pipes.add(pipe);

        // Spawn a collectible gold coin inside the gap or between pipes
        if (obstacleRandom.nextFloat() < 0.75f) {
            GoldCoin coin = new GoldCoin();
            coin.x = pipe.x + 34f * dp;
            coin.y = pipe.baseGapCenterY + (obstacleRandom.nextFloat() - 0.5f) * (pipe.gapHeight * 0.42f);
            coin.radius = 14f * dp;
            coin.collected = false;
            coin.spinPhase = obstacleRandom.nextFloat() * 6.28f;
            coins.add(coin);
        }
    }

    private boolean circleIntersectsRect(float cx, float cy, float r, float left, float top, float right, float bottom) {
        float closestX = Math.max(left, Math.min(cx, right));
        float closestY = Math.max(top, Math.min(cy, bottom));
        float dx = cx - closestX;
        float dy = cy - closestY;
        return (dx * dx + dy * dy) < (r * r);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        long nowMs = SystemClock.uptimeMillis();
        float dt = (lastFrameUptimeMs == 0L) ? 0.016f : Math.min(0.05f, (nowMs - lastFrameUptimeMs) / 1000f);
        lastFrameUptimeMs = nowMs;

        updateFrame(dt);

        drawAnimatedBackground(canvas);

        switch (screenState) {
            case MAIN_MENU:
                drawMainMenu(canvas);
                break;
            case SINGLE_PLAYER_GAME:
            case MULTIPLAYER_GAME:
                drawGameplayWorld(canvas);
                break;
            case GAME_OVER:
                drawGameplayWorld(canvas);
                drawGameOverOverlay(canvas);
                break;
            case BIRD_SHOP:
                drawBirdShopScreen(canvas);
                break;
            case LEADERBOARD:
                drawLeaderboardScreen(canvas);
                break;
            case MULTIPLAYER_LOBBY:
                drawMultiplayerLobbyScreen(canvas);
                break;
            case MULTIPLAYER_RESULT:
                drawGameplayWorld(canvas);
                drawMultiplayerResultOverlay(canvas);
                break;
        }

        if (toastBannerTimer > 0f && !toastBannerText.isEmpty()) {
            drawToastBanner(canvas);
        }

        // Schedule next 60 FPS frame
        postInvalidateOnAnimation();
    }

    private void drawAnimatedBackground(Canvas canvas) {
        // 1. Sky gradient
        paint.setShader(skyGradient);
        canvas.drawRect(0, 0, viewWidth, groundTopY, paint);
        paint.setShader(null);

        // 2. Glowing sun / moon orb in upper sky
        paint.setColor(Color.parseColor("#22FFD54F"));
        canvas.drawCircle(viewWidth * 0.82f, viewHeight * 0.16f, 68f * dp, paint);
        paint.setColor(Color.parseColor("#55FFE082"));
        canvas.drawCircle(viewWidth * 0.82f, viewHeight * 0.16f, 44f * dp, paint);

        // 3. Drifting clouds
        paint.setColor(Color.parseColor("#38FFFFFF"));
        for (Cloud c : clouds) {
            float r = 28f * dp * c.scale;
            canvas.drawCircle(c.x, c.y, r, paint);
            canvas.drawCircle(c.x + r * 0.8f, c.y + r * 0.1f, r * 0.85f, paint);
            canvas.drawCircle(c.x - r * 0.75f, c.y + r * 0.15f, r * 0.75f, paint);
        }

        // 4. Distant mountain silhouettes
        paint.setColor(Color.parseColor("#1B3B5F"));
        tempPath.reset();
        tempPath.moveTo(0, groundTopY);
        tempPath.lineTo(0, groundTopY - 140f * dp);
        tempPath.lineTo(viewWidth * 0.22f, groundTopY - 240f * dp);
        tempPath.lineTo(viewWidth * 0.45f, groundTopY - 120f * dp);
        tempPath.lineTo(viewWidth * 0.74f, groundTopY - 265f * dp);
        tempPath.lineTo(viewWidth, groundTopY - 150f * dp);
        tempPath.lineTo(viewWidth, groundTopY);
        tempPath.close();
        canvas.drawPath(tempPath, paint);

        // 5. Closer hills
        paint.setColor(Color.parseColor("#164E63"));
        tempPath.reset();
        tempPath.moveTo(0, groundTopY);
        tempPath.lineTo(0, groundTopY - 75f * dp);
        tempPath.lineTo(viewWidth * 0.35f, groundTopY - 145f * dp);
        tempPath.lineTo(viewWidth * 0.65f, groundTopY - 68f * dp);
        tempPath.lineTo(viewWidth, groundTopY - 130f * dp);
        tempPath.lineTo(viewWidth, groundTopY);
        tempPath.close();
        canvas.drawPath(tempPath, paint);

        // 6. Ground terrain & scrolling grass chevrons
        paint.setShader(groundGradient);
        canvas.drawRect(0, groundTopY, viewWidth, viewHeight, paint);
        paint.setShader(null);

        paint.setColor(Color.parseColor("#00E676"));
        canvas.drawRect(0, groundTopY, viewWidth, groundTopY + 10f * dp, paint);

        paint.setColor(Color.parseColor("#43A047"));
        float stripeStep = 48f * dp;
        for (float x = groundScrollX - stripeStep; x < viewWidth + stripeStep; x += stripeStep) {
            canvas.drawRoundRect(x, groundTopY + 14f * dp, x + 24f * dp, groundTopY + 26f * dp, 6f * dp, 6f * dp, paint);
        }
    }

    private void drawMainMenu(Canvas canvas) {
        float topPad = 42f * dp;
        float sidePad = 20f * dp;

        // Top Bar: Sound, Music, Server Settings
        float pillH = 38f * dp;
        float pillW = (viewWidth - sidePad * 2 - 16f * dp) / 3f;

        btnSoundToggle.set(sidePad, topPad, sidePad + pillW, topPad + pillH);
        btnMusicToggle.set(btnSoundToggle.right + 8f * dp, topPad, btnSoundToggle.right + 8f * dp + pillW, topPad + pillH);
        btnServerConfig.set(btnMusicToggle.right + 8f * dp, topPad, viewWidth - sidePad, topPad + pillH);

        drawPillButton(canvas, btnSoundToggle,
                soundManager.isSoundEnabled() ? "🔊 SFX: ON" : "🔇 SFX: OFF",
                soundManager.isSoundEnabled() ? Color.parseColor("#00897B") : Color.parseColor("#37474F"),
                Color.parseColor("#00E5FF"), 12f);

        drawPillButton(canvas, btnMusicToggle,
                soundManager.isMusicEnabled() ? "🎵 BGM: ON" : "🎵 BGM: OFF",
                soundManager.isMusicEnabled() ? Color.parseColor("#00897B") : Color.parseColor("#37474F"),
                Color.parseColor("#00E5FF"), 12f);

        drawPillButton(canvas, btnServerConfig,
                "⚙ SERVER / ID",
                Color.parseColor("#1E3A5F"),
                Color.parseColor("#FFD700"), 12f);

        // Currency & Best Score Status Row
        float statsY = btnSoundToggle.bottom + 14f * dp;
        float halfW = (viewWidth - sidePad * 2 - 12f * dp) * 0.5f;

        tempRect.set(sidePad, statsY, sidePad + halfW, statsY + 42f * dp);
        drawGlassCard(canvas, tempRect, Color.parseColor("#990B1D3A"), Color.parseColor("#FFD700"));
        textPaint.setColor(Color.parseColor("#FFD700"));
        textPaint.setTextSize(15f * dp);
        textPaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("🪙 COINS: " + gameManager.getCoins(), tempRect.centerX(), tempRect.centerY() + 5f * dp, textPaint);

        tempRect.set(sidePad + halfW + 12f * dp, statsY, viewWidth - sidePad, statsY + 42f * dp);
        drawGlassCard(canvas, tempRect, Color.parseColor("#990B1D3A"), Color.parseColor("#00E5FF"));
        textPaint.setColor(Color.parseColor("#00E5FF"));
        canvas.drawText("🏆 BEST: " + gameManager.getBestScore(), tempRect.centerX(), tempRect.centerY() + 5f * dp, textPaint);

        // Fly Bird Logo Banner
        float logoCenterY = statsY + 100f * dp;
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(46f * dp);
        textPaint.setColor(Color.parseColor("#081C38"));
        canvas.drawText("FLY BIRD", viewWidth * 0.5f + 3f * dp, logoCenterY + 4f * dp, textPaint);
        textPaint.setColor(Color.parseColor("#00E5FF"));
        canvas.drawText("FLY BIRD", viewWidth * 0.5f, logoCenterY, textPaint);

        textPaint.setTextSize(13f * dp);
        textPaint.setColor(Color.parseColor("#FFD700"));
        canvas.drawText("★ ONLINE MULTIPLAYER EDITION ★", viewWidth * 0.5f, logoCenterY + 24f * dp, textPaint);

        // Animated Hero Bird Preview with Selected Skin
        float previewY = logoCenterY + 76f * dp + (float) Math.sin(animTimeSec * 4.2f) * 8f * dp;
        GameManager.BirdSkin equipped = gameManager.getSelectedBirdSkin();
        drawStylizedBird(canvas, viewWidth * 0.5f, previewY, 26f * dp,
                (float) Math.sin(animTimeSec * 3.5f) * 8f, equipped, true);

        textPaint.setTextSize(12.5f * dp);
        textPaint.setColor(Color.WHITE);
        canvas.drawText("Equipped: " + equipped.name + "  •  Pilot: " + gameManager.getPlayerName(),
                viewWidth * 0.5f, previewY + 42f * dp, textPaint);

        // Main Menu Buttons
        float btnW = Math.min(viewWidth - sidePad * 2, 380f * dp);
        float btnLeft = (viewWidth - btnW) * 0.5f;
        float btnH = 48f * dp;
        float gap = 10f * dp;
        float startY = previewY + 56f * dp;

        btnPlaySingle.set(btnLeft, startY, btnLeft + btnW, startY + btnH);
        drawPillButton(canvas, btnPlaySingle, "▶  PLAY SINGLE-PLAYER",
                Color.parseColor("#00C853"), Color.parseColor("#B9F6CA"), 16.5f);

        startY += btnH + gap;
        btnPlayMulti.set(btnLeft, startY, btnLeft + btnW, startY + btnH);
        drawPillButton(canvas, btnPlayMulti, "⚡  ONLINE MULTIPLAYER (2P)",
                Color.parseColor("#0288D1"), Color.parseColor("#00E5FF"), 15.5f);

        startY += btnH + gap;
        float subW = (btnW - 12f * dp) * 0.5f;
        btnBirdShop.set(btnLeft, startY, btnLeft + subW, startY + btnH);
        drawPillButton(canvas, btnBirdShop, "🛒 BIRD SHOP",
                Color.parseColor("#FF8F00"), Color.parseColor("#FFE082"), 14.5f);

        btnLeaderboard.set(btnLeft + subW + 12f * dp, startY, btnLeft + btnW, startY + btnH);
        drawPillButton(canvas, btnLeaderboard, "🏅 RANKINGS",
                Color.parseColor("#6A1B9A"), Color.parseColor("#EA80FC"), 14.5f);

        startY += btnH + gap;
        boolean dailyAvailable = gameManager.canClaimDailyReward();
        btnDailyReward.set(btnLeft, startY, btnLeft + btnW, startY + 42f * dp);
        drawPillButton(canvas, btnDailyReward,
                gameManager.getNextDailyRewardStatusLabel(),
                dailyAvailable ? Color.parseColor("#F57F17") : Color.parseColor("#263238"),
                dailyAvailable ? Color.parseColor("#FFD700") : Color.parseColor("#80CBC4"),
                13f);

        startY += 42f * dp + gap;
        btnWatchAd.set(btnLeft, startY, btnLeft + btnW, startY + 40f * dp);
        drawPillButton(canvas, btnWatchAd, "🎬  WATCH AD (+50 BONUS COINS)",
                Color.parseColor("#00695C"), Color.parseColor("#FFD700"), 12.5f);

        if (showDailyRewardModal) {
            drawDailyRewardModal(canvas);
        }
    }

    private void drawDailyRewardModal(Canvas canvas) {
        paint.setColor(Color.parseColor("#CC040D1A"));
        canvas.drawRect(0, 0, viewWidth, viewHeight, paint);

        float cardW = Math.min(viewWidth - 36f * dp, 360f * dp);
        float cardH = 330f * dp;
        float left = (viewWidth - cardW) * 0.5f;
        float top = (viewHeight - cardH) * 0.46f;

        tempRect.set(left, top, left + cardW, top + cardH);
        drawGlassCard(canvas, tempRect, Color.parseColor("#F50B1D3A"), Color.parseColor("#FFD700"));

        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(22f * dp);
        textPaint.setColor(Color.parseColor("#FFD700"));
        canvas.drawText("📅 DAILY LOGIN REWARD", tempRect.centerX(), top + 40f * dp, textPaint);

        GameManager.DailyRewardStatus status = gameManager.getDailyRewardStatus();
        int streak = gameManager.getDailyStreak();
        textPaint.setTextSize(13.5f * dp);
        textPaint.setColor(Color.parseColor("#00E5FF"));
        canvas.drawText("🔥 LOGIN STREAK: DAY " + streak, tempRect.centerX(), top + 68f * dp, textPaint);

        // Glowing Coin Emblem
        float coinCx = tempRect.centerX();
        float coinCy = top + 122f * dp;
        paint.setColor(Color.parseColor("#44FFD700"));
        canvas.drawCircle(coinCx, coinCy, 36f * dp, paint);
        paint.setColor(Color.parseColor("#FFD700"));
        canvas.drawCircle(coinCx, coinCy, 26f * dp, paint);
        strokePaint.setColor(Color.parseColor("#FFF59D"));
        strokePaint.setStrokeWidth(2.5f * dp);
        canvas.drawCircle(coinCx, coinCy, 26f * dp, strokePaint);

        textPaint.setTextSize(16f * dp);
        textPaint.setColor(Color.parseColor("#0B1D3A"));
        canvas.drawText("+" + GameManager.DAILY_REWARD_COINS, coinCx, coinCy + 6f * dp, textPaint);

        textPaint.setTextSize(15f * dp);
        textPaint.setColor(Color.WHITE);
        if (status == GameManager.DailyRewardStatus.AVAILABLE) {
            canvas.drawText("Welcome back, Pilot! Claim your", tempRect.centerX(), top + 178f * dp, textPaint);
            canvas.drawText("+" + GameManager.DAILY_REWARD_COINS + " Gold Coins daily bonus!", tempRect.centerX(), top + 198f * dp, textPaint);
        } else if (status == GameManager.DailyRewardStatus.CLOCK_TAMPER_DETECTED) {
            textPaint.setColor(Color.parseColor("#FF5252"));
            canvas.drawText("System clock change detected.", tempRect.centerX(), top + 178f * dp, textPaint);
            canvas.drawText("Restore automatic time to claim.", tempRect.centerX(), top + 198f * dp, textPaint);
        } else {
            canvas.drawText("Today's +" + GameManager.DAILY_REWARD_COINS + " Coins already claimed!", tempRect.centerX(), top + 178f * dp, textPaint);
            textPaint.setColor(Color.parseColor("#B9F6CA"));
            canvas.drawText(gameManager.getNextDailyRewardStatusLabel(), tempRect.centerX(), top + 198f * dp, textPaint);
        }

        float btnW = cardW - 40f * dp;
        float btnL = left + 20f * dp;
        btnDailyModalClaim.set(btnL, top + 216f * dp, btnL + btnW, top + 266f * dp);
        if (status == GameManager.DailyRewardStatus.AVAILABLE) {
            drawPillButton(canvas, btnDailyModalClaim,
                    "🎁  CLAIM +" + GameManager.DAILY_REWARD_COINS + " COINS NOW",
                    Color.parseColor("#00C853"), Color.parseColor("#FFD700"), 15f);
        } else {
            drawPillButton(canvas, btnDailyModalClaim,
                    "✓  ALREADY CLAIMED TODAY",
                    Color.parseColor("#37474F"), Color.parseColor("#90A4AE"), 14f);
        }

        btnDailyModalClose.set(btnL, top + 276f * dp, btnL + btnW, top + 316f * dp);
        drawPillButton(canvas, btnDailyModalClose,
                status == GameManager.DailyRewardStatus.AVAILABLE ? "LATER" : "CLOSE",
                Color.parseColor("#1E3A5F"), Color.parseColor("#00E5FF"), 13.5f);
    }

    private void drawGameplayWorld(Canvas canvas) {
        float pipeWidth = 68f * dp;

        // 1. Draw Pipes
        for (PipeObstacle pipe : pipes) {
            float topPipeBottom = pipe.gapCenterY - pipe.gapHeight * 0.5f;
            float bottomPipeTop = pipe.gapCenterY + pipe.gapHeight * 0.5f;

            drawSinglePipeColumn(canvas, pipe.x, 0, pipeWidth, topPipeBottom, true);
            drawSinglePipeColumn(canvas, pipe.x, bottomPipeTop, pipeWidth, groundTopY, false);
        }

        // 2. Draw Spinning Gold Coins
        for (GoldCoin coin : coins) {
            if (coin.collected) continue;
            float scaleX = (float) Math.abs(Math.cos(coin.spinPhase));
            float rx = Math.max(4f * dp, coin.radius * scaleX);
            paint.setColor(Color.parseColor("#44FFD700"));
            canvas.drawCircle(coin.x, coin.y, coin.radius * 1.35f, paint);

            tempRect.set(coin.x - rx, coin.y - coin.radius, coin.x + rx, coin.y + coin.radius);
            paint.setColor(Color.parseColor("#FFD700"));
            canvas.drawOval(tempRect, paint);

            strokePaint.setColor(Color.parseColor("#FFF59D"));
            strokePaint.setStrokeWidth(2f * dp);
            canvas.drawOval(tempRect, strokePaint);
        }

        // 3. Draw Particles
        for (Particle p : particles) {
            paint.setColor(p.color);
            paint.setAlpha(Math.max(0, Math.min(255, (int) (p.alpha * 255))));
            canvas.drawCircle(p.x, p.y, p.radius, paint);
        }
        paint.setAlpha(255);

        // 4. In 2-Player Multiplayer, draw opponent ghost bird & name tag
        if (screenState == ScreenState.MULTIPLAYER_GAME || screenState == ScreenState.MULTIPLAYER_RESULT) {
            MultiplayerManager.PlayerSlot opp = multiplayerManager.getOpponentSlot();
            if (opp != null) {
                float oppY = Math.max(birdRadius, Math.min(groundTopY - birdRadius, opp.normalizedY * groundTopY));
                float oppX = birdX + 54f * dp;
                GameManager.BirdSkin oppSkin = gameManager.getBirdSkin(opp.birdSkinId);
                canvas.save();
                drawStylizedBird(canvas, oppX, oppY, birdRadius * 0.9f, 0f, oppSkin, opp.alive);
                textPaint.setTextSize(11f * dp);
                textPaint.setTextAlign(Paint.Align.CENTER);
                textPaint.setColor(opp.alive ? Color.parseColor("#00E5FF") : Color.parseColor("#FF5252"));
                canvas.drawText(opp.playerName + (opp.alive ? "" : " (OUT)"), oppX, oppY - birdRadius * 1.4f, textPaint);
                canvas.restore();
            }
        }

        // 5. Draw Local Player Bird
        drawStylizedBird(canvas, birdX, birdY, birdRadius, birdRotationDeg,
                gameManager.getSelectedBirdSkin(), localPlayerAlive);

        // 6. Draw HUD
        if (screenState == ScreenState.SINGLE_PLAYER_GAME) {
            drawSinglePlayerHud(canvas);
        } else if (screenState == ScreenState.MULTIPLAYER_GAME) {
            drawMultiplayerHud(canvas);
        }
    }

    private void drawSinglePipeColumn(Canvas canvas, float x, float top, float width, float bottom, boolean isTopPipe) {
        if (bottom <= top) return;
        paint.setColor(Color.parseColor("#2E7D32"));
        canvas.drawRect(x, top, x + width, bottom, paint);

        // Highlight strip
        paint.setColor(Color.parseColor("#66BB6A"));
        canvas.drawRect(x + 8f * dp, top, x + 20f * dp, bottom, paint);

        // Shadow strip
        paint.setColor(Color.parseColor("#1B5E20"));
        canvas.drawRect(x + width - 12f * dp, top, x + width, bottom, paint);

        // Pipe rim collar
        float lipH = 22f * dp;
        float lipLeft = x - 5f * dp;
        float lipRight = x + width + 5f * dp;
        if (isTopPipe) {
            tempRect.set(lipLeft, bottom - lipH, lipRight, bottom);
        } else {
            tempRect.set(lipLeft, top, lipRight, top + lipH);
        }
        paint.setColor(Color.parseColor("#388E3C"));
        canvas.drawRoundRect(tempRect, 5f * dp, 5f * dp, paint);
        strokePaint.setColor(Color.parseColor("#00E676"));
        strokePaint.setStrokeWidth(2f * dp);
        canvas.drawRoundRect(tempRect, 5f * dp, 5f * dp, strokePaint);
    }

    private void drawStylizedBird(Canvas canvas, float cx, float cy, float radius,
                                  float rotationDeg, GameManager.BirdSkin skin, boolean alive) {
        canvas.save();
        canvas.translate(cx, cy);
        canvas.rotate(rotationDeg);

        // Outer neon/elemental aura
        paint.setColor(skin.glowColor);
        canvas.drawCircle(0, 0, radius * 1.38f, paint);

        // Tail feathers
        paint.setColor(skin.secondaryColor);
        tempPath.reset();
        tempPath.moveTo(-radius * 0.8f, 0);
        tempPath.lineTo(-radius * 1.45f, -radius * 0.45f);
        tempPath.lineTo(-radius * 1.3f, 0);
        tempPath.lineTo(-radius * 1.45f, radius * 0.45f);
        tempPath.close();
        canvas.drawPath(tempPath, paint);

        // Main body
        paint.setColor(skin.primaryColor);
        canvas.drawCircle(0, 0, radius, paint);

        // Belly highlight
        paint.setColor(skin.secondaryColor);
        tempRect.set(-radius * 0.65f, -radius * 0.2f, radius * 0.75f, radius * 0.85f);
        canvas.drawArc(tempRect, 10f, 160f, true, paint);

        // Animated flapping wing
        float wingOffsetY = alive ? (float) Math.sin(animTimeSec * 18f) * (radius * 0.25f) : 0f;
        paint.setColor(skin.wingColor);
        tempRect.set(-radius * 0.55f, -radius * 0.25f + wingOffsetY, radius * 0.25f, radius * 0.45f + wingOffsetY);
        canvas.drawRoundRect(tempRect, radius * 0.35f, radius * 0.35f, paint);

        // Beak
        paint.setColor(skin.beakColor);
        tempPath.reset();
        tempPath.moveTo(radius * 0.75f, -radius * 0.22f);
        tempPath.lineTo(radius * 1.42f, 0.05f * radius);
        tempPath.lineTo(radius * 0.75f, radius * 0.32f);
        tempPath.close();
        canvas.drawPath(tempPath, paint);

        // Eye
        paint.setColor(Color.WHITE);
        canvas.drawCircle(radius * 0.38f, -radius * 0.26f, radius * 0.30f, paint);
        paint.setColor(Color.parseColor("#0B1D3A"));
        canvas.drawCircle(radius * 0.46f, -radius * 0.26f, radius * 0.15f, paint);

        // Outline ring
        strokePaint.setColor(Color.WHITE);
        strokePaint.setStrokeWidth(2f * dp);
        canvas.drawCircle(0, 0, radius, strokePaint);

        canvas.restore();
    }

    private void drawSinglePlayerHud(Canvas canvas) {
        float topY = 42f * dp;

        // Live Score
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(48f * dp);
        textPaint.setColor(Color.parseColor("#0B1D3A"));
        canvas.drawText(String.valueOf(currentScore), viewWidth * 0.5f + 3f * dp, topY + 46f * dp, textPaint);
        textPaint.setColor(Color.WHITE);
        canvas.drawText(String.valueOf(currentScore), viewWidth * 0.5f, topY + 43f * dp, textPaint);

        // Coins badge on top-left
        tempRect.set(20f * dp, topY, 155f * dp, topY + 40f * dp);
        drawGlassCard(canvas, tempRect, Color.parseColor("#AA0B1D3A"), Color.parseColor("#FFD700"));
        textPaint.setTextSize(15f * dp);
        textPaint.setColor(Color.parseColor("#FFD700"));
        canvas.drawText("🪙 +" + coinsCollectedInRun, tempRect.centerX(), tempRect.centerY() + 5f * dp, textPaint);

        // Pause button on top-right
        btnPauseGame.set(viewWidth - 125f * dp, topY, viewWidth - 20f * dp, topY + 40f * dp);
        drawPillButton(canvas, btnPauseGame, isPaused ? "▶ RESUME" : "⏸ PAUSE",
                Color.parseColor("#1E3A5F"), Color.parseColor("#00E5FF"), 13f);

        if (waitingReadyTap && !isPaused) {
            tempRect.set(viewWidth * 0.14f, viewHeight * 0.58f, viewWidth * 0.86f, viewHeight * 0.67f);
            drawGlassCard(canvas, tempRect, Color.parseColor("#CC0B1D3A"), Color.parseColor("#00E5FF"));
            textPaint.setColor(Color.parseColor("#00E5FF"));
            textPaint.setTextSize(18f * dp);
            canvas.drawText("TAP ANYWHERE TO FLY!", tempRect.centerX(), tempRect.centerY() + 6f * dp, textPaint);
        }

        if (isPaused) {
            drawPauseModal(canvas);
        }
    }

    private void drawMultiplayerHud(Canvas canvas) {
        float topY = 38f * dp;
        tempRect.set(16f * dp, topY, viewWidth - 16f * dp, topY + 68f * dp);
        drawGlassCard(canvas, tempRect, Color.parseColor("#DD07162C"), Color.parseColor("#00E5FF"));

        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setTextSize(14f * dp);
        textPaint.setColor(Color.parseColor("#00E5FF"));
        String myStatus = localPlayerAlive ? "ALIVE" : "ELIMINATED";
        canvas.drawText("YOU (" + gameManager.getPlayerName() + "): " + currentScore + "  [" + myStatus + "]",
                tempRect.left + 14f * dp, tempRect.top + 26f * dp, textPaint);

        MultiplayerManager.PlayerSlot opp = multiplayerManager.getOpponentSlot();
        String oppLine;
        if (opp != null) {
            oppLine = "OPPONENT (" + opp.playerName + "): " + opp.score + "  [" + (opp.alive ? "ALIVE" : "ELIMINATED") + "]";
            textPaint.setColor(opp.alive ? Color.parseColor("#FFD700") : Color.parseColor("#FF5252"));
        } else {
            oppLine = "OPPONENT: Synchronizing...";
            textPaint.setColor(Color.LTGRAY);
        }
        canvas.drawText(oppLine, tempRect.left + 14f * dp, tempRect.top + 52f * dp, textPaint);

        if (!localPlayerAlive) {
            tempRect.set(24f * dp, viewHeight * 0.45f, viewWidth - 24f * dp, viewHeight * 0.55f);
            drawGlassCard(canvas, tempRect, Color.parseColor("#DD0B1D3A"), Color.parseColor("#FFD700"));
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTextSize(15f * dp);
            textPaint.setColor(Color.parseColor("#FFD700"));
            canvas.drawText("YOU CRASHED! SPECTATING OPPONENT...", tempRect.centerX(), tempRect.centerY() - 4f * dp, textPaint);
            textPaint.setTextSize(12.5f * dp);
            textPaint.setColor(Color.WHITE);
            canvas.drawText("Server will announce the official winner when the match ends.", tempRect.centerX(), tempRect.centerY() + 18f * dp, textPaint);
        }
    }

    private void drawPauseModal(Canvas canvas) {
        paint.setColor(Color.parseColor("#AA000000"));
        canvas.drawRect(0, 0, viewWidth, viewHeight, paint);

        float cardW = Math.min(viewWidth - 48f * dp, 340f * dp);
        float cardH = 260f * dp;
        float left = (viewWidth - cardW) * 0.5f;
        float top = (viewHeight - cardH) * 0.5f;

        tempRect.set(left, top, left + cardW, top + cardH);
        drawGlassCard(canvas, tempRect, Color.parseColor("#F00B1D3A"), Color.parseColor("#00E5FF"));

        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(24f * dp);
        textPaint.setColor(Color.parseColor("#00E5FF"));
        canvas.drawText("GAME PAUSED", tempRect.centerX(), top + 44f * dp, textPaint);

        float btnW = cardW - 40f * dp;
        float btnL = left + 20f * dp;
        btnResumeGame.set(btnL, top + 72f * dp, btnL + btnW, top + 120f * dp);
        drawPillButton(canvas, btnResumeGame, "▶  RESUME GAME",
                Color.parseColor("#00C853"), Color.parseColor("#B9F6CA"), 15f);

        btnRestartGame.set(btnL, top + 134f * dp, btnL + btnW, top + 182f * dp);
        drawPillButton(canvas, btnRestartGame, "↻  RESTART",
                Color.parseColor("#0288D1"), Color.parseColor("#00E5FF"), 15f);

        btnBackToMenu.set(btnL, top + 196f * dp, btnL + btnW, top + 244f * dp);
        drawPillButton(canvas, btnBackToMenu, "🏠  MAIN MENU",
                Color.parseColor("#455A64"), Color.parseColor("#CFD8DC"), 15f);
    }

    private void drawGameOverOverlay(Canvas canvas) {
        paint.setColor(Color.parseColor("#B8040D1A"));
        canvas.drawRect(0, 0, viewWidth, viewHeight, paint);

        float cardW = Math.min(viewWidth - 40f * dp, 360f * dp);
        float cardH = 350f * dp;
        float left = (viewWidth - cardW) * 0.5f;
        float top = (viewHeight - cardH) * 0.45f;

        tempRect.set(left, top, left + cardW, top + cardH);
        drawGlassCard(canvas, tempRect, Color.parseColor("#F20B1D3A"), Color.parseColor("#FFD700"));

        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(28f * dp);
        textPaint.setColor(Color.parseColor("#FF5252"));
        canvas.drawText("GAME OVER", tempRect.centerX(), top + 44f * dp, textPaint);

        String medal = currentScore >= 50 ? "💎 PLATINUM MEDAL"
                : currentScore >= 30 ? "🥇 GOLD MEDAL"
                : currentScore >= 15 ? "🥈 SILVER MEDAL"
                : currentScore >= 5 ? "🥉 BRONZE MEDAL" : "🐣 ROOKIE FLIGHT";
        textPaint.setTextSize(14f * dp);
        textPaint.setColor(Color.parseColor("#00E5FF"));
        canvas.drawText(medal, tempRect.centerX(), top + 72f * dp, textPaint);

        textPaint.setTextSize(22f * dp);
        textPaint.setColor(Color.WHITE);
        canvas.drawText("SCORE: " + currentScore, tempRect.centerX(), top + 114f * dp, textPaint);

        textPaint.setTextSize(16f * dp);
        textPaint.setColor(Color.parseColor("#FFD700"));
        String bestLabel = "BEST SCORE: " + gameManager.getBestScore() + (lastRunWasNewBest ? " (NEW RECORD!)" : "");
        canvas.drawText(bestLabel, tempRect.centerX(), top + 144f * dp, textPaint);

        textPaint.setTextSize(14f * dp);
        textPaint.setColor(Color.parseColor("#B9F6CA"));
        canvas.drawText("🪙 Coins Earned This Flight: +" + lastEarnedTotalCoins, tempRect.centerX(), top + 176f * dp, textPaint);

        float btnW = cardW - 40f * dp;
        float btnL = left + 20f * dp;
        btnRestartGame.set(btnL, top + 206f * dp, btnL + btnW, top + 258f * dp);
        drawPillButton(canvas, btnRestartGame, "↻  PLAY AGAIN",
                Color.parseColor("#00C853"), Color.parseColor("#B9F6CA"), 16f);

        btnBackToMenu.set(btnL, top + 272f * dp, btnL + btnW, top + 324f * dp);
        drawPillButton(canvas, btnBackToMenu, "🏠  MAIN MENU",
                Color.parseColor("#1E3A5F"), Color.parseColor("#00E5FF"), 15f);
    }

    private void drawBirdShopScreen(Canvas canvas) {
        paint.setColor(Color.parseColor("#D8061224"));
        canvas.drawRect(0, 0, viewWidth, viewHeight, paint);

        float topY = 40f * dp;
        float sidePad = 18f * dp;

        btnHeaderBack.set(sidePad, topY, sidePad + 92f * dp, topY + 38f * dp);
        drawPillButton(canvas, btnHeaderBack, "← BACK",
                Color.parseColor("#1E3A5F"), Color.parseColor("#00E5FF"), 13f);

        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(22f * dp);
        textPaint.setColor(Color.parseColor("#FFD700"));
        canvas.drawText("BIRD SHOP", viewWidth * 0.5f, topY + 26f * dp, textPaint);

        textPaint.setTextAlign(Paint.Align.RIGHT);
        textPaint.setTextSize(15f * dp);
        textPaint.setColor(Color.parseColor("#FFD700"));
        canvas.drawText("🪙 " + gameManager.getCoins(), viewWidth - sidePad, topY + 25f * dp, textPaint);

        List<GameManager.BirdSkin> skins = gameManager.getBirdCatalog();
        float gridTop = topY + 54f * dp;
        float gap = 12f * dp;
        float colW = (viewWidth - sidePad * 2 - gap) * 0.5f;
        float rowH = Math.min(148f * dp, (viewHeight - gridTop - 135f * dp) / 3f);

        for (int i = 0; i < skins.size() && i < 6; i++) {
            int col = i % 2;
            int row = i / 2;
            float left = sidePad + col * (colW + gap);
            float top = gridTop + row * (rowH + gap);
            RectF card = shopCardRects[i];
            card.set(left, top, left + colW, top + rowH);

            GameManager.BirdSkin skin = skins.get(i);
            boolean unlocked = gameManager.isBirdUnlocked(skin.id);
            boolean selected = (gameManager.getSelectedBirdId() == skin.id);

            int borderCol = selected ? Color.parseColor("#00E676")
                    : unlocked ? Color.parseColor("#00E5FF") : Color.parseColor("#FFD700");
            drawGlassCard(canvas, card, Color.parseColor("#E60F2544"), borderCol);

            drawStylizedBird(canvas, card.centerX(), card.top + rowH * 0.32f,
                    17f * dp, (float) Math.sin(animTimeSec * 3f + i) * 6f, skin, true);

            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTextSize(14f * dp);
            textPaint.setColor(Color.WHITE);
            canvas.drawText(skin.name, card.centerX(), card.top + rowH * 0.62f, textPaint);

            RectF actionPill = new RectF(card.left + 10f * dp, card.bottom - 34f * dp, card.right - 10f * dp, card.bottom - 7f * dp);
            if (selected) {
                drawPillButton(canvas, actionPill, "✓ EQUIPPED",
                        Color.parseColor("#00C853"), Color.parseColor("#B9F6CA"), 11.5f);
            } else if (unlocked) {
                drawPillButton(canvas, actionPill, "SELECT",
                        Color.parseColor("#0277BD"), Color.parseColor("#00E5FF"), 11.5f);
            } else {
                drawPillButton(canvas, actionPill, "🔓 BUY • " + skin.price + " 🪙",
                        Color.parseColor("#FF8F00"), Color.parseColor("#FFE082"), 11.5f);
            }
        }

        float bottomBtnY = gridTop + 3 * (rowH + gap) + 4f * dp;
        btnShopWatchAd.set(sidePad, bottomBtnY, viewWidth - sidePad, bottomBtnY + 42f * dp);
        drawPillButton(canvas, btnShopWatchAd, "🎁 WATCH REWARDED AD FOR +50 COINS",
                Color.parseColor("#00695C"), Color.parseColor("#FFD700"), 13f);
    }

    private void drawLeaderboardScreen(Canvas canvas) {
        paint.setColor(Color.parseColor("#E0061224"));
        canvas.drawRect(0, 0, viewWidth, viewHeight, paint);

        float topY = 40f * dp;
        float sidePad = 18f * dp;

        btnHeaderBack.set(sidePad, topY, sidePad + 92f * dp, topY + 38f * dp);
        drawPillButton(canvas, btnHeaderBack, "← BACK",
                Color.parseColor("#1E3A5F"), Color.parseColor("#00E5FF"), 13f);

        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(20f * dp);
        textPaint.setColor(Color.parseColor("#00E5FF"));
        canvas.drawText("LEADERBOARDS", viewWidth * 0.5f, topY + 25f * dp, textPaint);

        btnRefreshOnline.set(viewWidth - sidePad - 96f * dp, topY, viewWidth - sidePad, topY + 38f * dp);
        drawPillButton(canvas, btnRefreshOnline, "↻ SYNC",
                Color.parseColor("#00897B"), Color.parseColor("#B9F6CA"), 12f);

        // Tabs
        float tabY = topY + 48f * dp;
        float tabW = (viewWidth - sidePad * 2 - 16f * dp) / 3f;
        btnTabLocal.set(sidePad, tabY, sidePad + tabW, tabY + 36f * dp);
        btnTabOnline.set(btnTabLocal.right + 8f * dp, tabY, btnTabLocal.right + 8f * dp + tabW, tabY + 36f * dp);
        btnTabMatches.set(btnTabOnline.right + 8f * dp, tabY, viewWidth - sidePad, tabY + 36f * dp);

        drawPillButton(canvas, btnTabLocal, "LOCAL BEST",
                leaderboardTab == 0 ? Color.parseColor("#0288D1") : Color.parseColor("#1C3144"),
                Color.parseColor("#00E5FF"), 11.5f);
        drawPillButton(canvas, btnTabOnline, "ONLINE TOP",
                leaderboardTab == 1 ? Color.parseColor("#0288D1") : Color.parseColor("#1C3144"),
                Color.parseColor("#00E5FF"), 11.5f);
        drawPillButton(canvas, btnTabMatches, "2P MATCHES",
                leaderboardTab == 2 ? Color.parseColor("#0288D1") : Color.parseColor("#1C3144"),
                Color.parseColor("#00E5FF"), 11.5f);

        float listTop = tabY + 48f * dp;
        float listBottom = viewHeight - 75f * dp;
        tempRect.set(sidePad, listTop, viewWidth - sidePad, listBottom);
        drawGlassCard(canvas, tempRect, Color.parseColor("#CC0B1D3A"), Color.parseColor("#00E5FF"));

        if (leaderboardTab == 0) {
            List<LeaderboardManager.ScoreEntry> local = leaderboardManager.getLocalScores();
            drawScoreEntriesList(canvas, local, listTop + 28f * dp, sidePad + 16f * dp,
                    "No local flights recorded yet. Play a round to set your record!");
        } else if (leaderboardTab == 1) {
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTextSize(11.5f * dp);
            textPaint.setColor(Color.parseColor("#FFD700"));
            canvas.drawText(leaderboardManager.getOnlineStatusMessage(), viewWidth * 0.5f, listTop + 22f * dp, textPaint);

            List<LeaderboardManager.ScoreEntry> online = leaderboardManager.getCachedOnlineScores();
            drawScoreEntriesList(canvas, online, listTop + 48f * dp, sidePad + 16f * dp,
                    "Deploy the Node.js server & set its URL via ⚙ SERVER to load global scores.");
        } else {
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTextSize(11.5f * dp);
            textPaint.setColor(Color.parseColor("#FFD700"));
            canvas.drawText(leaderboardManager.getOnlineStatusMessage(), viewWidth * 0.5f, listTop + 22f * dp, textPaint);

            List<LeaderboardManager.OnlineMatchEntry> matches = leaderboardManager.getCachedOnlineMatches();
            if (matches.isEmpty()) {
                textPaint.setColor(Color.LTGRAY);
                textPaint.setTextSize(13f * dp);
                canvas.drawText("No online 2-player matches recorded on server yet.",
                        viewWidth * 0.5f, listTop + 95f * dp, textPaint);
            } else {
                float rowY = listTop + 50f * dp;
                textPaint.setTextAlign(Paint.Align.LEFT);
                textPaint.setTextSize(12.5f * dp);
                for (int i = 0; i < Math.min(8, matches.size()); i++) {
                    LeaderboardManager.OnlineMatchEntry m = matches.get(i);
                    textPaint.setColor(Color.parseColor("#00E676"));
                    canvas.drawText("🏆 " + m.winnerName + "  •  "
                                    + m.player1Name + " (" + m.player1Score + ") vs "
                                    + m.player2Name + " (" + m.player2Score + ")",
                            sidePad + 16f * dp, rowY, textPaint);
                    rowY += 32f * dp;
                }
            }
        }
    }

    private void drawScoreEntriesList(Canvas canvas, List<LeaderboardManager.ScoreEntry> entries,
                                      float startY, float leftX, String emptyMsg) {
        if (entries.isEmpty()) {
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTextSize(13f * dp);
            textPaint.setColor(Color.LTGRAY);
            canvas.drawText(emptyMsg, viewWidth * 0.5f, startY + 60f * dp, textPaint);
            return;
        }
        float y = startY;
        for (int i = 0; i < Math.min(10, entries.size()); i++) {
            LeaderboardManager.ScoreEntry e = entries.get(i);
            textPaint.setTextAlign(Paint.Align.LEFT);
            textPaint.setTextSize(13.5f * dp);
            textPaint.setColor(i == 0 ? Color.parseColor("#FFD700")
                    : i == 1 ? Color.parseColor("#E0E0E0")
                    : i == 2 ? Color.parseColor("#FFB74D") : Color.WHITE);
            canvas.drawText("#" + e.rank + "  " + e.playerName + "  (" + e.birdName + ")", leftX, y, textPaint);

            textPaint.setTextAlign(Paint.Align.RIGHT);
            textPaint.setColor(Color.parseColor("#00E5FF"));
            canvas.drawText(e.score + " pts  •  🪙" + e.coins, viewWidth - leftX, y, textPaint);
            y += 30f * dp;
        }
    }

    private void drawMultiplayerLobbyScreen(Canvas canvas) {
        paint.setColor(Color.parseColor("#E0061224"));
        canvas.drawRect(0, 0, viewWidth, viewHeight, paint);

        float topY = 40f * dp;
        float sidePad = 20f * dp;

        btnHeaderBack.set(sidePad, topY, sidePad + 92f * dp, topY + 38f * dp);
        drawPillButton(canvas, btnHeaderBack, "← MENU",
                Color.parseColor("#1E3A5F"), Color.parseColor("#00E5FF"), 13f);

        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(19f * dp);
        textPaint.setColor(Color.parseColor("#00E5FF"));
        canvas.drawText("2-PLAYER ONLINE ARENA", viewWidth * 0.5f, topY + 25f * dp, textPaint);

        // Server & Pilot Identity Card
        float infoTop = topY + 50f * dp;
        tempRect.set(sidePad, infoTop, viewWidth - sidePad, infoTop + 82f * dp);
        drawGlassCard(canvas, tempRect, Color.parseColor("#CC0B1D3A"), Color.parseColor("#FFD700"));

        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setTextSize(13f * dp);
        textPaint.setColor(Color.WHITE);
        canvas.drawText("Pilot Name: " + gameManager.getPlayerName(), sidePad + 14f * dp, infoTop + 26f * dp, textPaint);

        textPaint.setTextSize(11.5f * dp);
        textPaint.setColor(gameManager.isServerConfigured() ? Color.parseColor("#00E676") : Color.parseColor("#FFAB40"));
        canvas.drawText("Server: " + gameManager.getWsServerUrl(), sidePad + 14f * dp, infoTop + 48f * dp, textPaint);

        btnMpEditServer.set(viewWidth - sidePad - 128f * dp, infoTop + 14f * dp, viewWidth - sidePad - 12f * dp, infoTop + 52f * dp);
        drawPillButton(canvas, btnMpEditServer, "✎ CONFIGURE",
                Color.parseColor("#1E3A5F"), Color.parseColor("#FFD700"), 11.5f);

        textPaint.setTextSize(11.5f * dp);
        textPaint.setColor(Color.parseColor("#80D8FF"));
        canvas.drawText("Status: " + multiplayerManager.getStatusBanner(), sidePad + 14f * dp, infoTop + 70f * dp, textPaint);

        String activeRoom = multiplayerManager.getCurrentRoomCode();
        if (activeRoom == null || activeRoom.isEmpty()) {
            // Room Creation / Join Controls
            float actionTop = infoTop + 105f * dp;
            btnMpCreateRoom.set(sidePad, actionTop, viewWidth - sidePad, actionTop + 54f * dp);
            drawPillButton(canvas, btnMpCreateRoom, "➕  CREATE ROOM (GENERATE 6-DIGIT CODE)",
                    Color.parseColor("#00C853"), Color.parseColor("#B9F6CA"), 15f);

            btnMpJoinRoom.set(sidePad, actionTop + 70f * dp, viewWidth - sidePad, actionTop + 124f * dp);
            drawPillButton(canvas, btnMpJoinRoom, "🔑  JOIN ROOM (ENTER 6-DIGIT CODE)",
                    Color.parseColor("#0288D1"), Color.parseColor("#00E5FF"), 15f);

            // Architecture & Anti-Cheat Note Card
            tempRect.set(sidePad, actionTop + 148f * dp, viewWidth - sidePad, actionTop + 265f * dp);
            drawGlassCard(canvas, tempRect, Color.parseColor("#990B1D3A"), Color.parseColor("#00E5FF"));
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTextSize(13.5f * dp);
            textPaint.setColor(Color.parseColor("#FFD700"));
            canvas.drawText("REAL-TIME WEBSOCKET MULTIPLAYER", tempRect.centerX(), tempRect.top + 28f * dp, textPaint);

            textPaint.setTextSize(11.5f * dp);
            textPaint.setColor(Color.WHITE);
            canvas.drawText("• Each room supports up to 2 Android phones over WSS.", tempRect.centerX(), tempRect.top + 52f * dp, textPaint);
            canvas.drawText("• Both players fly through the exact same seeded pipe course.", tempRect.centerX(), tempRect.top + 74f * dp, textPaint);
            canvas.drawText("• Authoritative Node.js server validates scores & determines winner.", tempRect.centerX(), tempRect.top + 96f * dp, textPaint);
        } else {
            // Inside Room Waiting / Ready Lobby
            float roomTop = infoTop + 96f * dp;
            tempRect.set(sidePad, roomTop, viewWidth - sidePad, roomTop + 74f * dp);
            drawGlassCard(canvas, tempRect, Color.parseColor("#EE0F2544"), Color.parseColor("#00E5FF"));

            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTextSize(13f * dp);
            textPaint.setColor(Color.parseColor("#80D8FF"));
            canvas.drawText("SHARE THIS 6-DIGIT ROOM CODE WITH PLAYER 2", tempRect.centerX(), roomTop + 24f * dp, textPaint);

            textPaint.setTextSize(28f * dp);
            textPaint.setColor(Color.parseColor("#FFD700"));
            canvas.drawText(activeRoom, tempRect.centerX(), roomTop + 58f * dp, textPaint);

            // Player Slots (Max 2 Players)
            List<MultiplayerManager.PlayerSlot> slots = multiplayerManager.getRoomPlayers();
            float slotTop = roomTop + 88f * dp;
            for (int i = 0; i < 2; i++) {
                tempRect.set(sidePad, slotTop + i * 68f * dp, viewWidth - sidePad, slotTop + i * 68f * dp + 58f * dp);
                MultiplayerManager.PlayerSlot s = (i < slots.size()) ? slots.get(i) : null;
                int border = (s != null && s.ready) ? Color.parseColor("#00E676") : Color.parseColor("#00E5FF");
                drawGlassCard(canvas, tempRect, Color.parseColor("#CC0B1D3A"), border);

                textPaint.setTextAlign(Paint.Align.LEFT);
                textPaint.setTextSize(14.5f * dp);
                if (s != null) {
                    textPaint.setColor(Color.WHITE);
                    canvas.drawText("P" + (i + 1) + ": " + s.playerName + (s.connected ? " (ONLINE)" : " (RECONNECTING)"),
                            tempRect.left + 16f * dp, tempRect.centerY() + 5f * dp, textPaint);

                    textPaint.setTextAlign(Paint.Align.RIGHT);
                    textPaint.setColor(s.ready ? Color.parseColor("#00E676") : Color.parseColor("#FFD700"));
                    canvas.drawText(s.ready ? "✓ READY" : "WAITING...", tempRect.right - 16f * dp, tempRect.centerY() + 5f * dp, textPaint);
                } else {
                    textPaint.setColor(Color.LTGRAY);
                    canvas.drawText("P" + (i + 1) + ": Waiting for opponent to join...",
                            tempRect.left + 16f * dp, tempRect.centerY() + 5f * dp, textPaint);
                }
            }

            float btnY = slotTop + 148f * dp;
            MultiplayerManager.PlayerSlot mySlot = multiplayerManager.getMySlot();
            boolean amReady = mySlot != null && mySlot.ready;
            btnMpReadyToggle.set(sidePad, btnY, viewWidth - sidePad, btnY + 52f * dp);
            drawPillButton(canvas, btnMpReadyToggle,
                    amReady ? "✓ READY (WAITING FOR OPPONENT)" : "⚡ TAP TO MARK READY & START",
                    amReady ? Color.parseColor("#00897B") : Color.parseColor("#00C853"),
                    Color.parseColor("#B9F6CA"), 15f);

            btnMpLeaveRoom.set(sidePad, btnY + 64f * dp, viewWidth - sidePad, btnY + 110f * dp);
            drawPillButton(canvas, btnMpLeaveRoom, "✖  LEAVE ROOM",
                    Color.parseColor("#C62828"), Color.parseColor("#FF8A80"), 14f);
        }
    }

    private void drawMultiplayerResultOverlay(Canvas canvas) {
        paint.setColor(Color.parseColor("#CC040D1A"));
        canvas.drawRect(0, 0, viewWidth, viewHeight, paint);

        MultiplayerManager.MatchResult res = multiplayerManager.getLastMatchResult();
        float cardW = Math.min(viewWidth - 36f * dp, 370f * dp);
        float cardH = 360f * dp;
        float left = (viewWidth - cardW) * 0.5f;
        float top = (viewHeight - cardH) * 0.45f;

        tempRect.set(left, top, left + cardW, top + cardH);
        drawGlassCard(canvas, tempRect, Color.parseColor("#F50B1D3A"), Color.parseColor("#FFD700"));

        textPaint.setTextAlign(Paint.Align.CENTER);
        if (res != null) {
            boolean iWon = gameManager.getClientSessionId().equals(res.winnerId);
            String title = res.isDraw ? "🤝 MATCH DRAW!" : (iWon ? "🏆 VICTORY!" : "💥 DEFEAT");
            textPaint.setTextSize(26f * dp);
            textPaint.setColor(res.isDraw ? Color.parseColor("#00E5FF")
                    : (iWon ? Color.parseColor("#FFD700") : Color.parseColor("#FF5252")));
            canvas.drawText(title, tempRect.centerX(), top + 46f * dp, textPaint);

            textPaint.setTextSize(14f * dp);
            textPaint.setColor(Color.WHITE);
            canvas.drawText("Winner: " + res.winnerName, tempRect.centerX(), top + 78f * dp, textPaint);

            textPaint.setTextSize(16f * dp);
            textPaint.setColor(Color.parseColor("#00E5FF"));
            canvas.drawText(res.player1Name + ": " + res.player1Score + " pts", tempRect.centerX(), top + 118f * dp, textPaint);
            canvas.drawText(res.player2Name + ": " + res.player2Score + " pts", tempRect.centerX(), top + 146f * dp, textPaint);

            textPaint.setTextSize(14f * dp);
            textPaint.setColor(Color.parseColor("#B9F6CA"));
            canvas.drawText("🪙 Server-Validated Reward: +" + res.rewardCoins + " Coins", tempRect.centerX(), top + 182f * dp, textPaint);
        }

        float btnW = cardW - 40f * dp;
        float btnL = left + 20f * dp;
        btnMpPlayAgain.set(btnL, top + 214f * dp, btnL + btnW, top + 266f * dp);
        drawPillButton(canvas, btnMpPlayAgain, "↻  PLAY AGAIN (REMATCH)",
                Color.parseColor("#00C853"), Color.parseColor("#B9F6CA"), 15f);

        btnMpLeaveRoom.set(btnL, top + 280f * dp, btnL + btnW, top + 332f * dp);
        drawPillButton(canvas, btnMpLeaveRoom, "🚪  RETURN TO LOBBY",
                Color.parseColor("#1E3A5F"), Color.parseColor("#00E5FF"), 14.5f);
    }

    private void drawToastBanner(Canvas canvas) {
        float w = viewWidth - 36f * dp;
        tempRect.set(18f * dp, 90f * dp, 18f * dp + w, 134f * dp);
        drawGlassCard(canvas, tempRect, Color.parseColor("#EE0B1D3A"), Color.parseColor("#FFD700"));
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(12.5f * dp);
        textPaint.setColor(Color.parseColor("#FFD700"));
        canvas.drawText(toastBannerText, tempRect.centerX(), tempRect.centerY() + 4f * dp, textPaint);
    }

    private void drawGlassCard(Canvas canvas, RectF rect, int bgColor, int strokeColor) {
        paint.setColor(bgColor);
        canvas.drawRoundRect(rect, 14f * dp, 14f * dp, paint);
        strokePaint.setColor(strokeColor);
        strokePaint.setStrokeWidth(2f * dp);
        canvas.drawRoundRect(rect, 14f * dp, 14f * dp, strokePaint);
    }

    private void drawPillButton(Canvas canvas, RectF rect, String label, int fillColor, int borderColor, float textSp) {
        paint.setColor(fillColor);
        canvas.drawRoundRect(rect, 24f * dp, 24f * dp, paint);
        strokePaint.setColor(borderColor);
        strokePaint.setStrokeWidth(2f * dp);
        canvas.drawRoundRect(rect, 24f * dp, 24f * dp, strokePaint);

        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(textSp * dp);
        textPaint.setColor(Color.WHITE);
        canvas.drawText(label, rect.centerX(), rect.centerY() + (textSp * 0.34f * dp), textPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() != MotionEvent.ACTION_DOWN) {
            return true;
        }
        float x = event.getX();
        float y = event.getY();

        switch (screenState) {
            case MAIN_MENU:
                handleMainMenuTouch(x, y);
                break;
            case SINGLE_PLAYER_GAME:
                handleSinglePlayerTouch(x, y);
                break;
            case MULTIPLAYER_GAME:
                performBirdFlap();
                break;
            case GAME_OVER:
                if (btnRestartGame.contains(x, y)) {
                    soundManager.playButton();
                    startNewRun(false, System.currentTimeMillis());
                } else if (btnBackToMenu.contains(x, y)) {
                    soundManager.playButton();
                    switchScreen(ScreenState.MAIN_MENU);
                }
                break;
            case BIRD_SHOP:
                handleBirdShopTouch(x, y);
                break;
            case LEADERBOARD:
                handleLeaderboardTouch(x, y);
                break;
            case MULTIPLAYER_LOBBY:
                handleMultiplayerLobbyTouch(x, y);
                break;
            case MULTIPLAYER_RESULT:
                if (btnMpPlayAgain.contains(x, y)) {
                    soundManager.playButton();
                    multiplayerManager.requestPlayAgain();
                    switchScreen(ScreenState.MULTIPLAYER_LOBBY);
                } else if (btnMpLeaveRoom.contains(x, y)) {
                    soundManager.playButton();
                    multiplayerManager.leaveRoom();
                    switchScreen(ScreenState.MULTIPLAYER_LOBBY);
                }
                break;
        }
        invalidate();
        return true;
    }

    private void handleMainMenuTouch(float x, float y) {
        if (showDailyRewardModal) {
            if (btnDailyModalClaim.contains(x, y)) {
                GameManager.DailyRewardClaimResult result = gameManager.claimDailyReward();
                if (result.success) {
                    soundManager.playCoin();
                    spawnCoinBurst(viewWidth * 0.5f, viewHeight * 0.45f);
                    showDailyRewardModal = false;
                    showBannerNotice(result.message);
                } else {
                    soundManager.playHit();
                    showBannerNotice(result.message);
                }
            } else if (btnDailyModalClose.contains(x, y)) {
                soundManager.playButton();
                showDailyRewardModal = false;
            }
            return;
        }
        if (btnSoundToggle.contains(x, y)) {
            soundManager.toggleSound();
        } else if (btnMusicToggle.contains(x, y)) {
            soundManager.playButton();
            soundManager.toggleMusic();
        } else if (btnServerConfig.contains(x, y)) {
            soundManager.playButton();
            if (hostCallbacks != null) hostCallbacks.onRequestServerAndProfileDialog();
        } else if (btnPlaySingle.contains(x, y)) {
            soundManager.playButton();
            startNewRun(false, System.currentTimeMillis());
        } else if (btnPlayMulti.contains(x, y)) {
            soundManager.playButton();
            switchScreen(ScreenState.MULTIPLAYER_LOBBY);
        } else if (btnBirdShop.contains(x, y)) {
            soundManager.playButton();
            switchScreen(ScreenState.BIRD_SHOP);
        } else if (btnLeaderboard.contains(x, y)) {
            soundManager.playButton();
            switchScreen(ScreenState.LEADERBOARD);
        } else if (btnDailyReward.contains(x, y)) {
            soundManager.playButton();
            showDailyRewardModal = true;
        } else if (btnWatchAd.contains(x, y)) {
            soundManager.playButton();
            if (hostCallbacks != null) hostCallbacks.onRequestRewardedAd();
        }
    }

    private void handleSinglePlayerTouch(float x, float y) {
        if (isPaused) {
            if (btnResumeGame.contains(x, y)) {
                soundManager.playButton();
                isPaused = false;
            } else if (btnRestartGame.contains(x, y)) {
                soundManager.playButton();
                isPaused = false;
                startNewRun(false, System.currentTimeMillis());
            } else if (btnBackToMenu.contains(x, y)) {
                soundManager.playButton();
                isPaused = false;
                switchScreen(ScreenState.MAIN_MENU);
            }
            return;
        }
        if (btnPauseGame.contains(x, y)) {
            soundManager.playButton();
            isPaused = true;
            return;
        }
        performBirdFlap();
    }

    private void handleBirdShopTouch(float x, float y) {
        if (btnHeaderBack.contains(x, y)) {
            soundManager.playButton();
            switchScreen(ScreenState.MAIN_MENU);
            return;
        }
        if (btnShopWatchAd.contains(x, y)) {
            soundManager.playButton();
            if (hostCallbacks != null) hostCallbacks.onRequestRewardedAd();
            return;
        }
        List<GameManager.BirdSkin> skins = gameManager.getBirdCatalog();
        for (int i = 0; i < skins.size() && i < shopCardRects.length; i++) {
            if (shopCardRects[i].contains(x, y)) {
                GameManager.BirdSkin skin = skins.get(i);
                GameManager.PurchaseResult res = gameManager.purchaseOrSelectBird(skin.id);
                if (res == GameManager.PurchaseResult.SUCCESS) {
                    soundManager.playCoin();
                    showBannerNotice("Unlocked & equipped " + skin.name + "!");
                } else if (res == GameManager.PurchaseResult.ALREADY_UNLOCKED) {
                    soundManager.playButton();
                    showBannerNotice("Equipped " + skin.name + "!");
                } else if (res == GameManager.PurchaseResult.INSUFFICIENT_COINS) {
                    soundManager.playHit();
                    showBannerNotice("Need " + skin.price + " coins to unlock " + skin.name + ".");
                }
                break;
            }
        }
    }

    private void handleLeaderboardTouch(float x, float y) {
        if (btnHeaderBack.contains(x, y)) {
            soundManager.playButton();
            switchScreen(ScreenState.MAIN_MENU);
        } else if (btnTabLocal.contains(x, y)) {
            soundManager.playButton();
            leaderboardTab = 0;
        } else if (btnTabOnline.contains(x, y)) {
            soundManager.playButton();
            leaderboardTab = 1;
            refreshOnlineLeaderboard();
        } else if (btnTabMatches.contains(x, y)) {
            soundManager.playButton();
            leaderboardTab = 2;
            refreshOnlineLeaderboard();
        } else if (btnRefreshOnline.contains(x, y)) {
            soundManager.playButton();
            refreshOnlineLeaderboard();
        }
    }

    private void refreshOnlineLeaderboard() {
        leaderboardManager.fetchOnlineLeaderboard(new LeaderboardManager.OnlineFetchCallback() {
            @Override
            public void onOnlineDataLoaded(List<LeaderboardManager.ScoreEntry> globalScores,
                                           List<LeaderboardManager.OnlineMatchEntry> recentMatches,
                                           String statusMessage) {
                invalidate();
            }

            @Override
            public void onOnlineDataError(String errorMessage) {
                invalidate();
            }
        });
    }

    private void handleMultiplayerLobbyTouch(float x, float y) {
        if (btnHeaderBack.contains(x, y)) {
            soundManager.playButton();
            multiplayerManager.leaveRoom();
            switchScreen(ScreenState.MAIN_MENU);
            return;
        }
        if (btnMpEditServer.contains(x, y)) {
            soundManager.playButton();
            if (hostCallbacks != null) hostCallbacks.onRequestServerAndProfileDialog();
            return;
        }
        String activeRoom = multiplayerManager.getCurrentRoomCode();
        if (activeRoom == null || activeRoom.isEmpty()) {
            if (btnMpCreateRoom.contains(x, y)) {
                soundManager.playButton();
                multiplayerManager.createRoom();
            } else if (btnMpJoinRoom.contains(x, y)) {
                soundManager.playButton();
                if (hostCallbacks != null) hostCallbacks.onRequestJoinRoomDialog();
            }
        } else {
            if (btnMpReadyToggle.contains(x, y)) {
                soundManager.playButton();
                MultiplayerManager.PlayerSlot mySlot = multiplayerManager.getMySlot();
                boolean nextReady = (mySlot == null) || !mySlot.ready;
                multiplayerManager.setPlayerReady(nextReady);
            } else if (btnMpLeaveRoom.contains(x, y)) {
                soundManager.playButton();
                multiplayerManager.leaveRoom();
            }
        }
    }

    // --- MultiplayerManager.MultiplayerListener Callbacks ---

    @Override
    public void onStateChanged(MultiplayerManager.RoomState state, String statusText) {
        invalidate();
    }

    @Override
    public void onRoomUpdated(String roomCode, List<MultiplayerManager.PlayerSlot> players) {
        invalidate();
    }

    @Override
    public void onMatchStart(String matchId, long obstacleSeed) {
        soundManager.playCoin();
        startNewRun(true, obstacleSeed);
    }

    @Override
    public void onOpponentUpdated(MultiplayerManager.PlayerSlot opponent) {
        invalidate();
    }

    @Override
    public void onMatchResult(MultiplayerManager.MatchResult result) {
        soundManager.playCoin();
        switchScreen(ScreenState.MULTIPLAYER_RESULT);
    }

    @Override
    public void onMultiplayerError(String code, String message) {
        showBannerNotice(String.format(Locale.US, "[%s] %s", code, message));
    }
}
