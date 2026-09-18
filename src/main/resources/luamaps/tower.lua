-- tower.lua — a 5x5 lookout tower with battlements and a ladder.
-- Run with: /luamap run tower

local cx, cy, cz
if player.exists() then
  cx, cy, cz = player.pos()
  cx, cy, cz = math.floor(cx), math.floor(cy), math.floor(cz)
else
  cx, cy, cz = 0, 64, 0
end

local height = 20
local w = 2 -- half-width (tower is 5x5)

-- foundation
world.fill(cx - w, cy - 1, cz - w, cx + w, cy - 1, cz + w, "minecraft:cobblestone")

-- hollow walls
world.hollow(cx - w, cy, cz - w, cx + w, cy + height, cz + w, "minecraft:cobblestone")

-- interior floor + top floor
world.fill(cx - w + 1, cy - 1, cz - w + 1, cx + w - 1, cy - 1, cz + w - 1, "minecraft:oak_planks")
world.fill(cx - w, cy + height, cz - w, cx + w, cy + height, cz + w, "minecraft:oak_planks")

-- doorway on south face
world.fill(cx, cy, cz + w, cx, cy + 1, cz + w, "minecraft:air")

-- ladder up the interior north wall
for i = 0, height - 1 do
  world.setblock(cx, cy + i, cz - w + 1, "minecraft:ladder[facing=south]")
end

-- battlements
for dx = -w, w do
  for dz = -w, w do
    if (dx + dz) % 2 == 0 and (dx == -w or dx == w or dz == -w or dz == w) then
      world.setblock(cx + dx, cy + height + 1, cz + dz, "minecraft:cobblestone_wall")
    end
  end
end

-- beacon block on top
world.setblock(cx, cy + height + 1, cz, "minecraft:lantern[hanging=false]")

chat(string.format("tower built at %d %d %d (%d blocks tall)", cx, cy, cz, height))
