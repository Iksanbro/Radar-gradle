-- =====================================================================
--  IFF-передатчик "свой" для radar.lua
--  Ставится на свою конструкцию (компьютер + ender modem).
--  Использование:
--    iff_beacon [радиус]          -- позиция по GPS (для кораблей)
--    iff_beacon [радиус] x y z    -- фиксированная позиция (для баз)
--  Всё в радиусе от передатчика радар считает своим и не стреляет.
-- =====================================================================
local PROTOCOL = "RADAR_IFF"
local KEY      = "change_me"   -- должен совпадать с iff.key в radar.cfg
local PERIOD   = 0.5

local args = {...}
local RADIUS = tonumber(args[1]) or 12
local fixed
if #args >= 4 then
  fixed = {x = tonumber(args[2]), y = tonumber(args[3]), z = tonumber(args[4])}
end

local opened = false
peripheral.find("modem", function(name) rednet.open(name); opened = true end)
if not opened then error("Modem required (ender modem recommended)", 0) end

term.clear(); term.setCursorPos(1, 1)
print("IFF beacon, radius " .. RADIUS)
while true do
  local pos = fixed
  if not pos then
    local x, y, z = gps.locate(1)
    if x then pos = {x = x, y = y, z = z} end
  end
  term.setCursorPos(1, 3); term.clearLine()
  if pos then
    rednet.broadcast({key = KEY, pos = pos, radius = RADIUS}, PROTOCOL)
    term.write(("TX %d %d %d"):format(pos.x, pos.y, pos.z))
  else
    term.write("No GPS fix!")
  end
  sleep(PERIOD)
end
