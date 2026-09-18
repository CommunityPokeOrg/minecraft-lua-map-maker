-- arena.lua — a small walled fighting arena.
-- Run with: /luamap run arena
-- Builds centered at the player (or at 0,~,0 when run from console).

local cx, cy, cz
if player.exists() then
  cx, cy, cz = player.pos()
  cx, cy, cz = math.floor(cx), math.floor(cy) - 1, math.floor(cz)
else
  cx, cy, cz = 0, 64, 0
end

local r = 12 -- arena radius (square)

-- clear the space
world.fill(cx - r, cy, cz - r, cx + r, cy + 12, cz + r, "minecraft:air")

-- floor
world.fill(cx - r, cy - 1, cz - r, cx + r, cy - 1, cz + r, "minecraft:stone_bricks")

-- walls (shell) with a doorway on the south side
world.hollow(cx - r, cy, cz - r, cx + r, cy + 4, cz + r, "minecraft:stone_bricks")
world.fill(cx - 1, cy, cz + r, cx + 1, cy + 2, cz + r, "minecraft:air")

-- corner pillars
for _, dx in ipairs({-r, r}) do
  for _, dz in ipairs({-r, r}) do
    world.fill(cx + dx, cy, cz + dz, cx + dx, cy + 5, cz + dz, "minecraft:chiseled_stone_bricks")
    world.setblock(cx + dx, cy + 6, cz + dz, "minecraft:lantern")
  end
end

-- center marker
world.setblock(cx, cy, cz, "minecraft:gold_block")

world.spawn(cx, cy + 1, cz + r + 2)
world.time("day")
world.weather("clear")

chat(string.format("arena built at %d %d %d (radius %d)", cx, cy, cz, r))
