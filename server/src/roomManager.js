const crypto = require('crypto');

/**
 * RoomManager manages 2-player rooms, unique 6-digit room codes, player readiness,
 * authoritative match state, disconnect grace windows, and abandoned room cleanup.
 */
class RoomManager {
  constructor(scoreManager, options = {}) {
    this.scoreManager = scoreManager;
    this.rooms = new Map(); // roomCode -> room object
    this.roomIdleTimeoutMs = Number(options.roomIdleTimeoutMs || process.env.ROOM_IDLE_TIMEOUT_MS || 300000);
    this.reconnectGraceMs = Number(options.reconnectGraceMs || process.env.RECONNECT_GRACE_MS || 30000);

    // Periodic cleanup of abandoned rooms every 30 seconds
    this.cleanupTimer = setInterval(() => this.cleanupAbandonedRooms(), 30000);
    if (this.cleanupTimer.unref) {
      this.cleanupTimer.unref();
    }
  }

  generateUniqueRoomCode() {
    for (let attempt = 0; attempt < 50; attempt++) {
      const code = String(Math.floor(100000 + Math.random() * 900000));
      if (!this.rooms.has(code)) {
        return code;
      }
    }
    return String(Date.now()).slice(-6);
  }

  createRoom(ws, { playerId, playerName, birdSkinId = 0 }) {
    if (!playerId) {
      return this.sendError(ws, 'INVALID_PLAYER', 'Missing playerId.');
    }
    this.removePlayerFromExistingRooms(playerId);

    const roomCode = this.generateUniqueRoomCode();
    const cleanName = this.scoreManager.sanitizePlayerName(playerName);

    const room = {
      roomCode,
      status: 'WAITING', // WAITING | READY | PLAYING | FINISHED
      matchId: '',
      seed: 0,
      matchStartTimeMs: 0,
      lastActivityMs: Date.now(),
      players: [
        {
          playerId,
          playerName: cleanName,
          birdSkinId: Number(birdSkinId || 0),
          ready: false,
          connected: true,
          score: 0,
          coins: 0,
          alive: true,
          normalizedY: 0.5,
          ws,
          disconnectTimer: null
        }
      ]
    };

    ws.roomCode = roomCode;
    ws.playerId = playerId;
    this.rooms.set(roomCode, room);

    this.sendJson(ws, {
      type: 'ROOM_CREATED',
      roomCode,
      status: room.status,
      players: this.serializePlayers(room)
    });
  }

  joinRoom(ws, { roomCode, playerId, playerName, birdSkinId = 0 }) {
    const cleanCode = String(roomCode || '').trim();
    if (!/^\d{6}$/.test(cleanCode)) {
      return this.sendError(ws, 'INVALID_ROOM_CODE', 'Room code must be a 6-digit number.');
    }

    const room = this.rooms.get(cleanCode);
    if (!room) {
      return this.sendError(ws, 'INVALID_ROOM_CODE', `Room #${cleanCode} does not exist or has expired.`);
    }

    if (room.status === 'PLAYING') {
      const existing = room.players.find((p) => p.playerId === playerId);
      if (!existing) {
        return this.sendError(ws, 'MATCH_ALREADY_RUNNING', `Room #${cleanCode} is already in an active match.`);
      }
    }

    // Check if player is rejoining their existing slot
    const existingSlot = room.players.find((p) => p.playerId === playerId);
    if (existingSlot) {
      return this.reconnectRoom(ws, { roomCode: cleanCode, playerId, playerName, birdSkinId });
    }

    if (room.players.length >= 2) {
      return this.sendError(ws, 'ROOM_FULL', `Room #${cleanCode} already has 2 players.`);
    }

    this.removePlayerFromExistingRooms(playerId);

    const cleanName = this.scoreManager.sanitizePlayerName(playerName);
    room.players.push({
      playerId,
      playerName: cleanName,
      birdSkinId: Number(birdSkinId || 0),
      ready: false,
      connected: true,
      score: 0,
      coins: 0,
      alive: true,
      normalizedY: 0.5,
      ws,
      disconnectTimer: null
    });

    ws.roomCode = cleanCode;
    ws.playerId = playerId;
    room.lastActivityMs = Date.now();
    room.status = room.players.length === 2 ? 'READY' : 'WAITING';

    this.broadcastRoomUpdate(room);
  }

  reconnectRoom(ws, { roomCode, playerId, playerName }) {
    const cleanCode = String(roomCode || '').trim();
    const room = this.rooms.get(cleanCode);
    if (!room) {
      return this.sendError(ws, 'INVALID_ROOM_CODE', `Room #${cleanCode} is no longer active.`);
    }

    const slot = room.players.find((p) => p.playerId === playerId);
    if (!slot) {
      return this.joinRoom(ws, { roomCode: cleanCode, playerId, playerName });
    }

    if (slot.disconnectTimer) {
      clearTimeout(slot.disconnectTimer);
      slot.disconnectTimer = null;
    }

    slot.ws = ws;
    slot.connected = true;
    if (playerName) {
      slot.playerName = this.scoreManager.sanitizePlayerName(playerName);
    }
    ws.roomCode = cleanCode;
    ws.playerId = playerId;
    room.lastActivityMs = Date.now();

    this.broadcastRoomUpdate(room);
  }

  setPlayerReady(ws, { roomCode, playerId, ready }) {
    const room = this.rooms.get(String(roomCode || '').trim());
    if (!room) {
      return this.sendError(ws, 'INVALID_ROOM_CODE', 'Room not found.');
    }
    if (room.status === 'PLAYING') {
      return;
    }

    const slot = room.players.find((p) => p.playerId === playerId);
    if (!slot) {
      return;
    }

    slot.ready = Boolean(ready);
    room.lastActivityMs = Date.now();

    if (room.players.length === 2 && room.players.every((p) => p.ready && p.connected)) {
      this.startMatch(room);
    } else {
      this.broadcastRoomUpdate(room);
    }
  }

  startMatch(room) {
    room.status = 'PLAYING';
    room.matchId = 'm_' + Date.now() + '_' + crypto.randomBytes(3).toString('hex');
    room.seed = Math.floor(100000 + Math.random() * 900000000);
    room.matchStartTimeMs = Date.now();
    room.lastActivityMs = Date.now();

    for (const p of room.players) {
      p.score = 0;
      p.coins = 0;
      p.alive = true;
      p.normalizedY = 0.5;
      p.ready = false;
    }

    const payload = {
      type: 'MATCH_START',
      roomCode: room.roomCode,
      matchId: room.matchId,
      seed: room.seed,
      startTime: room.matchStartTimeMs,
      players: this.serializePlayers(room)
    };

    for (const p of room.players) {
      this.sendJson(p.ws, payload);
    }
  }

  handleScoreUpdate(ws, { roomCode, playerId, score, coins, normalizedY, alive }) {
    const room = this.rooms.get(String(roomCode || '').trim());
    if (!room || room.status !== 'PLAYING') {
      return;
    }

    const slot = room.players.find((p) => p.playerId === playerId);
    if (!slot || !slot.alive) {
      return;
    }

    const serverElapsedMs = Math.max(500, Date.now() - room.matchStartTimeMs);
    const validation = this.scoreManager.validateScoreSubmission({
      score: Number(score || 0),
      coins: Number(coins || 0),
      elapsedMs: serverElapsedMs
    });

    if (!validation.valid) {
      return this.sendError(ws, 'VALIDATION_FAILED', validation.reason);
    }

    // Enforce monotonic non-decreasing score within a match
    if (validation.validatedScore >= slot.score) {
      slot.score = validation.validatedScore;
      slot.coins = validation.validatedCoins;
    }
    slot.normalizedY = Math.max(0, Math.min(1, Number(normalizedY || 0.5)));
    slot.alive = Boolean(alive);
    room.lastActivityMs = Date.now();

    // Broadcast live state to the opponent
    for (const other of room.players) {
      if (other.playerId !== playerId) {
        this.sendJson(other.ws, {
          type: 'OPPONENT_STATE',
          playerId: slot.playerId,
          playerName: slot.playerName,
          score: slot.score,
          alive: slot.alive,
          normalizedY: slot.normalizedY
        });
      }
    }
  }

  handlePlayerEliminated(ws, { roomCode, playerId, finalScore, coins }) {
    const room = this.rooms.get(String(roomCode || '').trim());
    if (!room || room.status !== 'PLAYING') {
      return;
    }

    const slot = room.players.find((p) => p.playerId === playerId);
    if (!slot) {
      return;
    }

    const serverElapsedMs = Math.max(500, Date.now() - room.matchStartTimeMs);
    const validation = this.scoreManager.validateScoreSubmission({
      score: Number(finalScore || slot.score),
      coins: Number(coins || slot.coins),
      elapsedMs: serverElapsedMs
    });

    if (validation.valid && validation.validatedScore >= slot.score) {
      slot.score = validation.validatedScore;
      slot.coins = validation.validatedCoins;
    }
    slot.alive = false;
    room.lastActivityMs = Date.now();

    // Notify opponent that this player crashed
    for (const other of room.players) {
      if (other.playerId !== playerId) {
        this.sendJson(other.ws, {
          type: 'OPPONENT_STATE',
          playerId: slot.playerId,
          playerName: slot.playerName,
          score: slot.score,
          alive: false,
          normalizedY: slot.normalizedY
        });
      }
    }

    // Conclude match when both players are eliminated OR surviving player already holds a higher score
    const allEliminated = room.players.every((p) => !p.alive);
    const survivor = room.players.find((p) => p.alive);
    const survivorAlreadyLeading = survivor && survivor.score > slot.score && room.players.length === 2;

    if (allEliminated || survivorAlreadyLeading) {
      this.finishMatch(room, 'Both flights completed');
    }
  }

  finishMatch(room, reason) {
    if (room.status === 'FINISHED' || room.players.length < 2) {
      return;
    }
    room.status = 'FINISHED';
    room.lastActivityMs = Date.now();

    const [p1, p2] = room.players;
    const outcome = this.scoreManager.evaluateMatchOutcome(room.roomCode, room.matchId, p1, p2);

    const payload = {
      type: 'MATCH_OVER',
      roomCode: room.roomCode,
      matchId: outcome.matchId,
      winnerId: outcome.winnerId,
      winnerName: outcome.winnerName,
      isDraw: outcome.isDraw,
      p1Name: outcome.player1Name,
      p1Score: outcome.player1Score,
      p2Name: outcome.player2Name,
      p2Score: outcome.player2Score,
      rewardCoins: outcome.rewardCoins,
      reason: reason || 'Match completed'
    };

    for (const p of room.players) {
      this.sendJson(p.ws, payload);
    }
  }

  handlePlayAgain(ws, { roomCode, playerId }) {
    const room = this.rooms.get(String(roomCode || '').trim());
    if (!room) {
      return this.sendError(ws, 'INVALID_ROOM_CODE', 'Room expired.');
    }

    const slot = room.players.find((p) => p.playerId === playerId);
    if (!slot) {
      return;
    }

    room.status = room.players.length === 2 ? 'READY' : 'WAITING';
    slot.ready = true;
    slot.alive = true;
    slot.score = 0;
    room.lastActivityMs = Date.now();

    if (room.players.length === 2 && room.players.every((p) => p.ready && p.connected)) {
      this.startMatch(room);
    } else {
      this.broadcastRoomUpdate(room);
    }
  }

  leaveRoom(ws, { roomCode, playerId }) {
    const code = String(roomCode || ws.roomCode || '').trim();
    const id = playerId || ws.playerId;
    if (!code || !this.rooms.has(code)) {
      return;
    }

    const room = this.rooms.get(code);
    const leavingIdx = room.players.findIndex((p) => p.playerId === id);
    if (leavingIdx === -1) {
      return;
    }

    const leavingPlayer = room.players[leavingIdx];
    if (leavingPlayer.disconnectTimer) {
      clearTimeout(leavingPlayer.disconnectTimer);
    }

    if (room.status === 'PLAYING' && room.players.length === 2) {
      leavingPlayer.alive = false;
      this.finishMatch(room, `${leavingPlayer.playerName} left the match`);
    }

    room.players.splice(leavingIdx, 1);
    if (room.players.length === 0) {
      this.rooms.delete(code);
    } else {
      room.status = 'WAITING';
      room.players[0].ready = false;
      this.broadcastRoomUpdate(room);
    }
  }

  handleSocketDisconnect(ws) {
    const code = ws.roomCode;
    const playerId = ws.playerId;
    if (!code || !playerId || !this.rooms.has(code)) {
      return;
    }

    const room = this.rooms.get(code);
    const slot = room.players.find((p) => p.playerId === playerId);
    if (!slot) {
      return;
    }

    slot.connected = false;
    slot.ws = null;
    this.broadcastRoomUpdate(room);

    slot.disconnectTimer = setTimeout(() => {
      this.leaveRoom({ roomCode: code, playerId }, { roomCode: code, playerId });
    }, this.reconnectGraceMs);
  }

  removePlayerFromExistingRooms(playerId) {
    for (const [code, room] of this.rooms.entries()) {
      if (room.players.some((p) => p.playerId === playerId)) {
        this.leaveRoom({ roomCode: code, playerId }, { roomCode: code, playerId });
      }
    }
  }

  cleanupAbandonedRooms() {
    const now = Date.now();
    for (const [code, room] of this.rooms.entries()) {
      const allOffline = room.players.every((p) => !p.connected);
      const idleTooLong = now - room.lastActivityMs > this.roomIdleTimeoutMs;
      if (room.players.length === 0 || allOffline || idleTooLong) {
        for (const p of room.players) {
          if (p.disconnectTimer) clearTimeout(p.disconnectTimer);
        }
        this.rooms.delete(code);
      }
    }
  }

  serializePlayers(room) {
    return room.players.map((p) => ({
      playerId: p.playerId,
      playerName: p.playerName,
      birdSkinId: p.birdSkinId,
      ready: p.ready,
      connected: p.connected,
      score: p.score,
      alive: p.alive,
      normalizedY: p.normalizedY
    }));
  }

  broadcastRoomUpdate(room) {
    const payload = {
      type: 'ROOM_UPDATE',
      roomCode: room.roomCode,
      status: room.status,
      players: this.serializePlayers(room)
    };
    for (const p of room.players) {
      this.sendJson(p.ws, payload);
    }
  }

  sendError(ws, code, message) {
    this.sendJson(ws, {
      type: 'ERROR',
      code,
      message
    });
  }

  sendJson(ws, obj) {
    if (ws && ws.readyState === 1) {
      try {
        ws.send(JSON.stringify(obj));
      } catch (err) {
        // Ignore broken pipe
      }
    }
  }
}

module.exports = { RoomManager };
