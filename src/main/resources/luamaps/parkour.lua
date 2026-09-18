-- ============================================================================
-- parkour.lua — infinite procedural parkour generator
-- ============================================================================
--
-- Run it with:   /luamap run parkour
--
-- Each run extends the course by SEGMENTS_PER_RUN new segments *ahead of
-- where generation last stopped*. The course advances in +Z; every run is
-- fully deterministic: segment i is a pure function of (SEED, i), so the
-- course is reproducible and the generator can resume exactly where it left
-- off — progress is stored in-world via two sky markers at the course tip:
--
--   * a lodestone at  (laneX, 200, tipZ)          — "generation reached here"
--   * a bedrock at    (laneX, 201 + i, tipZ)      — "i segments exist"
--
-- To extend the course: stand on/near the course and run the command again —
-- the tip marker is found by scanning forward at y=200. If no marker is found
-- ahead within SCAN_LIMIT blocks, a brand-new course starts at your position.
--
-- ---- Limitations (mod API model) -------------------------------------------
-- * Scripts run synchronously and then exit — there is no per-tick callback,
--   so "generate ahead WHILE you play" is emulated by re-running the command
--   (or via /luamap eval parkour_extend() style drivers). Default batch is
--   tuned to ~5-10 minutes of play per run.
-- * No per-player respawn-point API: checkpoints are visual (lit gold pads).
--   world.spawn is set once at course start so falling just costs a walk.
--   Set SAFETY_NET = true to add a catch floor under the course instead.
-- * The odometer encodes segment index in the bedrock marker's Y, capacity
--   INDEX_CAPACITY (118). Beyond it the counter saturates — the course still
--   extends, just re-uses segment index 118's difficulty pattern.
-- ============================================================================

local SEED             = 1337   -- change for a different course layout
local SEGMENTS_PER_RUN = 30     -- segments generated per /luamap run parkour
local CHECKPOINT_EVERY = 5      -- every Nth segment is a checkpoint pad
local HAZARD_LAVA      = true   -- lava strip under the course for stakes
local SAFETY_NET       = false  -- glass catch-floor instead of falling
local SCAN_LIMIT       = 4096   -- how far forward to look for the tip marker

local MARK_Y           = 200    -- sky lane for the tip marker (above builds)
local INDEX_BASE       = 201    -- bedrock odometer base y
local INDEX_CAPACITY   = 118    -- 201+118 = 319 = world height limit (1.20.4)

local MARKER   = "minecraft:lodestone"
local ODOMETER = "minecraft:bedrock"
local AIR      = "minecraft:air"

-- ---------------------------------------------------------------------------
-- Deterministic PRNG (LCG; Lua doubles keep 53-bit precision so this is exact)
-- ---------------------------------------------------------------------------
local function makeRng(seed)
    local s = seed % 2147483648
    if s < 0 then s = s + 2147483648 end
    return function(n)                 -- nextInt(n): 0..n-1
        s = (s * 1103515245 + 12345) % 2147483648
        return s % n
    end
end

-- ---------------------------------------------------------------------------
-- Small helpers
-- ---------------------------------------------------------------------------
local function B(x, y, z, spec)        -- bounded setblock (never out of world)
    if y >= -60 and y <= 319 then
        world.setblock(x, y, z, spec)
    end
end

local function surfaceY(x, z, fromY)   -- scan down for the ground
    for y = fromY, -60, -1 do
        if world.getblock(x, y, z) ~= AIR then return y + 1 end
    end
    return fromY
end

-- palettes rotate every 8 segments for visual variety
local PALETTES = {
    { solid = "minecraft:stone_bricks",  trim = "minecraft:mossy_stone_bricks", pad = "minecraft:iron_block"      },
    { solid = "minecraft:sandstone",     trim = "minecraft:cut_sandstone",      pad = "minecraft:gold_block"      },
    { solid = "minecraft:blackstone",    trim = "minecraft:gilded_blackstone",  pad = "minecraft:obsidian"        },
    { solid = "minecraft:end_stone_bricks", trim = "minecraft:purpur_block",    pad = "minecraft:purpur_pillar"   },
    { solid = "minecraft:prismarine_bricks", trim = "minecraft:dark_prismarine", pad = "minecraft:sea_lantern"    },
}
local function palette(i) return PALETTES[(math.floor(i / 8) % #PALETTES) + 1] end

-- difficulty ramps with the segment index
local function maxGap(i)      return math.min(4, 2 + math.floor(i / 12)) end
local function allowHard(i)   return i >= 10 end   -- 4-gap + single blocks
local function allowRise(i)   return math.min(2, math.floor(i / 8)) end

-- ---------------------------------------------------------------------------
-- Segment generators. Each returns the z where the next segment starts and
-- the y the course sits on after it. cx = lane center x, cy/cz = entry.
-- ---------------------------------------------------------------------------

-- A single landing platform, optionally smaller/harder.
local function pad(cx, y, z, w, spec)
    local half = math.floor(w / 2)
    world.fill(cx - half, y, z, cx + half, y, z + math.max(0, w - 1), spec)
end

-- plain jump chain: platforms with gaps of 2..maxGap and small height changes
local function segJumps(i, rng, cx, cy, cz)
    local pal = palette(i)
    local y, z = cy, cz
    local platforms = 4 + rng(4)
    for _ = 1, platforms do
        local gap  = 2 + rng(maxGap(i) - 1)          -- 2..maxGap
        local dy   = rng(3) - 1                      -- -1..+1
        if allowHard(i) and rng(5) == 0 then dy = dy + 1 end
        local size = allowHard(i) and rng(4) == 0 and 1 or 3
        z = z + gap
        y = math.max(cy - 2, y + dy)                 -- never sink too far
        pad(cx, y, z, size, size == 1 and pal.trim or pal.solid)
        z = z + size
    end
    return z, y
end

-- staircase up or down
local function segStairs(i, rng, cx, cy, cz)
    local pal = palette(i)
    local up = rng(2) == 0
    local steps = 6 + rng(6)
    local y, z = cy, cz
    for _ = 1, steps do
        y = up and (y + 1) or math.max(1, y - 1)
        pad(cx, y, z, 3, pal.solid)
        z = z + 1
    end
    return z, y
end

-- narrow 1-wide beam, optionally weaving
local function segBeam(i, rng, cx, cy, cz)
    local pal = palette(i)
    local len = 7 + rng(5)
    local x = cx
    for z = cz, cz + len - 1 do
        if rng(6) == 0 then x = x + (rng(2) == 0 and 1 or -1) end
        B(x, cy, z, pal.trim)
    end
    return cz + len + 2, cy
end

-- platforms alternating left/right of the lane
local function segWeave(i, rng, cx, cy, cz)
    local pal = palette(i)
    local z = cz
    local side = rng(2) == 0 and -2 or 2
    for _ = 1, 4 + rng(3) do
        side = -side
        pad(cx + side, cy, z, 2, pal.solid)
        z = z + 2 + rng(2)
    end
    return z, cy
end

-- slime bounce pads, spaced wider
local function segSlime(i, rng, cx, cy, cz)
    local z = cz
    for _ = 1, 3 + rng(3) do
        z = z + 2 + rng(2)
        pad(cx, cy, z, 2, "minecraft:slime_block")
        z = z + 2
    end
    return z, cy
end

-- slippery ice pads
local function segIce(i, rng, cx, cy, cz)
    local z = cz
    for _ = 1, 4 + rng(3) do
        z = z + 2 + rng(3)
        pad(cx, cy, z, 3, "minecraft:packed_ice")
        z = z + 3
    end
    return z, cy
end

-- drop-down ledges
local function segDrop(i, rng, cx, cy, cz)
    local pal = palette(i)
    local y, z = cy, cz
    for _ = 1, 3 + rng(2) do
        y = y - (1 + rng(2))
        z = z + 2 + rng(2)
        pad(cx, y, z, 3, pal.solid)
        z = z + 3
    end
    return z, y
end

-- spiralling step-tower around a column (a climbing challenge)
local function segTower(i, rng, cx, cy, cz)
    local pal = palette(i)
    local height = 6 + rng(5)
    world.fill(cx, cy - 1, cz + 4, cx, cy + height + 2, cz + 4, pal.trim) -- column
    local ring = { {1,0}, {1,1}, {0,1}, {-1,1}, {-1,0}, {-1,-1}, {0,-1}, {1,-1} }
    local y, k = cy, 1
    for _ = 1, height do
        local off = ring[k]
        B(cx + off[1], y, cz + 4 + off[2], pal.solid)
        k = k % #ring + 1
        y = y + 1
    end
    pad(cx, y, cz + 9, 3, pal.solid)   -- exit pad
    return cz + 12, y
end

-- lava gap: platform ends, a lava trench, landing slab lower
local function segLavaGap(i, rng, cx, cy, cz)
    local pal = palette(i)
    local span = 4 + rng(2)
    world.fill(cx - 3, cy - 3, cz, cx + 3, cy - 3, cz + span + 2, "minecraft:lava")
    pad(cx, cy - 1, cz + span + 1, 3, pal.trim)
    return cz + span + 4, cy - 1
end

local GENERATORS = { segJumps, segStairs, segBeam, segWeave, segSlime, segIce, segDrop, segTower, segLavaGap }
local UNLOCKED   = { [1]=0, [2]=0, [3]=3, [4]=5, [5]=8, [6]=12, [7]=6, [8]=15, [9]=10 }

local function pickGenerator(i, rng)
    -- ordered iteration: pairs() order isn't guaranteed, and we need the
    -- generator choice for segment i to be identical on every run
    local pool = {}
    for gi = 1, #GENERATORS do
        if i >= UNLOCKED[gi] then pool[#pool + 1] = GENERATORS[gi] end
    end
    return pool[rng(#pool) + 1]
end

-- checkpoint pad: a lit platform every CHECKPOINT_EVERY segments
local function checkpoint(cx, cy, cz)
    world.fill(cx - 2, cy, cz, cx + 2, cy, cz + 4, "minecraft:gold_block")
    world.fill(cx - 1, cy + 1, cz + 1, cx + 1, cy + 1, cz + 3, AIR)
    B(cx - 2, cy + 1, cz,     "minecraft:lantern")
    B(cx + 2, cy + 1, cz,     "minecraft:lantern")
    B(cx - 2, cy + 1, cz + 4, "minecraft:lantern")
    B(cx + 2, cy + 1, cz + 4, "minecraft:lantern")
    return cz + 6
end

-- ---------------------------------------------------------------------------
-- Tip marker: find / write / clear
-- ---------------------------------------------------------------------------
local function findTip(px, pz)
    local x0 = math.floor(px + 0.5)
    for z = math.floor(pz), math.floor(pz) + SCAN_LIMIT do
        for dx = -3, 3 do
            if world.getblock(x0 + dx, MARK_Y, z) == MARKER then
                return x0 + dx, z
            end
        end
    end
    return nil
end

local function decodeIndex(tx, tz)
    for y = INDEX_BASE, INDEX_BASE + INDEX_CAPACITY do
        if world.getblock(tx, y, tz) == ODOMETER then
            return y - INDEX_BASE
        end
    end
    return 0
end

local function writeMarker(laneX, tipZ, count)
    B(laneX, MARK_Y, tipZ, MARKER)
    B(laneX, INDEX_BASE + math.min(count, INDEX_CAPACITY), tipZ, ODOMETER)
end

local function clearMarker(laneX, tipZ)
    B(laneX, MARK_Y, tipZ, AIR)
    for y = INDEX_BASE, INDEX_BASE + INDEX_CAPACITY do
        if world.getblock(laneX, y, tipZ) == ODOMETER then B(laneX, y, tipZ, AIR) end
    end
end

-- ---------------------------------------------------------------------------
-- Main
-- ---------------------------------------------------------------------------
local px, py, pz = 0, 65, 0
if player.exists() then
    px, py, pz = player.pos()
end

local tipX, tipZ = findTip(px, pz)
local laneX, zCursor, yCursor, i0

if tipX then
    -- resume: keep lane, continue from the tip. The tip platform's height is
    -- recovered by scanning down just behind the marker (below the odometer).
    laneX   = tipX
    zCursor = tipZ + 1
    i0      = decodeIndex(tipX, tipZ)
    yCursor = nil
    for y = MARK_Y - 1, -60, -1 do
        local b = world.getblock(laneX, y, tipZ - 1)
        if b ~= AIR and b ~= MARKER and b ~= ODOMETER then yCursor = y break end
    end
    yCursor = yCursor or surfaceY(laneX, zCursor + 6, MARK_Y)
    chat(string.format("[parkour] found course tip at z=%d (segment %d, y=%d) — extending", tipZ, i0, yCursor))
else
    -- fresh course anchored on the player
    laneX   = math.floor(px)
    zCursor = math.floor(pz) + 3
    yCursor = surfaceY(laneX, zCursor, math.floor(py) + 4)
    i0      = 0
    world.spawn(laneX, yCursor + 1, math.floor(pz))
    chat(string.format("[parkour] new course from %d,%d going +Z", laneX, zCursor))
end

for i = i0, i0 + SEGMENTS_PER_RUN - 1 do
    local rng = makeRng(SEED + i)
    if i > 0 and i % CHECKPOINT_EVERY == 0 then
        zCursor = checkpoint(laneX, yCursor, zCursor)
        zCursor = zCursor + 2
    end
    local gen = pickGenerator(i, rng)
    zCursor, yCursor = gen(i, rng, laneX, yCursor, zCursor)
    zCursor = zCursor + 1   -- breathing room between segments
end

-- hazards / safety net across the generated span
if HAZARD_LAVA and not SAFETY_NET then
    world.fill(laneX - 4, yCursor - 6, tipZ or math.floor(pz) + 3,
               laneX + 4, yCursor - 6, zCursor - 1, "minecraft:lava")
elseif SAFETY_NET then
    world.fill(laneX - 4, yCursor - 6, tipZ or math.floor(pz) + 3,
               laneX + 4, yCursor - 6, zCursor - 1, "minecraft:glass")
end

-- move the tip marker forward
if tipX then clearMarker(tipX, tipZ) end
writeMarker(laneX, zCursor - 1, i0 + SEGMENTS_PER_RUN)

world.time("day")
world.weather("clear")

chat(string.format("[parkour] generated segments %d-%d, tip now at z=%d — re-run /luamap run parkour when you get close!",
    i0, i0 + SEGMENTS_PER_RUN - 1, zCursor - 1))
return string.format("segments=%d tip_z=%d", i0 + SEGMENTS_PER_RUN, zCursor - 1)
