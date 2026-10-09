package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  private lateinit var context: Context

  private class AdjustableClock(
    var wallTimeMs: Long,
    var monotonicMs: Long
  ) : GameManager.ClockProvider {
    override fun currentTimeMillis(): Long = wallTimeMs
    override fun elapsedRealtime(): Long = monotonicMs

    fun advanceHonestTime(deltaMs: Long) {
      wallTimeMs += deltaMs
      monotonicMs += deltaMs
    }
  }

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    context.getSharedPreferences("fly_bird_game_prefs", Context.MODE_PRIVATE)
      .edit()
      .clear()
      .commit()
  }

  @Test
  fun `read string from context`() {
    val appName = context.getString(R.string.app_name)
    assertEquals("Fly Bird", appName)
  }

  @Test
  fun `daily login reward grants 50 coins once per day and blocks duplicate same-day claims`() {
    val clock = AdjustableClock(
      wallTimeMs = 1_760_000_000_000L,
      monotonicMs = 100_000L
    )
    val manager = GameManager(context, clock)
    val startingCoins = manager.coins

    assertTrue(manager.canClaimDailyReward())
    val firstClaim = manager.claimDailyReward()
    assertTrue(firstClaim.success)
    assertEquals(50, firstClaim.coinsAwarded)
    assertEquals(1, firstClaim.newStreak)
    assertEquals(startingCoins + 50, manager.coins)

    // Second attempt on the same day must be rejected
    assertFalse(manager.canClaimDailyReward())
    assertEquals(GameManager.DailyRewardStatus.ALREADY_CLAIMED_TODAY, manager.dailyRewardStatus)
    val duplicateClaim = manager.claimDailyReward()
    assertFalse(duplicateClaim.success)
    assertEquals(0, duplicateClaim.coinsAwarded)
    assertEquals(startingCoins + 50, manager.coins)

    // Advance 24 hours honestly -> next day claim succeeds and increments streak
    clock.advanceHonestTime(24L * 3600L * 1000L)
    assertTrue(manager.canClaimDailyReward())
    val nextDayClaim = manager.claimDailyReward()
    assertTrue(nextDayClaim.success)
    assertEquals(50, nextDayClaim.coinsAwarded)
    assertEquals(2, nextDayClaim.newStreak)
    assertEquals(startingCoins + 100, manager.coins)
  }

  @Test
  fun `daily login reward detects and blocks forward and backward clock manipulation`() {
    val clock = AdjustableClock(
      wallTimeMs = 1_760_000_000_000L,
      monotonicMs = 500_000L
    )
    val manager = GameManager(context, clock)
    assertTrue(manager.claimDailyReward().success)
    val coinsAfterFirstClaim = manager.coins

    // Exploit attempt 1: User jumps system wall clock forward 25 hours while only 1 minute elapsed in reality
    clock.wallTimeMs += 25L * 3600L * 1000L
    clock.monotonicMs += 60_000L

    assertEquals(GameManager.DailyRewardStatus.CLOCK_TAMPER_DETECTED, manager.dailyRewardStatus)
    assertFalse(manager.canClaimDailyReward())
    val spoofedForwardClaim = manager.claimDailyReward()
    assertFalse(spoofedForwardClaim.success)
    assertEquals(coinsAfterFirstClaim, manager.coins)

    // Restore honest wall clock and advance 1 honest day, then test backward clock rollback exploit
    clock.wallTimeMs = 1_760_000_000_000L + 24L * 3600L * 1000L
    clock.monotonicMs = 500_000L + 24L * 3600L * 1000L
    assertTrue(manager.canClaimDailyReward())

    // Exploit attempt 2: User rolls system clock backward by 2 days
    clock.wallTimeMs -= 48L * 3600L * 1000L
    assertEquals(GameManager.DailyRewardStatus.CLOCK_TAMPER_DETECTED, manager.dailyRewardStatus)
    assertFalse(manager.claimDailyReward().success)
  }
}
