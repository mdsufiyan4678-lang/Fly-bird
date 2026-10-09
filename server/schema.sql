-- SQLite / PostgreSQL Compatible Schema for Fly Bird — Online Multiplayer Edition

CREATE TABLE IF NOT EXISTS leaderboard_scores (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    player_id TEXT NOT NULL,
    player_name TEXT NOT NULL,
    score INTEGER NOT NULL CHECK (score >= 0),
    coins INTEGER NOT NULL DEFAULT 0 CHECK (coins >= 0),
    duration_ms INTEGER NOT NULL CHECK (duration_ms >= 0),
    bird_name TEXT NOT NULL DEFAULT 'Blue Bird',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_leaderboard_score_desc
    ON leaderboard_scores (score DESC, created_at ASC);

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
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_matches_created_at
    ON multiplayer_matches (created_at DESC);
