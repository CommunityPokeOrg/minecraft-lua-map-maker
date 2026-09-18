/** Lua completion + snippets for the LuaMap scripting API (world.*, npc.*, player.*). */
import * as vscode from 'vscode';

type ApiMember = {
    name: string;
    kind: vscode.CompletionItemKind;
    detail: string;
    insert?: vscode.SnippetString;
};

const WORLD: ApiMember[] = [
    {
        name: 'setblock',
        kind: vscode.CompletionItemKind.Method,
        detail: 'world.setblock(x, y, z, "block_id")',
        insert: new vscode.SnippetString(
            'setblock(${1:x}, ${2:y}, ${3:z}, "${4:minecraft:stone}")'),
    },
    {
        name: 'fill',
        kind: vscode.CompletionItemKind.Method,
        detail: 'world.fill(x1, y1, z1, x2, y2, z2, "block_id") — ≤ 1M blocks',
        insert: new vscode.SnippetString(
            'fill(${1:x1}, ${2:y1}, ${3:z1}, ${4:x2}, ${5:y2}, ${6:z2}, "${7:minecraft:stone}")'),
    },
    {
        name: 'hollow',
        kind: vscode.CompletionItemKind.Method,
        detail: 'world.hollow(x1..z2, "block_id") — hollow box',
        insert: new vscode.SnippetString(
            'hollow(${1:x1}, ${2:y1}, ${3:z1}, ${4:x2}, ${5:y2}, ${6:z2}, "${7:minecraft:stone_bricks}")'),
    },
    {
        name: 'getblock',
        kind: vscode.CompletionItemKind.Method,
        detail: 'world.getblock(x, y, z) → block id string',
        insert: new vscode.SnippetString('getblock(${1:x}, ${2:y}, ${3:z})'),
    },
    {
        name: 'spawn',
        kind: vscode.CompletionItemKind.Method,
        detail: 'world.spawn("entity_id", x, y, z)',
        insert: new vscode.SnippetString(
            'spawn("${1:minecraft:zombie}", ${2:x}, ${3:y}, ${4:z})'),
    },
    {
        name: 'time',
        kind: vscode.CompletionItemKind.Method,
        detail: 'world.time(t) — set world time (ticks)',
        insert: new vscode.SnippetString('time(${1:6000})'),
    },
    {
        name: 'weather',
        kind: vscode.CompletionItemKind.Method,
        detail: 'world.weather("clear"|"rain"|"thunder")',
        insert: new vscode.SnippetString('weather("${1:clear}")'),
    },
];

const NPC: ApiMember[] = [
    {
        name: 'spawn',
        kind: vscode.CompletionItemKind.Method,
        detail: 'npc.spawn("Name", x, y, z) → bool',
        insert: new vscode.SnippetString(
            'spawn("${1:Guard}", ${2:x}, ${3:y}, ${4:z})'),
    },
    {
        name: 'exists',
        kind: vscode.CompletionItemKind.Method,
        detail: 'npc.exists("Name") → bool',
    },
    {
        name: 'remove',
        kind: vscode.CompletionItemKind.Method,
        detail: 'npc.remove("Name") → bool',
    },
    {
        name: 'removeAll',
        kind: vscode.CompletionItemKind.Method,
        detail: 'npc.removeAll() → count removed',
    },
    {
        name: 'list',
        kind: vscode.CompletionItemKind.Method,
        detail: 'npc.list() → {name={x,y,z}, …}',
    },
    {
        name: 'count',
        kind: vscode.CompletionItemKind.Method,
        detail: 'npc.count() → int',
    },
    {
        name: 'pos',
        kind: vscode.CompletionItemKind.Method,
        detail: 'npc.pos("Name") → x, y, z',
    },
    {
        name: 'moveto',
        kind: vscode.CompletionItemKind.Method,
        detail: 'npc.moveto("Name", x, y, z) → bool',
        insert: new vscode.SnippetString(
            'moveto("${1:Name}", ${2:x}, ${3:y}, ${4:z})'),
    },
    {
        name: 'look',
        kind: vscode.CompletionItemKind.Method,
        detail: 'npc.look("Name", x, y, z) — face a point',
        insert: new vscode.SnippetString(
            'look("${1:Name}", ${2:x}, ${3:y}, ${4:z})'),
    },
    {
        name: 'say',
        kind: vscode.CompletionItemKind.Method,
        detail: 'npc.say("Name", "message") — public chat',
        insert: new vscode.SnippetString('say("${1:Name}", "${2:Hello!}")'),
    },
];

const PLAYER: ApiMember[] = [
    {
        name: 'exists',
        kind: vscode.CompletionItemKind.Method,
        detail: 'player.exists("name") → bool',
    },
    {
        name: 'name',
        kind: vscode.CompletionItemKind.Method,
        detail: 'player.name() → first player name',
    },
    {
        name: 'pos',
        kind: vscode.CompletionItemKind.Method,
        detail: 'player.pos("name"?) → x, y, z',
    },
    {
        name: 'teleport',
        kind: vscode.CompletionItemKind.Method,
        detail: 'player.teleport("name"?, x, y, z)',
        insert: new vscode.SnippetString(
            'teleport(${1:name}, ${2:x}, ${3:y}, ${4:z})'),
    },
    {
        name: 'give',
        kind: vscode.CompletionItemKind.Method,
        detail: 'player.give("name"?, "item_id", count?)',
        insert: new vscode.SnippetString(
            'give(${1:name}, "${2:minecraft:diamond}", ${3:1})'),
    },
];

const MODULES: Record<string, ApiMember[]> = {
    world: WORLD,
    npc: NPC,
    player: PLAYER,
};

const TOP_LEVEL: ApiMember[] = [
    { name: 'world', kind: vscode.CompletionItemKind.Module, detail: 'LuaMap world API' },
    { name: 'npc', kind: vscode.CompletionItemKind.Module, detail: 'LuaMap NPC API' },
    { name: 'player', kind: vscode.CompletionItemKind.Module, detail: 'LuaMap player API' },
    {
        name: 'chat',
        kind: vscode.CompletionItemKind.Function,
        detail: 'chat("msg") — send a chat message',
        insert: new vscode.SnippetString('chat("${1:message}")'),
    },
    {
        name: 'log',
        kind: vscode.CompletionItemKind.Function,
        detail: 'log("msg") — server log line',
    },
    {
        name: 'print',
        kind: vscode.CompletionItemKind.Function,
        detail: 'print(...) — captured to script output',
    },
];

function toItems(members: ApiMember[]): vscode.CompletionItem[] {
    return members.map((m) => {
        const item = new vscode.CompletionItem(m.name, m.kind);
        item.detail = m.detail;
        if (m.insert) {
            item.insertText = m.insert;
        }
        return item;
    });
}

/**
 * Registers a '.'-triggered provider for `world.`/`npc.`/`player.` members
 * plus top-level module/function completion for lua documents.
 */
export function registerLuaCompletion(context: vscode.ExtensionContext): void {
    const provider = vscode.languages.registerCompletionItemProvider(
        'lua',
        {
            provideCompletionItems(doc, pos) {
                const before = doc.lineAt(pos).text.slice(0, pos.character);
                const m = /(world|npc|player)\.\w*$/.exec(before);
                if (m) {
                    return toItems(MODULES[m[1]]);
                }
                return toItems(TOP_LEVEL);
            },
        },
        '.',
    );
    context.subscriptions.push(provider);
}
