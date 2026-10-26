-- =====================================================================
--  RADAR + СУО для Create Big Cannons / CBC Advanced Technology
--  CC:Tweaked. Plug&play: пушки и радары находятся сами по методам.
--  Клавиши: H - стоп/разрешить огонь, R - пересканировать периферию,
--           C - сбросить треки, Q - выход
--  Файлы: radar.cfg (общие настройки), radar_cannons.cfg (по пушкам)
-- =====================================================================
local VERSION = "1.0"
local CFG_PATH, DB_PATH = "radar.cfg", "radar_cannons.cfg"

local DEFAULT_CFG = {
  -- base = {x=0, y=64, z=0},  -- позиция радара; если нет -> gps.locate()
  mobileBase   = false,  -- true: радар на корабле, позиция по GPS каждую сек
  scanRadius   = 160,
  minRange     = 6,      -- ближе не стреляем
  cycleDelay   = 0.05,
  minSpeed     = 0.6,    -- бл/сек; медленнее -> цель неактивна (не стреляем, не рисуем)
  trackTimeout = 2.0,    -- сек без обнаружения -> трек удаляется
  minSamples   = 3,      -- замеров до того, как цель станет активной
  velSmoothing = 0.45,   -- сглаживание скорости (0..1)
  aimHeight    = 0.9,    -- целимся в центр моба, а не в ноги
  targetMobs = true, targetPlayers = false, targetShips = true,
  hostileOnly = false,   -- если сканер отдаёт isHostile
  friendlyPlayers = {},  -- {"Nick1","Nick2"}
  ignorePatterns = {"item","experience","arrow","shell","shot","bullet","projectile",
    "fireball","falling","trident","firework","bolt","grenade","snowball","egg",
    "potion","pearl","marker","armor_stand","painting","frame","leash","boat",
    "minecart","contraption","seat","glue","display","interaction","lightning",
    "area_effect","bat"},
  iff = {protocol = "RADAR_IFF", key = "change_me", timeout = 3, defaultRadius = 10},
  aim = {
    tolerance  = 0.75,   -- мин. допуск наведения, град (не абсолютная точность!)
    deadband   = 0.2,    -- не перекомандовываем пушку при изменении меньше этого
    latency    = 0.15,   -- сек: задержка выстрела/команд для упреждения
    leadIter   = 4,
    minPitch   = -30, maxPitch = 60,
    settleDegPerSec = 90, -- если пушка не отдаёт текущий угол
    safetyCone = 5,       -- не стрелять, если свой в этом конусе перед целью
    hitRadius  = {entity = 1.0, player = 0.8, ship = 4},
  },
  yawOffset = 0, yawDir = 1, pitchDir = 1,   -- калибровка осей
  holdFire = false,
  profiles = {
    -- speed: бл/тик, gravity: бл/тик^2, drag: доля потери скорости за тик
    big   = {speed = 4.0, gravity = 0.05,  drag = 0.01, reload = 5.0, mode = "pulse", maxTicks = 400, maxRange = 400},
    rapid = {speed = 6.0, gravity = 0.025, drag = 0.01, reload = 0.1, mode = "hold",  maxTicks = 200, maxRange = 220},
  },
  typeProfiles = {
    {pattern = "autocannon", profile = "rapid"},
    {pattern = "rotary",     profile = "rapid"},
    {pattern = "machine",    profile = "rapid"},
    {pattern = "gatling",    profile = "rapid"},
    {pattern = "",           profile = "big"},
  },
  -- методы сканеров (добавьте свои, если у мода другое имя)
  radars = {
    {method = "scanEntities",        kind = "entity", relative = true },  -- AP Environment Detector
    {method = "scanForEntities",     kind = "entity", relative = false},
    {method = "getEntities",         kind = "entity", relative = false},
    {method = "scanForShips",        kind = "ship",   relative = false},
    {method = "scanShips",           kind = "ship",   relative = false},
    {method = "getShips",            kind = "ship",   relative = false},
    {method = "scanForContraptions", kind = "ship",   relative = false},
    {method = "scanContraptions",    kind = "ship",   relative = false},
    {method = "getSubLevels",        kind = "ship",   relative = false},
  },
}

-- имена методов пушек (первый найденный используется)
local M = {
  setYaw    = {"setYaw", "setTargetYaw", "setYawAngle"},
  setPitch  = {"setPitch", "setTargetPitch", "setPitchAngle"},
  getYaw    = {"getYaw", "getCurrentYaw", "getYawAngle"},
  getPitch  = {"getPitch", "getCurrentPitch", "getPitchAngle"},
  fire      = {"fire", "shoot"},
  setFiring = {"setFiring", "setFire"},
  assemble  = {"assemble"},
  running   = {"isRunning", "isAssembled"},
  pos       = {"getPosition", "getPos", "getWorldPosition"},
}

-- ---------------------------- утилиты --------------------------------
local sqrt, abs, floor, max, min = math.sqrt, math.abs, math.floor, math.max, math.min
local atan2 = math.atan2 or math.atan
local rad, deg = math.rad, math.deg
local function now() return os.clock() end
local function wrap180(a) return (a + 180) % 360 - 180 end
local function dist3(a, b)
  local dx, dy, dz = a.x - b.x, a.y - b.y, a.z - b.z
  return sqrt(dx * dx + dy * dy + dz * dz)
end
local function vec(t)
  if type(t) ~= "table" then return nil end
  local x, y, z = tonumber(t.x or t[1]), tonumber(t.y or t[2]), tonumber(t.z or t[3])
  if x and y and z then return {x = x, y = y, z = z} end
end
local function copy(t) return textutils.unserialize(textutils.serialize(t)) end
local function merge(dst, src)
  for k, v in pairs(src) do
    if type(v) == "table" and type(dst[k]) == "table" and #v == 0 then merge(dst[k], v)
    else dst[k] = v end
  end
  return dst
end
local function readTable(p)
  if not fs.exists(p) then return nil end
  local f = fs.open(p, "r"); local s = f.readAll(); f.close()
  return textutils.unserialize(s)
end
local function writeTable(p, t)
  local f = fs.open(p, "w"); f.write(textutils.serialize(t)); f.close()
end
local function firstMethod(set, list)
  for _, m in ipairs(list) do if set[m] then return m end end
end
local function call(name, method, ...)
  local r = {pcall(peripheral.call, name, method, ...)}
  if r[1] then return true, table.unpack(r, 2) end
  return false
end

-- ---------------------------- конфиг ---------------------------------
local cfg = copy(DEFAULT_CFG)
local userCfg = readTable(CFG_PATH)
if userCfg then merge(cfg, userCfg) else writeTable(CFG_PATH, cfg) end
local db = readTable(DB_PATH) or {}

peripheral.find("modem", function(n) if not rednet.isOpen(n) then rednet.open(n) end end)

local base = vec(cfg.base)
local function locateBase()
  local x, y, z = gps.locate(1)
  if x then base = {x = x, y = y, z = z} end
end
if not base then locateBase() end
if not base then error("No GPS fix. Set base={x=..,y=..,z=..} in " .. CFG_PATH, 0) end

-- --------------------------- периферия -------------------------------
local cannons, radars, monitors = {}, {}, {}
local needRescan = true

local function profileFor(ptype)
  local lt = ptype:lower()
  for _, tp in ipairs(cfg.typeProfiles) do
    if lt:find(tp.pattern, 1, true) then return tp.profile end
  end
  return "big"
end

local function buildCannon(name, ptype, set)
  local entry = db[name]
  if not entry then
    -- новая пушка: запоминаем, чтобы её можно было донастроить в файле
    entry = {type = ptype, profile = profileFor(ptype), enabled = true,
             pos = false, yawOffset = false, redstone = false}
    db[name] = entry
    writeTable(DB_PATH, db)
  end
  local m = {}
  for k, list in pairs(M) do m[k] = firstMethod(set, list) end
  local c = cannons[name] or {name = name, lastFire = -1e9, settleUntil = 0, lastAssemble = -1e9}
  c.type, c.m, c.db = ptype, m, entry
  c.prof = cfg.profiles[entry.profile] or cfg.profiles.big
  c.pos, c.posGuess = vec(entry.pos), false
  if not c.pos and m.pos then
    local ok, a, b, cc = call(name, m.pos)
    if ok then c.pos = vec(type(a) == "table" and a or {a, b, cc}) end
  end
  if not c.pos then c.pos = {x = base.x, y = base.y, z = base.z}; c.posGuess = true end
  return c
end

local function rescan()
  local newC, newR, newM = {}, {}, {}
  for _, name in ipairs(peripheral.getNames()) do
    local ptype = peripheral.getType(name) or "?"
    if ptype == "monitor" then
      newM[#newM + 1] = name
      pcall(peripheral.call, name, "setTextScale", 0.5)
    elseif ptype == "modem" then
      if not rednet.isOpen(name) then pcall(rednet.open, name) end
    else
      local set = {}
      local ok, list = pcall(peripheral.getMethods, name)
      if ok and list then for _, mm in ipairs(list) do set[mm] = true end end
      if firstMethod(set, M.setYaw) and firstMethod(set, M.setPitch) then
        local c = buildCannon(name, ptype, set)
        if c.db.enabled ~= false then newC[name] = c end
      end
      for _, spec in ipairs(cfg.radars) do
        if set[spec.method] then newR[#newR + 1] = {name = name, spec = spec} end
      end
    end
  end
  cannons, radars, monitors = newC, newR, newM
  needRescan = false
end

-- ------------------------------ IFF ----------------------------------
local friends = {}
local function isFriendPlayer(n)
  for _, f in ipairs(cfg.friendlyPlayers) do if f == n then return true end end
  return false
end
local function isFriendly(t, tnow)
  if t.kind == "player" and (not cfg.targetPlayers or isFriendPlayer(t.name)) then return true end
  for id, f in pairs(friends) do
    if tnow - f.time > cfg.iff.timeout then friends[id] = nil
    elseif dist3(t.pos, f.pos) <= f.radius then return true end
  end
  return false
end
local function iffLoop()
  while true do
    local id, msg = rednet.receive(cfg.iff.protocol)
    if type(msg) == "table" and msg.key == cfg.iff.key then
      local p = vec(msg.pos)
      if p then
        friends[id] = {pos = p, radius = tonumber(msg.radius) or cfg.iff.defaultRadius, time = now()}
      end
    end
  end
end

-- ----------------------------- треки ---------------------------------
local tracks, frame, anonN = {}, 0, 0

local function extract(e, key, spec)
  if type(e) ~= "table" then return nil end
  local p = vec(e.position) or vec(e.pos) or vec(e.worldPosition) or vec(e.center) or vec(e)
  if not p then return nil end
  if spec.relative then p.x, p.y, p.z = p.x + base.x, p.y + base.y, p.z + base.z end
  local id = e.uuid or e.id or e.shipId or e.slug
  if id == nil and type(key) == "string" then id = key end
  local kind = spec.kind
  if kind == "entity" and (e.isPlayer or e.type == "minecraft:player" or e.player == true) then
    kind = "player"
  end
  return {id = id ~= nil and tostring(id) or nil, kind = kind, pos = p, hostile = e.isHostile,
          name = tostring(e.displayName or e.name or e.type or e.slug or "?")}
end

local function ignored(o)
  if o.kind == "player" then return false end           -- игроки нужны для безопасности
  if o.kind == "ship" then return not cfg.targetShips end
  if not cfg.targetMobs then return true end
  if cfg.hostileOnly and o.hostile == false then return true end
  local n = o.name:lower()
  for _, pat in ipairs(cfg.ignorePatterns) do
    if n:find(pat, 1, true) then return true end
  end
  return false
end

local function keyFor(o)
  if o.id then return o.kind .. ":" .. o.id end
  local best, bd = nil, 4
  for k, t in pairs(tracks) do
    if t.anon and t.kind == o.kind and t.frame ~= frame then
      local d = dist3(t.pos, o.pos)
      if d < bd then best, bd = k, d end
    end
  end
  if best then return best end
  anonN = anonN + 1
  return "anon" .. anonN
end

local function updateTrack(key, o, t0)
  local t = tracks[key]
  if not t then
    tracks[key] = {key = key, kind = o.kind, name = o.name, pos = o.pos,
                   vel = {x = 0, y = 0, z = 0}, samples = 1, last = t0,
                   frame = frame, anon = (o.id == nil), moving = false}
    return
  end
  if t.frame == frame then return end             -- дубль со второго радара
  local dt = (t0 - t.last) * 20                   -- тики
  if dt >= 0.5 then
    local a = (t.samples < 2) and 1 or cfg.velSmoothing
    local v = t.vel
    t.vel = {x = v.x + ((o.pos.x - t.pos.x) / dt - v.x) * a,
             y = v.y + ((o.pos.y - t.pos.y) / dt - v.y) * a,
             z = v.z + ((o.pos.z - t.pos.z) / dt - v.z) * a}
    t.samples = t.samples + 1
    t.pos, t.last = o.pos, t0
  end
  t.name, t.frame = o.name, frame
end

local function scanAll()
  local results = {}
  local tasks = {}
  for i, r in ipairs(radars) do
    tasks[#tasks + 1] = function()
      local ok, a, b = call(r.name, r.spec.method, cfg.scanRadius)
      if ok then results[i] = {list = (type(a) == "table" and a) or (type(b) == "table" and b), t = now()} end
    end
  end
  if #tasks > 0 then parallel.waitForAll(table.unpack(tasks)) end
  for i, r in ipairs(radars) do
    local res = results[i]
    if res and res.list then
      for key, e in pairs(res.list) do
        local o = extract(e, key, r.spec)
        if o and not ignored(o) then updateTrack(keyFor(o), o, res.t) end
      end
    end
  end
end

local function classify(tnow)
  for k, t in pairs(tracks) do
    if tnow - t.last > cfg.trackTimeout then
      tracks[k] = nil
    else
      local v = t.vel
      t.speed = sqrt(v.x * v.x + v.y * v.y + v.z * v.z) * 20
      if t.speed >= cfg.minSpeed then t.moving = true
      elseif t.speed < cfg.minSpeed * 0.6 then t.moving = false end   -- гистерезис
      t.friendly = isFriendly(t, tnow)
      t.dist = dist3(t.pos, base)
      t.active = not t.friendly and t.moving and t.samples >= cfg.minSamples
                 and t.dist >= cfg.minRange
    end
  end
end

-- --------------------------- баллистика ------------------------------
local function simulate(pitch, prof, horiz)
  local vx, vy = prof.speed * math.cos(pitch), prof.speed * math.sin(pitch)
  local x, y, k = 0, 0, 1 - prof.drag
  for t = 1, prof.maxTicks do
    local nx, ny = x + vx, y + vy
    if nx >= horiz then
      local f = (horiz - x) / (nx - x)
      return y + (ny - y) * f, t - 1 + f
    end
    x, y = nx, ny
    vx, vy = vx * k, vy * k - prof.gravity
    if vx < 1e-3 then return nil end
  end
end

local function solvePitch(horiz, dy, prof, hint)
  local function hgt(a) return simulate(a, prof, horiz) or -1e9 end
  local aMin, aMax = rad(cfg.aim.minPitch), rad(cfg.aim.maxPitch)
  local lo, hi
  if hint then                                   -- быстрый путь: рядом с прошлым решением
    local a1, a2 = max(aMin, hint - rad(3)), min(aMax, hint + rad(3))
    if hgt(a1) < dy and hgt(a2) >= dy then lo, hi = a1, a2 end
  end
  if not lo then
    if hgt(aMin) >= dy then return nil end
    local step, prev, a = rad(4), aMin, aMin + rad(4)
    while a <= aMax + 1e-9 do
      if hgt(a) >= dy then lo, hi = prev, a; break end
      prev, a = a, a + step
    end
    if not lo then return nil end                 -- вне досягаемости
  end
  for _ = 1, 12 do
    local mid = (lo + hi) / 2
    if hgt(mid) >= dy then hi = mid else lo = mid end
  end
  local _, tf = simulate(hi, prof, horiz)
  return hi, tf
end

local function computeAim(c, t, tnow)
  local prof = c.prof
  local ah = (t.kind == "ship") and 0 or cfg.aimHeight
  local lag = (tnow - t.last + cfg.aim.latency) * 20
  local hint = (c.solKey == t.key) and c.solPitch or nil
  local ttf, pitch, yaw, aimP, horiz = 0
  for _ = 1, cfg.aim.leadIter do
    local lt = ttf + lag
    aimP = {x = t.pos.x + t.vel.x * lt, y = t.pos.y + ah + t.vel.y * lt, z = t.pos.z + t.vel.z * lt}
    local dx, dy, dz = aimP.x - c.pos.x, aimP.y - c.pos.y, aimP.z - c.pos.z
    horiz = sqrt(dx * dx + dz * dz)
    if horiz < cfg.minRange or horiz > prof.maxRange then return nil end
    local p, tf = solvePitch(horiz, dy, prof, hint)
    if not p then return nil end
    hint, pitch, ttf = p, p, tf
    yaw = deg(atan2(-dx, dz))                     -- yaw как в Minecraft (0 = юг)
  end
  c.solKey, c.solPitch = t.key, pitch
  return {yaw = yaw, pitch = deg(pitch), ttf = ttf, aimP = aimP, horiz = horiz}
end

-- ------------------------ безопасность огня --------------------------
local function lineBlocked(c, aimP)
  local ax, ay, az = aimP.x - c.pos.x, aimP.y - c.pos.y, aimP.z - c.pos.z
  local ad = sqrt(ax * ax + ay * ay + az * az)
  local tnow = now()
  local function ownVessel(p)                     -- свой же корабль, на котором стоит пушка
    for _, f in pairs(friends) do
      if tnow - f.time <= cfg.iff.timeout and dist3(f.pos, c.pos) <= f.radius
         and dist3(f.pos, p) <= f.radius then return true end
    end
    return false
  end
  local function check(p, r)
    if ownVessel(p) then return false end
    local fx, fy, fz = p.x - c.pos.x, p.y - c.pos.y, p.z - c.pos.z
    local fd = sqrt(fx * fx + fy * fy + fz * fz)
    if fd < 1 or fd > ad + r then return false end
    local cosA = (ax * fx + ay * fy + az * fz) / (ad * fd)
    local lim = rad(cfg.aim.safetyCone) + atan2(r, fd)
    return cosA > math.cos(lim)
  end
  for _, f in pairs(friends) do
    if tnow - f.time <= cfg.iff.timeout and check(f.pos, f.radius) then return true end
  end
  for _, t in pairs(tracks) do
    if t.friendly and check(t.pos, 1.5) then return true end
  end
  return false
end

-- ----------------------------- пушки ---------------------------------
local function rsSet(c, on)
  local r = c.db.redstone
  if type(r) ~= "table" or not r.side then return end
  if r.relay then pcall(peripheral.call, r.relay, "setOutput", r.side, on)
  else pcall(redstone.setOutput, r.side, on) end
  c.rsOn = on
end

local function trigger(c, on, tnow)
  if c.m.setFiring then
    if c.firing ~= on then call(c.name, c.m.setFiring, on); c.firing = on end
    return
  end
  if not on then
    if c.rsOn then rsSet(c, false) end
    return
  end
  if tnow - c.lastFire < c.prof.reload then
    if c.rsOn and c.prof.mode == "pulse" and tnow - c.lastFire >= 0.1 then rsSet(c, false) end
    return
  end
  c.lastFire = tnow
  if c.m.fire then call(c.name, c.m.fire)
  elseif not c.rsOn then rsSet(c, true) end
end

local function command(c, yaw, pitch, tnow)
  local dY = c.cmdYaw and abs(wrap180(yaw - c.cmdYaw)) or 180
  local dP = c.cmdPitch and abs(pitch - c.cmdPitch) or 90
  if dY > cfg.aim.deadband or dP > cfg.aim.deadband then
    parallel.waitForAll(
      function() call(c.name, c.m.setYaw, yaw) end,
      function() call(c.name, c.m.setPitch, pitch) end)
    c.cmdYaw, c.cmdPitch = yaw, pitch
    c.settleUntil = max(c.settleUntil, tnow + max(dY, dP) / cfg.aim.settleDegPerSec)
  end
end

local function onTarget(c, yaw, pitch, tol, tnow)
  if c.m.getYaw and c.m.getPitch then
    local y, p
    parallel.waitForAll(
      function() local ok, v = call(c.name, c.m.getYaw); if ok then y = v end end,
      function() local ok, v = call(c.name, c.m.getPitch); if ok then p = v end end)
    if type(y) == "number" and type(p) == "number" then
      return abs(wrap180(y - yaw)) <= tol and abs(p - pitch) <= tol
    end
  end
  return tnow >= c.settleUntil and abs(wrap180(c.cmdYaw - yaw)) <= tol and abs(c.cmdPitch - pitch) <= tol
end

local function control(c, tnow, active)
  if c.posGuess then c.pos = {x = base.x, y = base.y, z = base.z} end
  if c.m.assemble and c.m.running and tnow - c.lastAssemble > 5 then
    c.lastAssemble = tnow
    local ok, r = call(c.name, c.m.running)
    if ok and r == false then call(c.name, c.m.assemble) end
  end
  -- приоритет: ближайшая к пушке цель, на которую есть решение
  local cand = {}
  for _, t in ipairs(active) do cand[#cand + 1] = {t = t, d = dist3(t.pos, c.pos)} end
  table.sort(cand, function(a, b) return a.d < b.d end)
  local sol, tgt
  for i = 1, min(#cand, 3) do
    sol = computeAim(c, cand[i].t, tnow)
    if sol then tgt = cand[i].t; break end
  end
  if not sol then
    c.target = nil
    c.state = (#cand > 0) and "NO SOLUTION" or "IDLE"
    trigger(c, false, tnow)
    return
  end
  c.target = tgt.key
  local yawOff = c.db.yawOffset or cfg.yawOffset
  local cy = wrap180((sol.yaw - yawOff) * cfg.yawDir)
  local cp = sol.pitch * cfg.pitchDir
  command(c, cy, cp, tnow)
  -- допуск: не меньше угловых размеров цели -> нет бесконечного доводения
  local hr = cfg.aim.hitRadius[tgt.kind] or 1
  local tol = max(cfg.aim.tolerance, deg(atan2(hr, max(1, sol.horiz))))
  local ready = onTarget(c, cy, cp, tol, tnow)
  local blocked = ready and lineBlocked(c, sol.aimP)
  local fire = ready and not blocked and not cfg.holdFire
  trigger(c, fire, tnow)
  c.state = blocked and "BLOCKED" or (fire and "FIRE") or (cfg.holdFire and ready and "HOLD") or "AIMING"
end

-- ----------------------------- экран ---------------------------------
local COL = {entity = colors.red, player = colors.magenta, ship = colors.orange}
local SYM = {entity = "m", player = "P", ship = "#"}
local STC = {FIRE = colors.red, AIMING = colors.yellow, BLOCKED = colors.purple,
             HOLD = colors.orange, IDLE = colors.gray, ["NO SOLUTION"] = colors.lightGray}

local function render(dev, active)
  local w, h = dev.getSize()
  local win = window.create(dev, 1, 1, w, h, false)
  win.setBackgroundColor(colors.black); win.clear()
  local engaged, nC = {}, 0
  for _, c in pairs(cannons) do nC = nC + 1; if c.target then engaged[c.target] = true end end

  local panelW = (w >= 46) and 24 or 0
  local mapW, mapH = w - panelW, (panelW > 0) and h or h - 1
  local cx, cy = floor(mapW / 2) + 1, floor(mapH / 2) + 1
  local scale = cfg.scanRadius / max(1, min(mapH / 2 - 1, (mapW / 2 - 1) / 1.5))
  local function plot(dx, dz, ch, fg, bg)
    local col = cx + floor(dx / scale * 1.5 + 0.5)
    local row = cy + floor(dz / scale + 0.5)
    if col >= 1 and col <= mapW and row >= 1 and row <= mapH then
      win.setCursorPos(col, row)
      win.setBackgroundColor(bg or colors.black); win.setTextColor(fg); win.write(ch)
    end
  end
  for _, rr in ipairs({cfg.scanRadius, cfg.scanRadius / 2}) do
    for i = 0, 71 do
      local a = i / 72 * 2 * math.pi
      plot(math.cos(a) * rr, math.sin(a) * rr, ".", colors.gray)
    end
  end
  plot(0, -cfg.scanRadius, "N", colors.lightGray)
  plot(0, 0, "+", colors.white)
  for _, c in pairs(cannons) do
    if not c.posGuess then plot(c.pos.x - base.x, c.pos.z - base.z, "o", colors.lightBlue) end
  end
  for i = #active, 1, -1 do                         -- ближние рисуются поверх
    local t = active[i]
    local e = engaged[t.key]
    plot(t.pos.x - base.x, t.pos.z - base.z, e and "X" or SYM[t.kind] or "?",
         e and colors.yellow or COL[t.kind] or colors.white, e and colors.red or nil)
  end

  local function line(y, text, fg)
    if y >= 1 and y <= h then
      win.setCursorPos(mapW + 2, y); win.setBackgroundColor(colors.black)
      win.setTextColor(fg or colors.white); win.write(text:sub(1, panelW - 1))
    end
  end
  if panelW > 0 then
    line(1, "RADAR v" .. VERSION, colors.cyan)
    line(2, ("Targets:%d R:%d"):format(#active, cfg.scanRadius))
    line(3, cfg.holdFire and "FIRE: HOLD" or "FIRE: FREE", cfg.holdFire and colors.orange or colors.lime)
    line(4, ("Guns:%d Scan:%d IFF:%d"):format(nC, #radars, (function()
      local n = 0; for _ in pairs(friends) do n = n + 1 end; return n end)()), colors.lightGray)
    line(6, "TARGET     DIST  SPD", colors.lightGray)
    local y, limit = 7, h - nC - 1
    for _, t in ipairs(active) do
      if y > limit then break end
      line(y, ("%-10s%5d%5.1f"):format(t.name:sub(1, 10), floor(t.dist + 0.5), t.speed),
           engaged[t.key] and colors.yellow or COL[t.kind])
      y = y + 1
    end
    y = max(y + 1, h - nC + 1)
    for name, c in pairs(cannons) do
      line(y, ("%-11s %s"):format(name:sub(-11), c.state or "-"), STC[c.state] or colors.white)
      y = y + 1
    end
  else
    win.setCursorPos(1, h); win.setTextColor(colors.white)
    win.write(("T:%d G:%d %s"):format(#active, nC, cfg.holdFire and "HOLD" or "FREE"))
  end
  win.setVisible(true)
end

-- --------------------------- главный цикл ----------------------------
local function mainLoop()
  local lastGps = 0
  while true do
    if needRescan then rescan() end
    frame = frame + 1
    if cfg.mobileBase and now() - lastGps > 1 then lastGps = now(); locateBase() end
    scanAll()
    local tnow = now()
    classify(tnow)
    local active = {}
    for _, t in pairs(tracks) do if t.active then active[#active + 1] = t end end
    table.sort(active, function(a, b) return a.dist < b.dist end)
    local tasks = {}
    for _, c in pairs(cannons) do tasks[#tasks + 1] = function() control(c, tnow, active) end end
    if #tasks > 0 then parallel.waitForAll(table.unpack(tasks)) end
    render(term.current(), active)
    for _, mn in ipairs(monitors) do
      local mon = peripheral.wrap(mn)
      if mon then pcall(render, mon, active) end
    end
    sleep(cfg.cycleDelay)
  end
end

local function inputLoop()
  while true do
    local _, ch = os.pullEvent("char")
    ch = ch:lower()
    if ch == "q" then return
    elseif ch == "h" then cfg.holdFire = not cfg.holdFire
    elseif ch == "r" then needRescan = true
    elseif ch == "c" then tracks = {} end
  end
end

local function periphLoop()
  while true do
    local ev = os.pullEvent()
    if ev == "peripheral" or ev == "peripheral_detach" then needRescan = true end
  end
end

local ok, err = pcall(parallel.waitForAny, mainLoop, inputLoop, periphLoop, iffLoop)
for _, c in pairs(cannons) do pcall(trigger, c, false, now()) end
term.setBackgroundColor(colors.black); term.setTextColor(colors.white)
term.clear(); term.setCursorPos(1, 1)
if not ok then printError(err) else print("Radar stopped.") end
