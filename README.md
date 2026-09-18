# Lua Map Maker

A Minecraft **Fabric mod** that lets map makers build maps in **Lua**, plus a
**single-file launcher JAR** that downloads and starts the whole game for
quick testing — no Minecraft install, no launcher, no account required.

Drop `.lua` scripts into the `luamaps/` folder, run `/luamap run <name>`
in-game, and the script builds in the world. That's it.

Built for Wolfy ([@wolfybl](https://github.com/wolfybl)) to quick-test map
scripts.

## Quick start (for Wolfy)

You need **any Java 17+** just to run the launcher itself (`java -jar …`).
The launcher then manages its own Java 17 runtime for the game — see
[Managed Java runtime](#managed-java-runtime) below.

```bash
java -jar luamap-launcher.jar
```

Everything lands next to the launcher jar: `javahome/` (the managed JRE) and
`luamap-run/` (the game). First run downloads ~500 MB (JRE, vanilla client,
libraries, assets, Fabric loader); subsequent runs start instantly.

In the game:

1. `Singleplayer → Create New World` (creative + flat recommended)
2. `/luamap list` — shows the bundled example scripts
3. `/luamap run arena` — builds a walled arena around you
4. `/luamap run tower` — builds a lookout tower
5. `/luamap run parkour` — generates 30 segments of an infinite procedural
   parkour course ahead of you; re-run it as you progress to extend the
   course (jump chains, stairs, beams, slime/ice pads, checkpoints, lava
   hazard). Progress is tracked via an in-world marker so generation resumes
   where it left off.
6. `/luamap run ai_server_sim` — spawns 8 simulated players (visible in the
   tab list) that wander, run role tasks, chat, converse, and greet you; each
   run advances the simulation 40 deterministic steps.
7. `/luamap eval chat('hi from lua')` — evaluate a one-liner

Your own scripts go in `luamap-run/luamaps/`. The file name (minus `.lua`) is
the command name. Scripts that build "around you" should use
`player.pos()` as their origin — see `arena.lua` for the pattern.

### Headless mode

On a machine without a display, the same jar can run a dedicated server
(flat creative world, offline mode) — `/luamap` commands work from the
server console:

```bash
java -jar luamap-launcher.jar --server
```

Then type e.g. `luamap run arena` at the server console.

### Managed Java runtime

The launcher never launches Minecraft with your system Java. On every run it:

1. Resolves the directory containing `luamap-launcher.jar` (not the process
   working directory — double-clicking works the same as a terminal).
2. Looks for a Java executable in `javahome/` under that directory,
   accepting direct `bin/java`, nested `jdk-17.x/bin/java`, and macOS
   `Contents/Home/bin/java` layouts.
3. Runs `java -version` on what it finds. If it's missing or not major
   version 17, the directory is replaced with a freshly downloaded
   **Temurin (Adoptium) JRE 17** for the current platform.

Why: Fabric/ASM only understand class files up to the Minecraft target
version. Launching with a much newer JDK (e.g. Java 26, class version 70)
fails the mod loader — pinning a managed Java 17 makes this a non-issue.

Supported auto-download platforms (Temurin availability): **macOS aarch64
and x64, Linux x64 and aarch64, Windows x64** (Windows ARM64 is attempted
where Adoptium publishes a build). Downloads land in a staging dir, are
extracted (zip or tar.gz, with exec bits applied on POSIX), validated, then
moved into place — an interrupted run just cleans up and retries on the next
launch.

To use your own runtime instead: put any Java 17 JDK/JRE into
`javahome/` yourself and it will be picked up as-is.

### Launcher options

```
java -jar luamap-launcher.jar [options]

  --server          dedicated server instead of the client
  --gameDir DIR     run directory (default: <launcher dir>/luamap-run)
  --username NAME   offline-mode username (default: Wolfy)
  --xmx SIZE        game heap (default: 2G)
  --mc VERSION      override the bundled Minecraft version
  --bridgePort N    enable LuaBridge on 127.0.0.1:N for IDE live-eval/debug
                    (see docs/luabridge.md; the LuaMap IntelliJ plugin
                    connects here)
  --setup-ide       provision IntelliJ IDEA CE + LuaMap plugin under
                    .luamap/ide/, print the path, exit (no game launch)
  --ide             same, then launch the IDE
  --idePath DIR     use an existing IntelliJ install instead of the managed
                    one (plugin still installed)
```

`--setup-ide`/`--ide` never start the game — run the launcher again normally
for that. The managed IDE comes from the JetBrains releases API
(`data.services.jetbrains.com`, Community Edition `IIC`), is verified against
the published `.sha256`, extracted into `.luamap/ide/ideaIC-<version>/` with a
`.done` completion marker, and gets the LuaMap plugin (bundled inside the
launcher jar; falls back to the matching GitHub release asset) installed into
its `plugins/` directory. Start the game with `--bridgePort 25575`, open a
`.lua` script in the IDE, and use a "LuaMap Script" run configuration to
live-run it against the world.

## IDE integration (LuaBridge + IntelliJ plugin)

Two pieces make up the dev-tooling side of the repo:

- **LuaBridge** (`bridge/` module, embedded in the mod jar) — a localhost-only
  newline-delimited-JSON socket server exposing `eval`/`run`/`reload`/`list`/
  `status`. Enable it with `--bridgePort 25575` (or `port=25575` in
  `<gameDir>/luamap-bridge.properties`). Protocol: [docs/luabridge.md](docs/luabridge.md).
- **LuaMap Tools** (`ide-plugin/` — included Gradle build) — IntelliJ IDEA
  plugin with `.luamap` file type, API-word highlighting + completion for
  `world.*`/`player.*`/`npc.*`, a "LuaMap Script" run configuration that
  sends scripts to LuaBridge, a gutter run marker, and a block-preview tool
  window stub. Build: `./gradlew :ide-plugin:build` → install the zip from
  `ide-plugin/build/distributions/` via Settings → Plugins → Install from Disk.

The IDE plugin is a composite included build — `./gradlew build` never
configures it, so the main build stays fast and JVM-only; build it explicitly
when you want the IDE side.

## Lua API

Scripts run in a sandboxed Lua 5.2 environment (LuaJ). The API is a set of
global tables:

### `world` — block editing and world state

```lua
world.setblock(x, y, z, "block")                    -- one block
world.fill(x1,y1,z1, x2,y2,z2, "block")             -- cuboid, returns count
world.hollow(x1,y1,z1, x2,y2,z2, "block")           -- cuboid shell (walls)
world.getblock(x, y, z)                             -- -> "minecraft:stone"
world.spawn(x, y, z)                                -- set world spawn
world.time("day")                                   -- or "noon"|"night"|"midnight"|ticks
world.weather("clear")                              -- or "rain"|"thunder"
```

Block names accept a namespace (`"minecraft:stone"`), omit it for vanilla
(`"stone"`), and take block states in brackets
(`"minecraft:chest[facing=north]"`, `"minecraft:ladder[facing=south]"`).

`fill`/`hollow` are capped at **1,000,000 blocks per call** — split bigger
regions into several calls.

### `player` — the player who ran the command

```lua
if player.exists() then           -- false when run from server console
  local x, y, z = player.pos()
  player.teleport(x, y + 10, z)
  player.give("minecraft:diamond_sword", 1)
  chat("hi " .. player.name())
end
```

### `npc` — simulated players (AI server simulator)

NPCs are real fake-player entities (Fabric `FakePlayer`): they appear in the
tab list, occupy the world, and **persist across script runs** — use
`npc.list()` on re-entry to resume control of an existing population.

```lua
npc.spawn("Sim_Aria", x, y, z)       -- name: 3-16 chars [A-Za-z0-9_], unique
npc.exists("Sim_Aria")               -- -> boolean
npc.remove("Sim_Aria")               -- -> boolean
npc.removeAll()                      -- -> count removed
npc.list()                           -- -> {name={x=..,y=..,z=..}, ...}
npc.count()                          -- -> n
npc.pos("Sim_Aria")                  -- -> x, y, z
npc.moveto("Sim_Aria", x, y, z)      -- teleport-step move (1 block/step is the
                                    --   convention for "walking")
npc.look("Sim_Aria", yaw, pitch)     -- set facing
npc.say("Sim_Aria", "hello")         -- broadcasts "<Sim_Aria> hello"
```

Because scripts are synchronous, "living" NPCs are driven in steps — see
`luamaps/ai_server_sim.lua` for a full step-based simulator (roles, tasks,
wandering, chatter, NPC↔NPC and NPC↔player interactions). Movement is
teleport-step walking — no continuous physics or built-in pathfinding; scripts
implement walkability checks via `world.getblock`. `npc.say` is a server
broadcast, not signed player chat.

### Output

```lua
chat("message")   -- sends to the player / server console
log("message")    -- same thing (alias)
print(...)        -- also routed to chat
```

Return a value from a script (`return expr`) to have it echoed back as
`= <value>`.

### Example

```lua
-- luamaps/my_map.lua
local cx, cy, cz = 0, 64, 0
if player.exists() then
  cx, cy, cz = player.pos()
  cx, cy, cz = math.floor(cx), math.floor(cy), math.floor(cz)
end

world.fill(cx-10, cy-1, cz-10, cx+10, cy-1, cz+10, "minecraft:grass_block")
world.hollow(cx-10, cy, cz-10, cx+10, cy+4, cz+10, "minecraft:stone_bricks")
world.spawn(cx, cy, cz)
world.time("day"); world.weather("clear")
chat("map ready!")
```

More in [`src/main/resources/luamaps/`](src/main/resources/luamaps/) — these
are auto-copied into `<gameDir>/luamaps/` on first launch.

## Architecture

```
┌──────────────────────────── repo ────────────────────────────┐
│                                                              │
│  root project  = the Fabric mod (Fabric Loom / Gradle)       │
│  launcher/     = standalone Java launcher (fat jar)          │
│                                                              │
└──────────────────────────────────────────────────────────────┘
```

### Loader choice: Fabric

- **Fastest path to a working dev/test loop** — Fabric Loom handles
  deobfuscation, remapping (yarn), and dev runtime with almost no config.
- **Loader JAR is embeddable** — the mod jar carries LuaJ inside itself
  (Jar-in-Jar via `include`), so users need no extra dependencies.
- Lightweight, minimal overhead; easy port to NeoForge later if needed.

### Lua engine: LuaJ (`org.luaj:luaj-jse`)

- Pure-Java Lua 5.2 — no native libraries to ship or crash.
- Mature, widely used for embedded Minecraft scripting.
- Sandboxed in [`LuaRuntime`](src/main/java/org/communitypoke/luamap/lua/LuaRuntime.java):
  `io`, `luajava`, `package`/`require`, `dofile`/`loadfile`, and the dangerous
  `os.*` functions are removed — scripts can only touch the game through the
  `world`/`player` tables.

### Mod internals

| File | Role |
|---|---|
| `LuaMapMakerMod` | entrypoint; seeds bundled examples into `luamaps/` |
| `command/LuaMapCommand` | `/luamap list\|dir\|run\|eval` (Brigadier) |
| `ScriptLibrary` | script discovery, name validation, example seeding |
| `lua/LuaRuntime` | sandboxed LuaJ globals + `run()` |
| `lua/LuaContext` | world + invoking player + output sink |
| `lua/api/WorldApi` | `world.*` — setblock/fill/hollow/getblock/spawn/time/weather |
| `lua/api/PlayerApi` | `player.*` — exists/name/pos/teleport/give |
| `lua/api/NpcApi` + `npc/NpcManager` | `npc.*` — fake-player NPC registry, spawn/move/look/say |
| `bridge/LuaBridgeService` (mod) + `bridge/` module | LuaBridge localhost socket server (IDE integration) |
| `lua/BlockStates` | `"name[props]"` → `BlockState` parser |

Commands require permission level 2 (singleplayer cheats / server op).

### Launcher internals (`launcher/`)

A ~900-line Java program, no external framework beyond gson (merged into the
jar):

0. **Provisions Java 17** into `<launcher dir>/javahome` (Temurin download,
   extraction, exec bits, version check) — the game always runs on it.
1. Fetches the vanilla **version manifest** and version JSON from Mojang.
2. Downloads **libraries + natives + client jar + assets** (assets in a
   parallel pool, resumable — existing files are skipped).
3. Fetches the **Fabric loader profile** from `meta.fabricmc.net` for the
   pinned loader version.
4. Extracts the **bundled mod jar** (`bundled-mods/` inside the launcher jar)
   and downloads **Fabric API** into `mods/`.
5. Builds the classpath and launch arguments (offline `legacy` auth, random
   UUID) and starts `net.fabricmc.loader.impl.launch.knot.KnotClient`.

`--server` instead downloads the official Fabric server launcher jar,
writes `eula.txt` + a flat-creative `server.properties`, installs the same
mods, and runs it.

## Repository layout

```
├── build.gradle            # mod build (Fabric Loom)
├── settings.gradle         # includes :launcher, :bridge; includeBuild ide-plugin
├── gradle.properties       # pinned versions (MC, loader, Fabric API, LuaJ)
├── gradlew / gradlew.bat   # Gradle wrapper (8.10)
├── src/main/java/...       # mod source (+ bridge/LuaBridgeService, npc/)
├── src/main/resources/
│   ├── fabric.mod.json
│   └── luamaps/            # bundled example scripts (index.txt lists them)
├── src/test/java/...       # JUnit tests (sandbox, library, scripts, sim)
├── launcher/
│   ├── build.gradle        # fatJar → luamap-launcher.jar
│   └── src/main/java/...   # downloader / installer / bootstrap
├── bridge/                 # LuaBridge protocol + localhost socket server
│   └── src/...             # BridgeProtocol / BridgeServer / BridgeClient
├── ide-plugin/             # IntelliJ plugin (included build; own settings)
│   └── src/...             # file type, completion, run config, tool window
├── docs/luabridge.md       # bridge protocol spec
└── README.md
```

## Building from source

Requires **JDK 17** and internet access on first build.

```bash
./gradlew build
```

Outputs:

- `build/libs/luamap-0.1.0.jar` — the mod jar (LuaJ embedded)
- `launcher/build/libs/luamap-launcher.jar` — the runnable launcher
  (contains the mod jar)

Tests: `./gradlew test` — JUnit suite covering the Lua sandbox, script
library, and block-spec parsing.

Run a dev client straight from Gradle (uses a real Minecraft account or
offline, depending on Loom config):

```bash
./gradlew runClient
```

## Limitations

- **Minecraft 1.20.4 + Java 17+ only** for now (pinned in
  `gradle.properties`; bump `minecraft_version`/mappings to port).
- **Scripts run synchronously** on the server thread — an infinite loop
  (`while true do end`) will hang the game. Keep scripts finite.
- **Absolute coordinates** — `world.*` functions use world coordinates, not
  relative `~` notation. Use `player.pos()` as an anchor.
- **Java 17 game runtime only** — the managed `javahome/` is pinned to
  major version 17 (matching MC 1.20.4); other versions are rejected and
  re-provisioned.
- **Offline mode** — the launcher never logs into a Mojang account;
  usernames are whatever `--username` says, and multiplayer skin/session
  features don't apply. No Mojang terms are bypassed — offline mode is a
  vanilla feature; assets and game files are downloaded from Mojang's
  official servers.
- **1M block cap** per `fill`/`hollow` call.
- **No tick/event callbacks** — scripts run once and exit, so "generate
  ahead while playing" is emulated by re-running the script (`parkour.lua`
  resumes from a world marker). There is no per-player respawn or
  fall-detection hook, so parkour checkpoints are visual; `world.spawn`
  is the only respawn lever a script has.
- No undo/redo, no schematic import/export, no per-map packaging — yet.
- Lua is 5.2 semantics (LuaJ), not 5.3+/LuaJIT.

## License

MIT — see [LICENSE](LICENSE).
