require('dotenv').config();
const http = require('http');
const express = require('express');
const cors = require('cors');
const { WebSocketServer } = require('ws');
const { GameDatabase } = require('./database');
const { ScoreManager } = require('./scoreManager');
const { RoomManager } = require('./roomManager');

const PORT = Number(process.env.PORT || 8080);
const DB_PATH = process.env.DB_PATH || './data/flybird.sqlite';

const app = express();
app.use(cors());
app.use(express.json({ limit: '32kb' }));

const database = new GameDatabase(DB_PATH);
const scoreManager = new ScoreManager(database);
const roomManager = new RoomManager(scoreManager);

// Health check endpoint
app.get('/health', (req, res) => {
  res.json({
    status: 'ok',
    service: 'fly-bird-multiplayer-server',
    activeRooms: roomManager.rooms.size,
    timestamp: new Date().toISOString()
  });
});

// Online Leaderboard & Recent 2P Matches endpoint
app.get('/api/leaderboard', (req, res) => {
  try {
    const leaderboard = database.getTopLeaderboard(50);
    const recentMatches = database.getRecentMatches(20);
    res.json({
      ok: true,
      leaderboard,
      recentMatches
    });
  } catch (err) {
    res.status(500).json({ ok: false, error: 'Failed to query leaderboard.' });
  }
});

// Validated Score Submission endpoint
app.post('/api/leaderboard', (req, res) => {
  try {
    const result = scoreManager.submitLeaderboardScore(req.body || {});
    if (!result.ok) {
      return res.status(400).json(result);
    }
    res.json(result);
  } catch (err) {
    res.status(500).json({ ok: false, error: 'Server error validating score.' });
  }
});

const server = http.createServer(app);
const wss = new WebSocketServer({ server });

wss.on('connection', (ws) => {
  ws.isAlive = true;
  ws.on('pong', () => {
    ws.isAlive = true;
  });

  ws.on('message', (rawBuffer) => {
    let msg;
    try {
      msg = JSON.parse(rawBuffer.toString('utf-8'));
    } catch (err) {
      return roomManager.sendError(ws, 'BAD_JSON', 'Malformed JSON payload.');
    }

    switch (msg.type) {
      case 'CREATE_ROOM':
        roomManager.createRoom(ws, msg);
        break;
      case 'JOIN_ROOM':
        roomManager.joinRoom(ws, msg);
        break;
      case 'RECONNECT_ROOM':
        roomManager.reconnectRoom(ws, msg);
        break;
      case 'SET_READY':
        roomManager.setPlayerReady(ws, msg);
        break;
      case 'SCORE_UPDATE':
        roomManager.handleScoreUpdate(ws, msg);
        break;
      case 'PLAYER_ELIMINATED':
        roomManager.handlePlayerEliminated(ws, msg);
        break;
      case 'PLAY_AGAIN':
        roomManager.handlePlayAgain(ws, msg);
        break;
      case 'LEAVE_ROOM':
        roomManager.leaveRoom(ws, msg);
        break;
      default:
        roomManager.sendError(ws, 'UNKNOWN_TYPE', `Unsupported message type: ${msg.type}`);
        break;
    }
  });

  ws.on('close', () => {
    roomManager.handleSocketDisconnect(ws);
  });
});

// WebSocket ping/pong heartbeat interval every 20s
const heartbeatInterval = setInterval(() => {
  wss.clients.forEach((ws) => {
    if (ws.isAlive === false) {
      roomManager.handleSocketDisconnect(ws);
      return ws.terminate();
    }
    ws.isAlive = false;
    ws.ping();
  });
}, 20000);

wss.on('close', () => {
  clearInterval(heartbeatInterval);
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`Fly Bird Multiplayer & Leaderboard Server listening on port ${PORT}`);
});
