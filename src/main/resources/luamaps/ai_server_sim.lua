-- ============================================================================
-- ai_server_sim.lua — AI server-activity simulator
-- ============================================================================
--
-- Run it with:   /luamap run ai_server_sim
--
-- Spawns a roster of simulated players (real fake-player entities — they show
-- up in the tab list) and advances a deterministic "server life" simulation:
-- NPCs wander, run role tasks (mining, building, farming, patrolling, trading),
-- chat publicly, converse with each other, and greet real players who come
-- near.
--
-- ---- The step model (synchronous scripts) -----------------------------------
-- Scripts run once and exit — the mod has no tick callbacks. So the sim runs
-- in discrete STEPS: each `/luamap run ai_server_sim` executes STEPS_PER_RUN
-- simulation ticks and stores the total step count in-world as a second
-- lodestone marker (`originZ + steps` at y=250). Re-run the command to advance
-- the simulation; NPCs, their positions, and the step counter all persist
-- across runs (NPCs are real entities, tracked by the mod's registry).
--
-- Everything is deterministic: rng is seeded by (SEED, step, npcIndex), so a
-- given world state always produces the same next steps.
--
-- To reset:   /luamap eval npc.removeAll()   then delete the two lodestone
-- markers at (originX, 250, originZ) and (originX, 250, originZ + steps).
--
-- Limitations: NPC movement is teleport-step "walking" (one block per tick,
-- walkability-checked via world.getblock) — not continuous physics or true
-- pathfinding. `npc.look` sets facing but models don't animate. Chat is a
-- server broadcast as "<Name> msg" — it is not signed player chat.
-- Tests may inject a `SIMCFG` global table to override constants.
-- ============================================================================

local CFG = SIMCFG or {}
local SEED           = CFG.seed           or 2024
local STEPS_PER_RUN  = CFG.steps          or 40
local SPAWN_RADIUS   = CFG.radius         or 8
local CHAT_CHANCE    = CFG.chat_chance    or 14   -- 1-in-N per npc per step
local CONVERSE_RANGE = CFG.converse_range or 4
local GREET_RANGE    = CFG.greet_range    or 7
local SCAN_LIMIT     = 8192                     -- marker scan distance (+Z)
local MARK_Y         = 250                      -- sky lane for sim markers
local MARKER         = "minecraft:lodestone"
local AIR            = "minecraft:air"

-- ---------------------------------------------------------------------------
-- Roster: fixed name -> role. Roles decide movement pattern, tasks, chatter.
-- ---------------------------------------------------------------------------
local ROSTER = {
    { name = "Sim_Aria", role = "wanderer" },
    { name = "Sim_Bo",   role = "miner"    },
    { name = "Sim_Cora", role = "builder"  },
    { name = "Sim_Dex",  role = "farmer"   },
    { name = "Sim_Eli",  role = "greeter"  },
    { name = "Sim_Finn", role = "guard"    },
    { name = "Sim_Gia",  role = "trader"   },
    { name = "Sim_Hank", role = "wanderer" },
}

local CHATTER = {
    wanderer = { "nice day for a walk", "anyone seen the mineshaft?", "just stretching my legs",
                 "this server feels alive today", "off to explore" },
    miner    = { "back to the pit", "struck stone again", "need more torches",
                 "digging level " },
    builder  = { "planks or bricks?", "wall is coming along", "watch the scaffolding",
                 "need a hand up here" },
    farmer   = { "crops coming in", "wheat prices are up", "tilling the back field",
                 "harvest soon" },
    greeter  = { "welcome to the server!", "how's it going?", "great to see new faces",
                 "make yourself at home" },
    guard    = { "all clear", "perimeter secure", "keep it moving", "patrol underway" },
    trader   = { "fresh stock at the stall!", "best prices in spawn", "trades open!",
                 "emeralds for wheat" },
}

local CONVERSATIONS = {
    { "you heading to spawn?", "yeah, in a bit" },
    { "seen any creepers?", "not since last night" },
    { "busy day?", "always is" },
    { "nice build over there", "thanks, still a wip" },
}

-- ---------------------------------------------------------------------------
-- Deterministic rng (LCG) — see parkour.lua for the same scheme
-- ---------------------------------------------------------------------------
local function makeRng(seed)
    local s = seed % 2147483648
    if s < 0 then s = s + 2147483648 end
    return function(n)
        s = (s * 1103515245 + 12345) % 2147483648
        return s % n
    end
end

local function dist(x1, z1, x2, z2)
    local dx, dz = x1 - x2, z1 - z2
    return math.sqrt(dx * dx + dz * dz)
end

local function groundY(x, z, fromY)   -- first non-air going down
    for y = fromY, -60, -1 do
        if world.getblock(x, y, z) ~= AIR then return y + 1 end
    end
    return fromY
end

-- Can an npc stand at (x,y,z)? feet+head must be air with solid ground below.
local function walkable(x, y, z)
    return world.getblock(x, y - 1, z) ~= AIR
       and world.getblock(x, y,     z) == AIR
       and world.getblock(x, y + 1, z) == AIR
end

-- Try a horizontal step; step up one if blocked by a ledge, down one if the
-- floor drops away. Returns the position actually moved to (or nil).
local function tryStep(name, tx, tz)
    local x, y, z = npc.pos(name)
    tx, tz = math.floor(tx + 0.5), math.floor(tz + 0.5)
    if math.floor(x + 0.5) == tx and math.floor(z + 0.5) == tz then return nil end
    for _, ny in ipairs({ y, y + 1, y - 1 }) do
        local iy = math.floor(ny)
        if walkable(tx, iy, tz) then
            npc.moveto(name, tx, iy, tz)
            return tx, iy, tz
        end
    end
    return nil
end

local function wander(name, rng, cx, cz, radius)
    local x, y, z = npc.pos(name)
    local dx, dz = rng(3) - 1, rng(3) - 1      -- -1,0,1 each
    if dx == 0 and dz == 0 then return end
    local tx, tz = x + dx, z + dz
    if dist(tx, tz, cx, cz) > radius then      -- stay in the leash area
        tx, tz = x - dx, z - dz                -- bounce back toward centre
    end
    tryStep(name, tx, tz)
end

-- ---------------------------------------------------------------------------
-- Role behaviours: one tick of "what this npc does", plus task side-effects.
-- Home anchors keep each role in its own corner of the sim area.
-- ---------------------------------------------------------------------------
local HOMES = {
    wanderer = { 0,  0 }, miner  = { -10, 6 },  builder = { 10, 6 },
    farmer   = { -10, -10 }, greeter = { 0, 6 }, guard  = { 0, -8 },
    trader   = { 8, -10 },
}

local function act(agent, i, step, ox, oy, oz, rng)
    local home = HOMES[agent.role]
    local hx, hz = ox + home[1], oz + home[2]

    if agent.role == "guard" then
        -- patrol between two posts; flip direction at the ends
        agent.dir = agent.dir or 1
        local x, y, z = npc.pos(agent.name)
        local target = ox + agent.dir * 6
        if math.floor(x + 0.5) == target then agent.dir = -agent.dir end
        tryStep(agent.name, x + agent.dir, z)

    elseif agent.role == "miner" then
        -- dig a trench: clear one block below ground level each tick
        local dx = (step + i) % 5
        local gy = groundY(hx + dx, hz, oy + 4)
        if world.getblock(hx + dx, gy - 1, hz) ~= "minecraft:bedrock" then
            world.setblock(hx + dx, gy - 1, hz, AIR)
        end
        wander(agent.name, rng, hx, hz, 4)

    elseif agent.role == "builder" then
        -- raise a plank wall, one block per tick
        local bx, bz = hx + ((step + i) % 4), hz + math.floor((step + i) / 4) % 3
        local gy = groundY(bx, bz, oy + 4)
        local wy = gy + ((step + i) % 4)
        if world.getblock(bx, wy, bz) == AIR then
            world.setblock(bx, wy, bz, "minecraft:oak_planks")
        end
        wander(agent.name, rng, hx, hz, 3)

    elseif agent.role == "farmer" then
        -- lay farmland + wheat in a small patch
        local fx = hx + (step + i) % 4
        local fz = hz + math.floor((step + i) / 4) % 4
        local gy = groundY(fx, fz, oy + 4)
        world.setblock(fx, gy - 1, fz, "minecraft:farmland")
        if world.getblock(fx, gy, fz) == AIR then
            world.setblock(fx, gy, fz, "minecraft:wheat")
        end
        wander(agent.name, rng, hx, hz, 5)

    elseif agent.role == "trader" then
        -- hold the stall, drift a little
        wander(agent.name, rng, hx, hz, 3)

    else  -- wanderer / greeter: free roam inside spawn radius
        wander(agent.name, rng, ox, oz, SPAWN_RADIUS + 4)
    end

    -- public chatter
    if rng(CHAT_CHANCE) == 0 then
        local bank = CHATTER[agent.role]
        local line = bank[rng(#bank) + 1]
        if agent.role == "miner" and line:sub(-1) == " " then
            line = line .. (60 + rng(10))    -- "digging level 62"
        end
        npc.say(agent.name, line)
    end
end

-- ---------------------------------------------------------------------------
-- Interactions: NPC<->NPC conversation when close; NPC->player greetings
-- ---------------------------------------------------------------------------
local function interactions(npcs, step, rng)
    for a = 1, #npcs do
        for b = a + 1, #npcs do
            local ax, _, az = npc.pos(npcs[a].name)
            local bx, _, bz = npc.pos(npcs[b].name)
            if dist(ax, az, bx, bz) <= CONVERSE_RANGE then
                local crng = makeRng(SEED + step * 131 + a * 17 + b)
                if crng(18) == 0 then
                    local convo = CONVERSATIONS[crng(#CONVERSATIONS) + 1]
                    npc.say(npcs[a].name, convo[1])
                    npc.say(npcs[b].name, convo[2])
                end
            end
        end
    end

    if player.exists() then
        local px, py, pz = player.pos()
        for i, n in ipairs(npcs) do
            if n.role == "greeter" or n.role == "trader" then
                local x, y, z = npc.pos(n.name)
                if dist(x, z, px, pz) <= GREET_RANGE then
                    local grng = makeRng(SEED + step * 977 + i)
                    if step == 1 or grng(10) == 0 then
                        npc.say(n.name, "hey " .. player.name() .. "!")
                    end
                end
            end
        end
    end
end

-- ---------------------------------------------------------------------------
-- Step persistence: two sky markers.
--   anchor  = lodestone at (ox, MARK_Y, oz)            — "sim origin"
--   stepper = lodestone at (ox, MARK_Y, oz + steps)    — "sim ran N ticks"
-- ---------------------------------------------------------------------------
local function findAnchor(px, pz)
    local x0 = math.floor(px + 0.5)
    for z = math.floor(pz) - 32, math.floor(pz) + SCAN_LIMIT do
        for dx = -4, 4 do
            if world.getblock(x0 + dx, MARK_Y, z) == MARKER then
                return x0 + dx, z
            end
        end
    end
    return nil
end

local function findStepCount(ox, oz)
    for z = oz + 1, oz + SCAN_LIMIT do
        if world.getblock(ox, MARK_Y, z) == MARKER then
            return z - oz, z
        end
    end
    return 0, nil
end

local function moveStepMarker(ox, oz, oldZ, steps)
    if oldZ then world.setblock(ox, MARK_Y, oldZ, AIR) end
    world.setblock(ox, MARK_Y, oz + steps, MARKER)
end

-- ---------------------------------------------------------------------------
-- Main
-- ---------------------------------------------------------------------------
local px, py, pz = 0, 65, 0
local havePlayer = player.exists()
if havePlayer then px, py, pz = player.pos() end

local ox, oz, steps, stepMarkZ
local ax, az = findAnchor(px, pz)

if ax then
    ox, oz = ax, az
    steps, stepMarkZ = findStepCount(ox, oz)
    chat(string.format("[sim] resuming at step %d (origin %d,%d)", steps, ox, oz))
else
    ox, oz = math.floor(px), math.floor(pz)
    steps = 0
    world.setblock(ox, MARK_Y, oz, MARKER)   -- anchor marker
    chat(string.format("[sim] new simulation at %d,%d", ox, oz))
end

local oy = groundY(ox, oz, math.floor(py) + 4)

-- spawn (or re-attach to) the roster
local npcs = {}
for i, def in ipairs(ROSTER) do
    if not npc.exists(def.name) then
        local angle = (i / #ROSTER) * 6.283185
        local sx = ox + math.floor(math.cos(angle) * 4 + 0.5)
        local sz = oz + math.floor(math.sin(angle) * 4 + 0.5)
        npc.spawn(def.name, sx, groundY(sx, sz, oy + 4), sz)
    end
    npcs[#npcs + 1] = { name = def.name, role = def.role }
end

-- run STEPS_PER_RUN deterministic ticks
for s = steps + 1, steps + STEPS_PER_RUN do
    for i, n in ipairs(npcs) do
        act(n, i, s, ox, oy, oz, makeRng(SEED + s * 31 + i))
    end
    interactions(npcs, s, makeRng(SEED + s))
end

steps = steps + STEPS_PER_RUN
moveStepMarker(ox, oz, stepMarkZ, steps)

chat(string.format("[sim] step %d complete — %d npcs active; re-run to advance",
    steps, npc.count()))
return string.format("steps=%d npcs=%d", steps, npc.count())
