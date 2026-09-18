/**
 * LuaBridge wire protocol — newline-delimited JSON over a loopback TCP socket
 * (protocol v1; see docs/luabridge.md at the repo root). Pure TypeScript over
 * node:net — no `vscode` imports so the whole module is unit-testable on a
 * plain Node runtime.
 */
import * as net from 'net';

export const PROTOCOL_VERSION = 1;
export const DEFAULT_PORT = 25575;

export interface BridgeRequest {
    v: number;
    id: number;
    op: string;
    code?: string;
    name?: string;
}

export interface BridgeResponse {
    v?: number;
    id?: number;
    ok: boolean;
    result?: string;
    output?: string;
    error?: string;
}

/** Serialize a request to one NDJSON line. */
export function encodeRequest(req: BridgeRequest): string {
    return JSON.stringify(req) + '\n';
}

/**
 * Incremental NDJSON decoder: buffers arbitrary chunks and yields one object
 * per complete line. Throws on malformed JSON (callers treat it as fatal to
 * the connection — the protocol is request/response anyway).
 */
export class LineFramer {
    private buf = '';

    /** Append a chunk; returns every complete decoded object. */
    push(chunk: string | Buffer): BridgeResponse[] {
        this.buf += chunk.toString('utf8');
        const out: BridgeResponse[] = [];
        let idx: number;
        while ((idx = this.buf.indexOf('\n')) >= 0) {
            const line = this.buf.slice(0, idx);
            this.buf = this.buf.slice(idx + 1);
            if (line.trim().length > 0) {
                out.push(JSON.parse(line) as BridgeResponse);
            }
        }
        return out;
    }

    /** Bytes buffered but not yet newline-terminated. */
    get pending(): number {
        return this.buf.length;
    }
}

export type ConnectionState =
    | 'disconnected'
    | 'connecting'
    | 'connected'
    | 'error';

type Pending = {
    resolve: (r: BridgeResponse) => void;
    reject: (e: Error) => void;
};

/**
 * Async request/response bridge client. Concurrent calls are pipelined and
 * matched to replies by `id`. All events come from socket callbacks — never
 * blocks a UI thread by construction.
 */
export class BridgeClient {
    private socket: net.Socket | null = null;
    private framer = new LineFramer();
    private pending = new Map<number, Pending>();
    private nextId = 0;
    private _state: ConnectionState = 'disconnected';

    /** Fired on every state transition. */
    onStateChanged: ((state: ConnectionState, detail: string) => void) | null =
        null;
    /** Fired when the socket closes or errors (state already 'disconnected'). */
    onClosed: ((reason: string) => void) | null = null;

    get state(): ConnectionState {
        return this._state;
    }

    get isConnected(): boolean {
        return this._state === 'connected';
    }

    private setState(s: ConnectionState, detail: string): void {
        this._state = s;
        this.onStateChanged?.(s, detail);
    }

    /** Connect to host:port. Resolves once the socket is established. */
    connect(host: string, port: number, timeoutMs = 5000): Promise<void> {
        this.disposeSocket();
        this.setState('connecting', `connecting to ${host}:${port}`);
        return new Promise((resolve, reject) => {
            const s = net.createConnection({ host, port });
            this.socket = s;
            s.setTimeout(timeoutMs);
            s.setNoDelay(true);

            const onConnect = () => {
                cleanup();
                s.setTimeout(60_000); // request read timeout
                this.setState('connected', `${host}:${port}`);
                resolve();
            };
            const onErr = (e: Error) => {
                cleanup();
                this.setState('error', e.message);
                reject(e);
            };
            const onTimeout = () => onErr(new Error('connect timed out'));
            const cleanup = () => {
                s.off('connect', onConnect);
                s.off('timeout', onTimeout);
                s.off('error', onErr);
            };
            s.once('connect', onConnect);
            s.once('timeout', onTimeout);
            s.once('error', onErr);

            s.on('data', (d) => this.onData(d));
            s.on('close', (hadError) => this.onClose(hadError));
            s.on('error', () => {
                /* handled at connect time; post-connect errors surface via close */
            });
        });
    }

    /** Send one request; resolves with the matching response. */
    call(op: string, code?: string, name?: string): Promise<BridgeResponse> {
        const s = this.socket;
        if (this._state !== 'connected' || s === null) {
            return Promise.reject(new Error('not connected'));
        }
        const id = ++this.nextId;
        const req: BridgeRequest = { v: PROTOCOL_VERSION, id, op };
        if (code !== undefined) {
            req.code = code;
        }
        if (name !== undefined) {
            req.name = name;
        }
        return new Promise((resolve, reject) => {
            this.pending.set(id, { resolve, reject });
            s.write(encodeRequest(req), (err) => {
                if (err) {
                    this.pending.delete(id);
                    reject(err);
                }
            });
        });
    }

    eval(code: string): Promise<BridgeResponse> {
        return this.call('eval', code);
    }

    run(name: string): Promise<BridgeResponse> {
        return this.call('run', undefined, name);
    }

    status(): Promise<BridgeResponse> {
        return this.call('status');
    }

    list(): Promise<BridgeResponse> {
        return this.call('list');
    }

    reload(name: string): Promise<BridgeResponse> {
        return this.call('reload', undefined, name);
    }

    /** User-initiated disconnect: closes the socket without an error state. */
    disconnect(): void {
        this.disposeSocket();
        this.setState('disconnected', 'disconnected');
    }

    private onData(chunk: Buffer): void {
        let responses: BridgeResponse[];
        try {
            responses = this.framer.push(chunk);
        } catch (e) {
            this.failAll(new Error('malformed bridge reply: ' + String(e)));
            this.disposeSocket();
            this.setState('error', 'malformed reply');
            return;
        }
        for (const r of responses) {
            const id = typeof r.id === 'number' ? r.id : -1;
            const p = this.pending.get(id);
            if (p) {
                this.pending.delete(id);
                p.resolve(r);
            }
        }
    }

    private onClose(hadError: boolean): void {
        const wasConnected = this._state === 'connected';
        this.failAll(new Error('connection closed'));
        this.socket = null;
        this.setState(hadError || wasConnected ? 'error' : 'disconnected',
            'connection closed');
        this.onClosed?.('closed');
    }

    private failAll(e: Error): void {
        for (const p of this.pending.values()) {
            p.reject(e);
        }
        this.pending.clear();
    }

    private disposeSocket(): void {
        const s = this.socket;
        this.socket = null;
        if (s) {
            s.removeAllListeners();
            s.destroy();
        }
    }
}
