package com.example;

import android.app.Activity;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdListener;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.rewarded.RewardedAd;
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback;

/**
 * AdManager integrates the Google Mobile Ads SDK (AdMob).
 * - Displays non-intrusive Banner Ads on menu screens (hidden during active gameplay).
 * - Provides optional Rewarded Ads for earning bonus coins in the Bird Shop / Main Menu.
 * - Uses official Google Test Ad Unit IDs by default and degrades gracefully if offline.
 */
public class AdManager {
    private static final String TAG = "AdManager";

    // Configured Google AdMob Ad Unit IDs
    public static final String DEFAULT_BANNER_AD_UNIT_ID = "ca-app-pub-1238798127993304/8249308413";
    public static final String DEFAULT_REWARDED_AD_UNIT_ID = "ca-app-pub-1238798127993304/1683900062";

    public static final int REWARD_COINS_AMOUNT = 50;

    public interface RewardCallback {
        void onCoinsRewarded(int bonusCoins);
        void onAdUnavailable(String reason);
    }

    private final Activity activity;
    private AdView bannerAdView;
    private RewardedAd rewardedAd;
    private boolean isLoadingRewarded = false;
    private boolean bannerVisibleOnMenus = true;

    public AdManager(Activity activity) {
        this.activity = activity;
    }

    /**
     * Initializes the Google Mobile Ads SDK and attaches a bottom banner container.
     */
    public void initialize(FrameLayout rootContainer) {
        try {
            MobileAds.initialize(activity, initializationStatus -> {
                Log.d(TAG, "AdMob SDK initialized.");
                activity.runOnUiThread(() -> {
                    setupBannerAd(rootContainer);
                    loadRewardedAd();
                });
            });
        } catch (Throwable t) {
            Log.w(TAG, "AdMob initialization skipped or failed: " + t.getMessage());
        }
    }

    private String getBannerAdUnitId() {
        try {
            String configured = BuildConfig.ADMOB_BANNER_AD_UNIT_ID;
            if (configured != null && !configured.trim().isEmpty()) {
                return configured.trim();
            }
        } catch (Exception ignored) {
        }
        return DEFAULT_BANNER_AD_UNIT_ID;
    }

    private String getRewardedAdUnitId() {
        try {
            String configured = BuildConfig.ADMOB_REWARDED_AD_UNIT_ID;
            if (configured != null && !configured.trim().isEmpty()) {
                return configured.trim();
            }
        } catch (Exception ignored) {
        }
        return DEFAULT_REWARDED_AD_UNIT_ID;
    }

    private void setupBannerAd(FrameLayout rootContainer) {
        try {
            bannerAdView = new AdView(activity);
            bannerAdView.setAdSize(AdSize.BANNER);
            bannerAdView.setAdUnitId(getBannerAdUnitId());

            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            bannerAdView.setLayoutParams(params);

            bannerAdView.setAdListener(new AdListener() {
                @Override
                public void onAdLoaded() {
                    Log.d(TAG, "Banner ad loaded.");
                    updateBannerVisibility();
                }

                @Override
                public void onAdFailedToLoad(@NonNull LoadAdError loadAdError) {
                    Log.d(TAG, "Banner ad failed to load: " + loadAdError.getMessage());
                }
            });

            rootContainer.addView(bannerAdView);
            AdRequest adRequest = new AdRequest.Builder().build();
            bannerAdView.loadAd(adRequest);
        } catch (Exception e) {
            Log.w(TAG, "Failed to setup banner ad: " + e.getMessage());
        }
    }

    /**
     * Controls whether the banner ad is visible. Must be hidden during active gameplay
     * so ads never obstruct touch controls or player visibility.
     */
    public void setMenuBannerVisible(boolean visible) {
        this.bannerVisibleOnMenus = visible;
        activity.runOnUiThread(this::updateBannerVisibility);
    }

    private void updateBannerVisibility() {
        if (bannerAdView != null) {
            bannerAdView.setVisibility(bannerVisibleOnMenus ? View.VISIBLE : View.GONE);
        }
    }

    public void loadRewardedAd() {
        if (isLoadingRewarded || rewardedAd != null) {
            return;
        }
        try {
            isLoadingRewarded = true;
            AdRequest adRequest = new AdRequest.Builder().build();
            RewardedAd.load(activity, getRewardedAdUnitId(), adRequest, new RewardedAdLoadCallback() {
                @Override
                public void onAdLoaded(@NonNull RewardedAd ad) {
                    rewardedAd = ad;
                    isLoadingRewarded = false;
                    Log.d(TAG, "Rewarded ad loaded and ready.");
                }

                @Override
                public void onAdFailedToLoad(@NonNull LoadAdError loadAdError) {
                    rewardedAd = null;
                    isLoadingRewarded = false;
                    Log.d(TAG, "Rewarded ad not available: " + loadAdError.getMessage());
                }
            });
        } catch (Exception e) {
            isLoadingRewarded = false;
            Log.w(TAG, "Error loading rewarded ad: " + e.getMessage());
        }
    }

    public boolean isRewardedAdReady() {
        return rewardedAd != null;
    }

    public void showRewardedAd(RewardCallback callback) {
        if (rewardedAd == null) {
            loadRewardedAd();
            if (callback != null) {
                callback.onAdUnavailable("Rewarded ad is still loading or unavailable. Try again shortly.");
            }
            return;
        }

        rewardedAd.setFullScreenContentCallback(new FullScreenContentCallback() {
            @Override
            public void onAdDismissedFullScreenContent() {
                rewardedAd = null;
                loadRewardedAd();
            }

            @Override
            public void onAdFailedToShowFullScreenContent(@NonNull AdError adError) {
                rewardedAd = null;
                loadRewardedAd();
                if (callback != null) {
                    callback.onAdUnavailable("Ad failed to display: " + adError.getMessage());
                }
            }
        });

        rewardedAd.show(activity, rewardItem -> {
            if (callback != null) {
                callback.onCoinsRewarded(REWARD_COINS_AMOUNT);
            }
        });
    }

    public void onPause() {
        if (bannerAdView != null) {
            bannerAdView.pause();
        }
    }

    public void onResume() {
        if (bannerAdView != null) {
            bannerAdView.resume();
        }
    }

    public void onDestroy() {
        if (bannerAdView != null) {
            bannerAdView.destroy();
            bannerAdView = null;
        }
    }
}
