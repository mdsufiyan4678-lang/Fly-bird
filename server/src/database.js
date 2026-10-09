const fs = require('fs');
const path = require('path');

/**
 * Pure JavaScript persistent database engine with atomic disk writes.
 * Avoids native C++ node-gyp compilation failures on cloud hosts (Render/Railway)
 * while persisting leaderboard_scores and multiplayer_matches reliably on disk.
 */
class GameDatabase {
  constructor(dbFilePath) {
    const resolvedPath = path.resolve(dbFilePath || './data/flybird-db.json');
    const dir = path.dirname(resolvedPath);
    if (!fs.existsSync(dir)) {
      fs.mkdirSync(dir, { recursive: true });
    }
    this.filePath = resolvedPath;
    this.state = {
      leaderboardScores: [],
      multiplayerMatches: []
    };
    this.loadFromDisk();
  }

  loadFromDisk() {
    try {
      if (fs.existsSync(this.filePath)) {
        const raw = fs.readFileSync(this.filePath, 'utf-8');
        const parsed = JSON.parse(raw);
        if (Array.isArray(parsed.leaderboardScores)) {
          this.state.leaderboardScores = parsed.leaderboardScores;
        }
        if (Array.isArray(parsed.multiplayerMatches)) {
          this.state.multiplayerMatches = parsed.multiplayerMatches;
        }
      }
    } catch (err) {
      console.warn('Initializing fresh database store:', err.message);
    }
  }

  saveToDisk() {
    try {
      const tmpPath = this.filePath + '.tmp';
      fs.writeFileSync(tmpPath, JSON.stringify(this.state, null, 2), 'utf-8');
      fs.renameSync(tmpPath, this.filePath);
    } catch (err) {
      console.warn('Error persisting database:', err.message);
    }
  }

  recordValidatedScore({ playerId, playerName, score, coins, durationMs, birdName }) {
    const createdAt = new Date().toISOString().slice(0, 16).replace('T', ' ');
    const cleanId = String(playerId).slice(0, 64);
    const cleanName = String(playerName).slice(0, 16);
    const numScore = Number(score);
    const numCoins = Number(coins || 0);
    const cleanBird = String(birdName || 'Blue Bird').slice(0, 24);

    const existingIdx = this.state.leaderboardScores.findIndex((e) => e.playerId === cleanId);
    if (existingIdx >= 0) {
      if (numScore >= this.state.leaderboardScores[existingIdx].score) {
        this.state.leaderboardScores[existingIdx] = {
          playerId: cleanId,
          playerName: cleanName,
          score: numScore,
          coins: numCoins,
          durationMs: Number(durationMs || 0),
          birdName: cleanBird,
          createdAt
        };
      }
    } else {
      this.state.leaderboardScores.push({
        playerId: cleanId,
        playerName: cleanName,
        score: numScore,
        coins: numCoins,
        durationMs: Number(durationMs || 0),
        birdName: cleanBird,
        createdAt
      });
    }

    this.state.leaderboardScores.sort((a, b) => b.score - a.score);
    if (this.state.leaderboardScores.length > 200) {
      this.state.leaderboardScores.length = 200;
    }
    this.saveToDisk();
  }

  recordMatchResult(match) {
    const createdAt = new Date().toISOString().slice(0, 16).replace('T', ' ');
    this.state.multiplayerMatches.unshift({
      matchId: match.matchId,
      roomCode: match.roomCode,
      winnerId: match.winnerId || '',
      winnerName: match.winnerName || 'Draw',
      isDraw: match.isDraw ? 1 : 0,
      player1Id: match.player1Id,
      player1Name: match.player1Name,
      player1Score: match.player1Score,
      player2Id: match.player2Id,
      player2Name: match.player2Name,
      player2Score: match.player2Score,
      createdAt
    });
    if (this.state.multiplayerMatches.length > 100) {
      this.state.multiplayerMatches.length = 100;
    }
    this.saveToDisk();
  }

  getTopLeaderboard(limit = 50) {
    return this.state.leaderboardScores.slice(0, limit);
  }

  getRecentMatches(limit = 20) {
    return this.state.multiplayerMatches.slice(0, limit);
  }
}

module.exports = { GameDatabase };
