-- hello.lua — smallest possible smoke test.
-- Run with: /luamap run hello
-- Builds a tiny signpost at a fixed spot and tells you what happened.

local x, y, z = 8, 100, 8  -- adjust to taste; this example works over air too

chat("hello from Lua Map Maker!")

world.setblock(x, y, z, "minecraft:oak_planks")
world.setblock(x, y + 1, z, "minecraft:torch")
chat("placed marker at " .. x .. " " .. y .. " " .. z .. " — block is " .. world.getblock(x, y, z))

if player.exists() then
  local px, py, pz = player.pos()
  chat(string.format("you are %s at %.0f %.0f %.0f", player.name(), px, py, pz))
else
  chat("(run by console — player API skipped)")
end
