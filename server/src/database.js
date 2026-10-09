const fs = require('fs');
const path = require('path');
const Database = require('better-sqlite3');

/**
 * Database layer using SQLite (better-sqlite3) for persistent storage of:
 * 1. Validated global high scores (leaderboard_scores)
 * 2. Authoritative 2-player online match results (multiplayer_matches)
 */
class GameDatabase {
  constructor(dbFilePath) {
    const resolvedPath = path.resolve(dbFilePath || './data/flybird.sqlite');
    const dir = path.dirname(resolvedPath);
    if (!fs.existsSync(dir)) {
      fs.mkdirSync(dir, { recursive: true });
    }

    this.db = new Database(resolvedPath);
    this.db.pragma('journal_mode = WAL');
    this.initSchema();
  }

  initSchema() {
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS leaderboard_scores (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        player_id TEXT NOT NULL,
        player_name TEXT NOT NULL,
        score INTEGER NOT NULL,
        coins INTEGER NOT NULL DEFAULT 0,
        duration_ms INTEGER NOT NULL,
        bird_name TEXT NOT NULL DEFAULT 'Blue Bird',
        created_at TEXT NOT NULL
      );

      CREATE INDEX IF NOT EXISTS idx_leaderboard_score_desc
        ON leaderboard_scores (score DESC);

      CREATE TABLE IF NOT EXISTS multiplayer_matches (
        match_id TEXT PRIMARY KEY,
        room_code TEXT NOT NULL,
        winner_id TEXT,
        winner_name TEXT NOT NULL,
        is_draw INTEGER NOT NULL DEFAULT 0,
        player1_id TEXT NOT NULL,
        player1_name TEXT NOT NULL,
        player1_score INTEGER NOT NULL DEFAULT 0,
        player2_id TEXT NOT NULL,
        player2_name TEXT NOT NULL,
        player2_score INTEGER NOT NULL DEFAULT 0,
        created_at TEXT NOT NULL
      );
    `);

    this.insertScoreStmt = this.db.prepare(`
      INSERT INTO leaderboard_scores (player_id, player_name, score, coins, duration_ms, bird_name, created_at)
      VALUES (@playerId, @playerName, @score, @coins, @durationMs, @birdName, @createdAt)
    `);

    this.topScoresStmt = this.db.prepare(`
      SELECT player_name AS playerName,
             MAX(score) AS score,
             coins,
             bird_name AS birdName,
             created_at AS createdAt
      FROM leaderboard_scores
      GROUP BY player_id
      ORDER BY score DESC, created_at ASC
      LIMIT ?
    `);

    this.insertMatchStmt = this.db.prepare(`
      INSERT OR REPLACE INTO multiplayer_matches (
        match_id, room_code, winner_id, winner_name, is_draw,
        player1_id, player1_name, player1_score,
        player2_id, player2_name, player2_score, created_at
      ) VALUES (
        @matchId, @roomCode, @winnerId, @winnerName, @isDraw,
        @player1Id, @player1Name, @player1Score,
        @player2Id, @player2Name, @player2Score, @createdAt
      )
    `);

    this.recentMatchesStmt = this.db.prepare(`
      SELECT match_id AS matchId,
             room_code AS roomCode,
             winner_name AS winnerName,
             is_draw AS isDraw,
             player1_name AS player1Name,
             player1_score AS player1Score,
             player2_name AS player2Name,
             player2_score AS player2Score,
             created_at AS createdAt
      FROM multiplayer_matches
      ORDER BY created_at DESC
      LIMIT ?
    `);
  }

  recordValidatedScore({ playerId, playerName, score, coins, durationMs, birdName }) {
    const createdAt = new Date().toISOString().slice(0, 16).replace('T', ' ');
    this.insertScoreStmt.run({
      playerId: String(playerId).slice(0, 64),
      playerName: String(playerName).slice(0, 16),
      score: Number(score),
      coins: Number(coins || 0),
      durationMs: Number(durationMs || 0),
      birdName: String(birdName || 'Blue Bird').slice(0, 24),
      createdAt
    });
  }

  recordMatchResult(match) {
    const createdAt = new Date().toISOString().slice(0, 16).replace('T', ' ');
    this.insertMatchStmt.run({
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
  }

  getTopLeaderboard(limit = 50) {
    return this.topScoresStmt.all(limit);
  }

  getRecentMatches(limit = 20) {
    return this.recentMatchesStmt.all(limit);
  }
}

module.exports = { GameDatabase };
