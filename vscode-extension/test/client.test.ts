/** BridgeClient tests against a mock loopback NDJSON server. */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import * as net from 'net';
import { BridgeClient, BridgeRequest, BridgeResponse }
    from '../src/luabridge';
import { parseNpcs, parseStatus, replyText } from '../src/session';

type Responder = (req: BridgeRequest) => BridgeResponse;

/** Minimal NDJSON bridge server on an ephemeral loopback port. */
class MockBridge {
    readonly server: net.Server;
    private clients = new Set<net.Socket>();
    private bufs = new Map<net.Socket, string>();
    responder: Responder = (req) => {
        if (req.op === 'status') {
            return { id: req.id, ok: true,
                output: 'ok; scripts=3; npcs=2; world=minecraft:overworld' };
        }
        if (req.op === 'list') {
            return { id: req.id, ok: true,
                result: 'hello\narena\nparkour' };
        }
        if (req.op === 'eval' && req.code?.includes('npc.list()')) {
            return { id: req.id, ok: true,
                result: 'Steve,10.00,64.00,-5.00\nAlex,-3.50,70.25,8.00' };
        }
        if (req.op === 'eval' && req.code?.includes('world.getblock')) {
            return { id: req.id, ok: true, result: 'minecraft:stone' };
        }
        if (req.op === 'eval' && req.code?.includes('bad')) {
            return { id: req.id, ok: false, error: 'boom' };
        }
        return { id: req.id, ok: true, result: 'ok' };
    };
    requests: BridgeRequest[] = [];

    constructor() {
        this.server = net.createServer((s) => this.onSocket(s));
        this.server.unref();
    }

    private onSocket(s: net.Socket): void {
        s.unref();
        this.clients.add(s);
        this.bufs.set(s, '');
        s.on('data', (d) => {
            let buf = (this.bufs.get(s) ?? '') + d.toString('utf8');
            let idx: number;
            while ((idx = buf.indexOf('\n')) >= 0) {
                const line = buf.slice(0, idx);
                buf = buf.slice(idx + 1);
                if (!line.trim()) {
                    continue;
                }
                const req = JSON.parse(line) as BridgeRequest;
                this.requests.push(req);
                s.write(JSON.stringify(this.responder(req)) + '\n');
            }
            this.bufs.set(s, buf);
        });
        s.on('close', () => {
            this.clients.delete(s);
            this.bufs.delete(s);
        });
    }

    listen(): Promise<number> {
        return new Promise((resolve) => {
            this.server.listen(0, '127.0.0.1', () => {
                const a = this.server.address();
                resolve(typeof a === 'object' && a ? a.port : 0);
            });
        });
    }

    dropClients(): void {
        for (const s of this.clients) {
            s.destroy();
        }
    }

    close(): Promise<void> {
        this.dropClients();
        return new Promise((r) => this.server.close(() => r()));
    }
}

function once<T>(fn: (cb: (v: T) => void) => void): Promise<T> {
    return new Promise((resolve) => fn(resolve));
}

function waitFor(pred: () => boolean, ms = 3000): Promise<void> {
    return new Promise((resolve, reject) => {
        const t0 = Date.now();
        const tick = () => {
            if (pred()) {
                resolve();
            } else if (Date.now() - t0 > ms) {
                reject(new Error('waitFor timed out'));
            } else {
                setTimeout(tick, 10);
            }
        };
        tick();
    });
}

test('connect → status reply parses into StatusInfo', async () => {
    const srv = new MockBridge();
    const port = await srv.listen();
    const c = new BridgeClient();
    await c.connect('127.0.0.1', port);
    assert.equal(c.state, 'connected');
    const res = await c.status();
    assert.equal(res.ok, true);
    const info = parseStatus(replyText(res));
    assert.equal(info.scripts, 3);
    assert.equal(info.npcs, 2);
    assert.equal(info.world, 'minecraft:overworld');
    c.disconnect();
    assert.equal(c.state, 'disconnected');
    await srv.close();
});

test('requests are sent as ndjson protocol v1', async () => {
    const srv = new MockBridge();
    const port = await srv.listen();
    const c = new BridgeClient();
    await c.connect('127.0.0.1', port);
    await c.eval('return world.getblock(0, 64, 0)');
    assert.equal(srv.requests.length, 1);
    const req = srv.requests[0];
    assert.equal(req.v, 1);
    assert.equal(req.id, 1);
    assert.equal(req.op, 'eval');
    assert.match(req.code ?? '', /world\.getblock/);
    c.disconnect();
    await srv.close();
});

test('concurrent calls match replies by id', async () => {
    const srv = new MockBridge();
    const port = await srv.listen();
    const c = new BridgeClient();
    await c.connect('127.0.0.1', port);
    const [r1, r2] = await Promise.all([c.status(), c.list()]);
    assert.equal(r1.id, 1);
    assert.match(r1.output ?? '', /scripts=3/);
    assert.equal(r2.id, 2);
    assert.equal(r2.result, 'hello\narena\nparkour');
    c.disconnect();
    await srv.close();
});

test('npc.list() eval result parses into NpcInfo', async () => {
    const srv = new MockBridge();
    const port = await srv.listen();
    const c = new BridgeClient();
    await c.connect('127.0.0.1', port);
    const res = await c.eval(
        'for n,p in pairs(npc.list()) do end return ""');
    const npcs = parseNpcs(res.result ?? replyText(res));
    assert.equal(npcs.length, 2);
    assert.equal(npcs[0].name, 'Steve');
    assert.equal(npcs[0].x, 10);
    assert.equal(npcs[1].name, 'Alex');
    assert.equal(npcs[1].z, 8);
    c.disconnect();
    await srv.close();
});

test('error reply surfaces error text', async () => {
    const srv = new MockBridge();
    const port = await srv.listen();
    const c = new BridgeClient();
    await c.connect('127.0.0.1', port);
    const res = await c.eval('this is bad lua');
    assert.equal(res.ok, false);
    assert.equal(replyText(res), 'error: boom');
    c.disconnect();
    await srv.close();
});

test('connect to dead port ends in error state', async () => {
    const c = new BridgeClient();
    const states: string[] = [];
    c.onStateChanged = (s) => states.push(s);
    await assert.rejects(c.connect('127.0.0.1', 1, 1000));
    assert.equal(c.state, 'error');
    assert.deepEqual(states, ['connecting', 'error']);
});

test('server drop fails pending calls and fires close', async () => {
    const srv = new MockBridge();
    const port = await srv.listen();
    const c = new BridgeClient();
    await c.connect('127.0.0.1', port);
    const closed = once<string>((cb) => {
        c.onClosed = cb;
    });
    srv.dropClients();
    await closed;
    assert.equal(c.state, 'error');
    await assert.rejects(c.status(), /not connected|closed/);
    await srv.close();
});

test('poll + reconnect flow via LuaBridgeSession', async () => {
    const srv = new MockBridge();
    const port = await srv.listen();
    const { LuaBridgeSession } = await import('../src/session');
    const session = new LuaBridgeSession();
    const states: string[] = [];
    session.onStateChanged = (s) => states.push(s);
    const polled = once<void>((cb) => {
        session.onPoll = (status, npcs) => {
            assert.equal(status.scripts, 3);
            assert.equal(npcs.length, 2);
            cb();
        };
    });
    session.connect({
        host: '127.0.0.1', port, autoReconnect: true,
        pollIntervalSeconds: 60, // manual refresh; poll event above
    });
    await waitFor(() => session.state === 'connected');
    await polled;
    // Drop → state flips, auto-reconnect fires within ~1.5 s.
    srv.dropClients();
    await waitFor(() => session.state === 'error');
    await waitFor(() => session.state === 'connected', 5000);
    session.dispose();
    await srv.close();
});
