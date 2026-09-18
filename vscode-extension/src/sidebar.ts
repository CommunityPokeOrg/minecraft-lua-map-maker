/** Activity-bar sidebar: session status, NPC inspector, script list. */
import * as vscode from 'vscode';
import { LuaBridgeSession, NpcInfo, StatusInfo } from './session';

class SimpleItem extends vscode.TreeItem {
    constructor(label: string,
        collapsible = vscode.TreeItemCollapsibleState.None) {
        super(label, collapsible);
    }
}

class ActionItem extends SimpleItem {
    constructor(label: string, command: string, icon: string) {
        super(label);
        this.command = { command, title: label };
        this.iconPath = new vscode.ThemeIcon(icon);
    }
}

/** luamap.session — state + connection actions. */
export class SessionTree implements vscode.TreeDataProvider<vscode.TreeItem> {
    private emitter = new vscode.EventEmitter<void>();
    readonly onDidChangeTreeData = this.emitter.event;

    private detail = 'not connected';

    constructor(private session: LuaBridgeSession) {}

    setDetail(d: string): void {
        this.detail = d;
        this.emitter.fire();
    }

    refresh(): void {
        this.emitter.fire();
    }

    getTreeItem(el: vscode.TreeItem): vscode.TreeItem {
        return el;
    }

    getChildren(): vscode.TreeItem[] {
        const state = this.session.state;
        const stateItem = new SimpleItem(`State: ${state}`);
        stateItem.description = this.detail;
        stateItem.iconPath = new vscode.ThemeIcon(
            state === 'connected' ? 'debug-stackframe-dot'
            : state === 'connecting' ? 'loading~spin'
            : state === 'error' ? 'error' : 'circle-outline');
        const items: vscode.TreeItem[] = [stateItem];
        if (state === 'connected' || state === 'connecting' ||
            state === 'error') {
            items.push(new ActionItem('Disconnect', 'luamap.disconnect',
                'debug-disconnect'));
            items.push(new ActionItem('Reconnect', 'luamap.reconnect',
                'refresh'));
        } else {
            items.push(new ActionItem('Connect', 'luamap.connect', 'plug'));
        }
        items.push(new ActionItem('Run Script…', 'luamap.runScript', 'play'));
        items.push(new ActionItem('Evaluate Lua…', 'luamap.evaluate',
            'symbol-method'));
        items.push(new ActionItem('Query Block…', 'luamap.queryBlock',
            'symbol-misc'));
        return items;
    }
}

/** luamap.npcs — live NPC inspector (name + xyz), refreshes on poll. */
export class NpcTree implements vscode.TreeDataProvider<vscode.TreeItem> {
    private emitter = new vscode.EventEmitter<void>();
    readonly onDidChangeTreeData = this.emitter.event;
    private npcs: NpcInfo[] = [];

    constructor(private session: LuaBridgeSession) {}

    update(npcs: NpcInfo[]): void {
        this.npcs = npcs;
        this.emitter.fire();
    }

    getTreeItem(el: vscode.TreeItem): vscode.TreeItem {
        return el;
    }

    getChildren(): vscode.TreeItem[] {
        if (this.session.state !== 'connected') {
            const item = new SimpleItem('Connect to list NPCs');
            item.iconPath = new vscode.ThemeIcon('info');
            return [item];
        }
        if (this.npcs.length === 0) {
            const item = new SimpleItem('No NPCs');
            item.iconPath = new vscode.ThemeIcon('info');
            return [item, new ActionItem('Refresh', 'luamap.refreshNpcs',
                'refresh')];
        }
        const rows = this.npcs.map((n) => {
            const item = new SimpleItem(n.name);
            item.description = `${n.x.toFixed(1)}, ${n.y.toFixed(1)}, ${n.z.toFixed(1)}`;
            item.iconPath = new vscode.ThemeIcon('person');
            item.tooltip = `${n.name} @ ${n.x}, ${n.y}, ${n.z}`;
            return item as vscode.TreeItem;
        });
        rows.push(new ActionItem('Refresh', 'luamap.refreshNpcs', 'refresh'));
        return rows;
    }
}

/** luamap.scripts — `list` op results + run-on-click. */
export class ScriptTree implements vscode.TreeDataProvider<vscode.TreeItem> {
    private emitter = new vscode.EventEmitter<void>();
    readonly onDidChangeTreeData = this.emitter.event;
    private scripts: string[] = [];

    update(scripts: string[]): void {
        this.scripts = scripts;
        this.emitter.fire();
    }

    getTreeItem(el: vscode.TreeItem): vscode.TreeItem {
        return el;
    }

    getChildren(): vscode.TreeItem[] {
        if (this.scripts.length === 0) {
            const item = new SimpleItem('No scripts listed');
            item.iconPath = new vscode.ThemeIcon('info');
            return [item];
        }
        return this.scripts.map((s) => {
            const item = new SimpleItem(s);
            item.iconPath = new vscode.ThemeIcon('file-code');
            item.contextValue = 'luamapScript';
            item.command = {
                command: 'luamap.runScript',
                title: 'Run Script',
                arguments: [s],
            };
            return item;
        });
    }
}
