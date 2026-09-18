# LuaMap Tools — IntelliJ IDEA plugin

IDE companion for Lua Map Maker map scripts. Lives in this repo as a Gradle
**included build** (composite) so it shares nothing with the mod build except
the protocol documented in `../docs/luabridge.md`.

## What it does (current: scaffold)

- `.luamap` script file type + API-word highlighting (`world.*`, `player.*`,
  `npc.*`, `chat`, `log`, `print`) — annotator also applies to plain `.lua`.
- Completion contributor offering the whole LuaMap/NPC API surface.
- "LuaMap Script" run configuration (host/port/script) that sends the script
  to LuaBridge (`run` op) and prints script output in the Run console.
- Gutter run marker on `.luamap` files (wires into the run configuration).
- "LuaMap" tool window stub for the block-preview panel (to be driven by
  `world.getblock`/`world.fill` queries over LuaBridge).
- `LuaBridgeClient` — synchronous newline-delimited-JSON socket client for the
  bridge protocol.

## Build

```bash
# from repo root (composite build)
./gradlew :ide-plugin:build
# or standalone
cd ide-plugin && ../gradlew build       # or gradle wrapper of your choice
```

Produces `build/distributions/luamap-idea-plugin-<version>.zip` — install via
IDEA → Settings → Plugins → ⚙ → Install Plugin from Disk. The IDE dependency
(~1.5 GB) downloads on first build; platform pins live in `gradle.properties`.

Compatibility: `since-build="241"` (IDEA 2024.1+) with **no `until-build`** —
the plugin loads on every newer IDE, including 2025.3 (build 253.*) as
provisioned by `--setup-ide`. The build compiles against the unified IDEA
distribution (`ideaIU`; JetBrains stopped publishing a separate IC archive
starting with 2025.3) using a JDK 21 toolchain auto-provisioned by
foojay-resolver, while emitting Java 17 bytecode that also runs on JBR 17
IDEs. `verifyPluginXml` (wired into `check`) fails the build if the packaged
plugin.xml ever loses that range.

## Try it

```bash
# repo root
cd ide-plugin && ../gradlew runIde   # sandbox IDEA with the plugin loaded
# in another shell — game with the bridge on:
java -jar launcher/build/libs/luamap-launcher.jar --bridgePort 25575
```

Then open a `.luamap` file (or a `.lua` script under `luamap-run/luamaps/`),
use the gutter/run button or a "LuaMap Script" run config pointed at
`localhost:25575`.

## Not yet implemented (stubs/TODO)

- True debugger UI (breakpoints/step-through) — protocol has the `eval`/`run`
  shape to hang a debug session on, but no frame model exists in the mod.
- Block preview rendering (tool window is a placeholder panel).
- Error squiggles mapping LuaError line numbers back into the editor.
