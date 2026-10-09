/**
 * ScoreManager performs server-side validation of submitted player scores and
 * calculates authoritative multiplayer match rewards so clients cannot spoof scores.
 */
class ScoreManager {
  constructor(database, options = {}) {
    this.database = database;
    this.maxPipesPerSecond = Number(options.maxPipesPerSecond || process.env.MAX_PIPES_PER_SECOND || 1.15);
    this.maxCoinsPerPipe = 2;
  }

  sanitizePlayerName(rawName) {
    if (!rawName || typeof rawName !== 'string') {
      return 'Pilot';
    }
    const cleaned = rawName.trim().replace(/[^a-zA-Z0-9_\- ]/g, '').slice(0, 16);
    return cleaned.length > 0 ? cleaned : 'Pilot';
  }

  /**
   * Validates whether a reported score and coin count are physically possible
   * given the elapsed match/flight duration in milliseconds.
   */
  validateScoreSubmission({ score, coins = 0, elapsedMs = 0 }) {
    const numericScore = Number(score);
    const numericCoins = Number(coins);
    const numericElapsed = Number(elapsedMs);

    if (!Number.isInteger(numericScore) || numericScore < 0 || numericScore > 9999) {
      return { valid: false, reason: 'Score out of valid bounds.' };
    }
    if (!Number.isInteger(numericCoins) || numericCoins < 0 || numericCoins > 9999) {
      return { valid: false, reason: 'Coin count out of valid bounds.' };
    }

    // If score > 0, check minimum plausible flight duration
    if (numericScore > 0) {
      const elapsedSeconds = Math.max(0.5, numericElapsed / 1000.0);
      const maxPlausibleScore = Math.ceil(elapsedSeconds * this.maxPipesPerSecond) + 2;
      if (numericScore > maxPlausibleScore) {
        return {
          valid: false,
          reason: `Score ${numericScore} exceeds maximum plausible rate (${maxPlausibleScore}) for ${elapsedSeconds.toFixed(1)}s flight.`
        };
      }
      const maxPlausibleCoins = (numericScore + 3) * this.maxCoinsPerPipe;
      if (numericCoins > maxPlausibleCoins) {
        return {
          valid: false,
          reason: 'Reported coin count exceeds plausible pipe-to-coin ratio.'
        };
      }
    }

    return {
      valid: true,
      validatedScore: numericScore,
      validatedCoins: numericCoins
    };
  }

  /**
   * Validates a standalone leaderboard submission and saves it to the database.
   */
  submitLeaderboardScore(payload) {
    const playerId = String(payload.playerId || '').trim();
    const playerName = this.sanitizePlayerName(payload.playerName);
    const score = Number(payload.score);
    const coins = Number(payload.coins || 0);
    const durationMs = Number(payload.durationMs || 0);
    const birdName = String(payload.birdName || 'Blue Bird').slice(0, 24);

    if (!playerId) {
      return { ok: false, error: 'Missing playerId.' };
    }

    const check = this.validateScoreSubmission({ score, coins, elapsedMs: durationMs });
    if (!check.valid) {
      return { ok: false, error: check.reason };
    }

    if (check.validatedScore > 0) {
      this.database.recordValidatedScore({
        playerId,
        playerName,
        score: check.validatedScore,
        coins: check.validatedCoins,
        durationMs,
        birdName
      });
    }

    return { ok: true, validatedScore: check.validatedScore };
  }

  /**
   * Computes the authoritative winner and coin reward for a completed 2-player match.
   */
  evaluateMatchOutcome(roomCode, matchId, player1, player2) {
    const p1Score = Math.max(0, Number(player1.score || 0));
    const p2Score = Math.max(0, Number(player2.score || 0));

    let isDraw = false;
    let winnerId = '';
    let winnerName = 'Draw';

    if (p1Score > p2Score) {
      winnerId = player1.playerId;
      winnerName = player1.playerName;
    } else if (p2Score > p1Score) {
      winnerId = player2.playerId;
      winnerName = player2.playerName;
    } else {
      isDraw = true;
    }

    const rewardCoins = isDraw ? 10 : 25;

    const record = {
      matchId,
      roomCode,
      winnerId,
      winnerName,
      isDraw,
      player1Id: player1.playerId,
      player1Name: player1.playerName,
      player1Score: p1Score,
      player2Id: player2.playerId,
      player2Name: player2.playerName,
      player2Score: p2Score,
      rewardCoins
    };

    this.database.recordMatchResult(record);
    return record;
  }
}

module.exports = { ScoreManager };
