/**
 * Session controller over BridgeClient: owns polling, auto-reconnect, and the
 * `npc.list()` probe. vscode-free so it is unit-testable.
 */
import { BridgeClient, BridgeResponse } from './luabridge';

export type SessionState = 'disconnected' | 'connecting' | 'connected' | 'error';

export interface StatusInfo {
    raw: string;
    scripts: number;
    npcs: number;
    world: string;
}

export interface NpcInfo {
    name: string;
    x: number;
    y: number;
    z: number;
}

export interface SessionOptions {
    host: string;
    port: number;
    autoReconnect: boolean;
    pollIntervalSeconds: number;
}

/** Lua snippet returning `name,x,y,z` lines for every NPC. */
export const NPC_LIST_LUA = [
    'local out = {}',
    'for n,p in pairs(npc.list()) do',
    '  out[#out+1] = string.format("%s,%.2f,%.2f,%.2f", n, p.x, p.y, p.z)',
    'end',
    'table.sort(out)',
    'return table.concat(out, "\\n")',
].join('\n');

/** Parse a `status` reply: `"ok; scripts=N; npcs=N; world=<id>"`. */
export function parseStatus(raw: string): StatusInfo {
    const info: StatusInfo = { raw, scripts: 0, npcs: 0, world: '' };
    for (const part of raw.split(';')) {
        const kv = part.trim().split('=');
        if (kv.length !== 2) {
            continue;
        }
        const key = kv[0].trim();
        const val = kv[1].trim();
        if (key === 'scripts') {
            info.scripts = parseInt(val, 10) || 0;
        } else if (key === 'npcs') {
            info.npcs = parseInt(val, 10) || 0;
        } else if (key === 'world') {
            info.world = val;
        }
    }
    return info;
}

/** Parse the NPC_LIST_LUA result: CSV `name,x,y,z` lines, sorted. */
export function parseNpcs(text: string): NpcInfo[] {
    const out: NpcInfo[] = [];
    for (const line of text.split('\n')) {
        const t = line.trim();
        if (!t) {
            continue;
        }
        const c = t.split(',');
        if (c.length !== 4) {
            continue;
        }
        out.push({
            name: c[0],
            x: parseFloat(c[1]),
            y: parseFloat(c[2]),
            z: parseFloat(c[3]),
        });
    }
    return out;
}

/** Best human text for a reply: output first, then `=> result`. */
export function replyText(r: BridgeResponse): string {
    if (!r.ok) {
        return 'error: ' + (r.error ?? 'unknown');
    }
    const parts: string[] = [];
    if (r.output) {
        parts.push(r.output);
    }
    if (r.result !== undefined && r.result !== '') {
        parts.push('=> ' + r.result);
    }
    return parts.join('\n') || 'ok';
}

export class LuaBridgeSession {
    readonly client = new BridgeClient();
    private timer: ReturnType<typeof setInterval> | null = null;
    private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
    private options: SessionOptions | null = null;
    private pendingReconnect = false;

    onStateChanged: ((s: SessionState, detail: string) => void) | null = null;
    /** Fired after each successful poll with fresh status + NPC list. */
    onPoll: ((status: StatusInfo, npcs: NpcInfo[]) => void) | null = null;
    /** Fired when a poll fails while connected (e.g. transient drop). */
    onPollError: ((message: string) => void) | null = null;

    constructor() {
        this.client.onStateChanged = (s, d) => this.forwardState(s, d);
        this.client.onClosed = () => this.scheduleReconnect();
    }

    get state(): SessionState {
        return this.client.state;
    }

    connect(options: SessionOptions): void {
        this.options = options;
        this.client.connect(options.host, options.port).then(() => {
            this.startPolling();
            void this.refresh();
        }).catch(() => {
            this.scheduleReconnect();
        });
    }

    disconnect(): void {
        this.stopPolling();
        this.clearReconnect();
        this.client.disconnect();
    }

    reconnect(): void {
        this.disconnect();
        if (this.options) {
            this.connect(this.options);
        }
    }

    async refresh(): Promise<void> {
        if (!this.client.isConnected) {
            return;
        }
        try {
            const [statusRes, npcRes] = await Promise.all([
                this.client.status(),
                this.client.eval(NPC_LIST_LUA),
            ]);
            const status = parseStatus(replyText(statusRes));
            const npcText = npcRes.result ?? replyText(npcRes);
            const npcs = statusRes.ok && npcRes.ok
                ? parseNpcs(npcText)
                : [];
            this.onPoll?.(status, npcs);
        } catch (e) {
            this.onPollError?.(e instanceof Error ? e.message : String(e));
        }
    }

    updateOptions(opts: SessionOptions): void {
        this.options = opts;
        if (this.client.isConnected) {
            this.startPolling();
        }
    }

    dispose(): void {
        this.disconnect();
        this.client.onStateChanged = null;
        this.client.onClosed = null;
    }

    private forwardState(s: SessionState, detail: string): void {
        if (s === 'connected') {
            this.startPolling();
        } else if (s === 'disconnected' || s === 'error') {
            this.stopPolling();
        }
        this.onStateChanged?.(s, detail);
    }

    private startPolling(): void {
        this.stopPolling();
        const secs = this.options?.pollIntervalSeconds ?? 2;
        this.timer = setInterval(() => void this.refresh(),
            Math.max(1, secs) * 1000);
    }

    private stopPolling(): void {
        if (this.timer) {
            clearInterval(this.timer);
            this.timer = null;
        }
    }

    private scheduleReconnect(): void {
        if (!this.options?.autoReconnect || this.pendingReconnect) {
            return;
        }
        this.pendingReconnect = true;
        this.reconnectTimer = setTimeout(() => {
            this.pendingReconnect = false;
            if (this.options && !this.client.isConnected &&
                this.client.state !== 'connecting') {
                this.connect(this.options);
            }
        }, 1500);
    }

    private clearReconnect(): void {
        this.pendingReconnect = false;
        if (this.reconnectTimer) {
            clearTimeout(this.reconnectTimer);
            this.reconnectTimer = null;
        }
    }
}
