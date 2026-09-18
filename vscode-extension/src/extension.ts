/** LuaMap Tools extension entry point — commands, status bar, sidebar wiring. */
import * as vscode from 'vscode';
import { registerLuaCompletion } from './completion';
import { NpcTree, ScriptTree, SessionTree } from './sidebar';
import { LuaBridgeSession, SessionOptions, replyText } from './session';

function readOptions(): SessionOptions {
    const cfg = vscode.workspace.getConfiguration('luamap');
    return {
        host: cfg.get<string>('bridge.host', '127.0.0.1'),
        port: cfg.get<number>('bridge.port', 25575),
        autoReconnect: cfg.get<boolean>('autoReconnect', true),
        pollIntervalSeconds:
            cfg.get<number>('pollIntervalSeconds', 2),
    };
}

export function activate(context: vscode.ExtensionContext): void {
    const session = new LuaBridgeSession();
    context.subscriptions.push({ dispose: () => session.dispose() });

    const consoleOut = vscode.window.createOutputChannel('LuaMap');
    context.subscriptions.push(consoleOut);
    const log = (line: string) => consoleOut.appendLine(line);

    const statusBar = vscode.window.createStatusBarItem(
        vscode.StatusBarAlignment.Left, 10);
    statusBar.command = 'luamap.connect';
    context.subscriptions.push(statusBar);
    statusBar.show();

    const sessionTree = new SessionTree(session);
    const npcTree = new NpcTree(session);
    const scriptTree = new ScriptTree();
    context.subscriptions.push(
        vscode.window.createTreeView('luamap.session',
            { treeDataProvider: sessionTree }),
        vscode.window.createTreeView('luamap.npcs',
            { treeDataProvider: npcTree }),
        vscode.window.createTreeView('luamap.scripts',
            { treeDataProvider: scriptTree }));

    const setBadge = (s: string, detail: string) => {
        const icon = s === 'connected' ? '$(plug)' : s === 'connecting'
            ? '$(sync~spin)' : s === 'error' ? '$(error)' : '$(circle-slash)';
        statusBar.text = `${icon} LuaMap: ${s}`;
        statusBar.tooltip = `LuaBridge ${s} — ${detail}`;
        sessionTree.setDetail(detail);
    };
    setBadge('disconnected', 'not connected');

    session.onStateChanged = (s, detail) => setBadge(s, detail);
    session.onPoll = (status, npcs) => {
        sessionTree.setDetail(
            `${status.world} · ${status.scripts} scripts · ${status.npcs} npcs`);
        npcTree.update(npcs);
    };
    session.onPollError = (m) => log(`poll failed: ${m}`);

    const requireConnected = (): boolean => {
        if (!session.client.isConnected) {
            vscode.window.showWarningMessage(
                'LuaMap: not connected — run "LuaMap: Connect" first.');
            return false;
        }
        return true;
    };

    context.subscriptions.push(
        vscode.commands.registerCommand('luamap.connect', async () => {
            const opts = readOptions();
            const host = await vscode.window.showInputBox({
                prompt: 'LuaBridge host',
                value: opts.host,
            });
            if (host === undefined) {
                return;
            }
            const portStr = await vscode.window.showInputBox({
                prompt: 'LuaBridge port',
                value: String(opts.port),
            });
            if (portStr === undefined) {
                return;
            }
            const port = parseInt(portStr, 10);
            if (!Number.isFinite(port) || port <= 0 || port > 65535) {
                vscode.window.showErrorMessage(
                    `LuaMap: invalid port "${portStr}"`);
                return;
            }
            session.connect({ ...opts, host, port });
        }),

        vscode.commands.registerCommand('luamap.disconnect', () =>
            session.disconnect()),

        vscode.commands.registerCommand('luamap.reconnect', () =>
            session.reconnect()),

        vscode.commands.registerCommand('luamap.refreshNpcs', async () => {
            if (!requireConnected()) {
                return;
            }
            await session.refresh();
        }),

        vscode.commands.registerCommand('luamap.runScript',
            async (arg?: unknown) => {
                if (!requireConnected()) {
                    return;
                }
                let name = typeof arg === 'string' ? arg : undefined;
                try {
                    const listRes = await session.client.list();
                    if (listRes.ok && listRes.result) {
                        scriptTree.update(
                            listRes.result.split('\n')
                                .map((s) => s.trim()).filter(Boolean));
                    }
                } catch { /* listing is best-effort */ }
                if (!name) {
                    name = await vscode.window.showInputBox({
                        prompt: 'Script name (luamaps/<name>.lua)',
                        placeHolder: 'parkour',
                    });
                }
                if (!name) {
                    return;
                }
                log(`> run ${name}`);
                try {
                    log(replyText(await session.client.run(name)));
                } catch (e) {
                    log(`run failed: ${e}`);
                }
            }),

        vscode.commands.registerCommand('luamap.evaluate', async () => {
            if (!requireConnected()) {
                return;
            }
            const editor = vscode.window.activeTextEditor;
            const sel = editor?.document.getText(editor.selection);
            const code = await vscode.window.showInputBox({
                prompt: 'Lua code to evaluate on the server',
                value: sel && sel.length < 200 ? sel : '',
            });
            if (!code) {
                return;
            }
            log(`> ${code}`);
            try {
                log(replyText(await session.client.eval(code)));
            } catch (e) {
                log(`eval failed: ${e}`);
            }
        }),

        vscode.commands.registerCommand('luamap.queryBlock', async () => {
            if (!requireConnected()) {
                return;
            }
            const xyz = await vscode.window.showInputBox({
                prompt: 'Block coordinates: "x y z"',
                placeHolder: '0 64 0',
            });
            if (!xyz) {
                return;
            }
            const parts = xyz.trim().split(/\s+/).map(Number);
            if (parts.length !== 3 || parts.some((n) => !Number.isFinite(n))) {
                vscode.window.showErrorMessage(
                    `LuaMap: expected three numbers, got "${xyz}"`);
                return;
            }
            const [x, y, z] = parts;
            const code = `return world.getblock(${x}, ${y}, ${z})`;
            log(`> ${code}`);
            try {
                log(replyText(await session.client.eval(code)));
            } catch (e) {
                log(`query failed: ${e}`);
            }
        }),
    );

    registerLuaCompletion(context);

    context.subscriptions.push(
        vscode.workspace.onDidChangeConfiguration((e) => {
            if (e.affectsConfiguration('luamap')) {
                session.updateOptions(readOptions());
            }
        }));
}

export function deactivate(): void {
    // session.dispose() via subscriptions.
}
