PUMPKIN PANIC  -  4-player LAN co-op farm horror   (for AIDE, plain Java, no libraries)
=================================================================================

WHAT IT IS
  Farm pumpkins, survive the nights, repair the boat, escape the island - together.
  Systems: farming (plant/water/harvest/sell), lantern + oil, water can, day/night,
  crow, workbench + closet shop, fishing, saw + logs, boat repair, plus the monsters:
  Skinwalker (hide in the cabin, candles blow out), Shadow Demon (under the bed),
  Clowns (follow the carnival music; cheer the red one or the blue one hunts you),
  Stag -> Wendigo (raise your lantern at it!). All art is drawn in code and all music
  and sound effects are synthesised in code - there are no asset files.

INSTALL IN AIDE  (pick one)
  A) AIDE -> New project -> Android App. Package name: com.pumpkinpanic.coop
     Copy ALL .java files from src/com/pumpkinpanic/coop/ (game + addon system + scripting, 40+ files) over the
     project's package folder, copy res/layout/addon_manager.xml and res/values/strings.xml,
     copy libs/luaj-jse-3.0.2.jar into the project's libs/ folder, and replace AndroidManifest.xml.
  B) Copy the whole PumpkinPanic_AIDE folder into /AppProjects, then open it from
     AIDE's "Open project" screen and press Run. (libs/luaj-jse-3.0.2.jar is already inside.)
  Needs Android 4.1+ (API 16). Landscape only. Overwrite ALL old .java files (many changed). One library: Luaj (libs/luaj-jse-3.0.2.jar, MIT). Java 7 syntax.

PLAY WITH 4 DEVICES (LAN)
  1. Put every device on the SAME Wi-Fi, or turn on one phone's hotspot and connect the others to it.
  2. Device 1: "Host co-op". The lobby shows the host IP. (Add bots with "Add bot" if you like.)
  3. Devices 2-4: "Join co-op". The host appears in the list within a few seconds - tap it.
     If the list stays empty: tap "Enter IP address" and type the IP shown on the host.
  4. Host taps START. People can also join a game that is already running.
  Ports: TCP 47777 (game) and UDP 47778 (discovery). Guest Wi-Fi / "client isolation"
  routers block device-to-device traffic - use a phone hotspot in that case.
  The host phone runs the simulation, so it should stay in the foreground.
  Over the internet: use any VPN/LAN tool (ZeroTier, Radmin, Tailscale) and type that IP.

CONTROLS: CODE ONLY  (there are no manual controls any more)
  The character is moved and used ONLY by a script you write in the in-game code editor.
  Title screen / in game: tap CODE (top right). Write the script, tap RUN. STOP ends it.
  Every action call (goto, plant, water, harvest, sell, buy, chop, fish, cheer, revive, sail ...)
  walks to the right place by itself (A* path finding) and waits until it is done.
    plant(Crop.Turnip)   water()   harvest()   sell()   buy(Item.Saw)   hide()   lantern(True)
    for t in tiles(): if t.ready: harvest(t)
  Language: Python style - variables, if/while/for, functions, lambdas, lists, dicts, sets, f-strings,
  comprehensions, try/except, import of your other scripts, and spawn(fn, priority=..) for parallel
  threads (the higher priority thread gets the character first).
  Errors: the editor checks while you type (red = error, yellow = warning: misspelled names,
  wrong argument counts, forgotten parentheses, loops that never wait ...). At run time an error stops
  the script and shows the line, the error type and a hint in the console (bottom of the screen and
  in the editor). Failed actions only warn (set_strict(True) turns them into catchable ActionErrors).
  FILES / EXAMPLES / FUNCTIONS / HELP in the editor: many scripts, 8 ready-made examples (hello farm,
  smart farmer, survival guard, FULL AUTO that plays the whole game and keeps sailing in endless mode, ...),
  a function picker and the full reference. The same reference is in SCRIPTING.txt.
  Scripts are plain text files: /sdcard/PumpkinPanic/scripts/<name>.py (or the app folder if storage is
  blocked), so you can also edit them with any text editor. Pause menu: Auto-run on/off.
  The script keeps running across islands. The game is paused while you edit (solo only).
  LAN: every player runs their own script on their own device.

ENDLESS MODE  (lobby button "Mode: Endless", default)
  Finish the 4 boat parts and call sail(): instead of winning you wash ashore on the south shore of a
  fresh copy of the island - far from the safe cabin, at the start of a new morning. Each island is
  1.5x more dangerous than the last (monsters come 1.5x as often, move faster up to a 2.2x cap,
  and downed players bleed out sooner). You keep your gear (upgrades, saw, hats), seeds, carried goods,
  half of your coins and gain 1 life. The farm, boat parts, wood and plots start over.
  The run ends when all lives are gone: the card shows "Islands escaped". Classic mode = old behaviour.
  Numbers: Data.ENDLESS_STEP (1.5), ENDLESS_SPEED_CAP (2.2), ENDLESS_COIN_KEEP (0.5), ENDLESS_MIN_BLEED.
  Scripts can read island(), danger() and is_endless().

TUNING
  Data.java has every number: crop times/prices, upgrade costs, monster timers and speeds,
  difficulty tables (Normal / Hard / Nightmare), day length, map layout.

FILES
  Data      constants + map            World   simulation, shop, snapshot (de)serialising
  Mobs      monsters, crow, clowns, bot AI    Net     TCP host/client + UDP/subnet discovery (protocol 3)
  Sfx       synth + adaptive music            Art     all artwork (ground, props, characters)
  Hud       menus, world render, lighting, HUD GameView  input, LAN protocol, fx, audio mapping
  MainActivity  Android glue (also boots the addon system)
  Script*   the scripting system: ScriptLexer/Parser/Ast (language), ScriptInterp/Lib/Values (evaluator and
            built-ins), ScriptChecker (static errors + warnings), ScriptHost (script threads + console),
            ScriptPilot/Tasks (the character's hands), ScriptApi (game functions), ScriptStore (files),
            ScriptTemplates/Docs (examples + help).   CodeEditor  the in-game editor dialog.

ADDONS  (all in package com.pumpkinpanic.coop)
  Title screen -> "Addons": switch addons on/off. New addons start OFF. Changes apply when you close the menu.
  Folder: /sdcard/PumpkinPanic/addons/<anything>/   (if the phone blocks that, the Addons menu shows the
  fallback path: Android/data/com.pumpkinpanic.coop/files/addons/). Each addon folder contains:
    mod_info.json   {"id":"my_mod", "title":"..", "author":"..", "version":"1.0", "description":"..",
                     "entryScript":"optional.lua", "mainMapOverride":"mapName", "incompatible_with":["other_id"]}
    icon.png        shown in the menu
    scripts/*.lua   Lua scripts (sandboxed: no io/os/load). All files of one addon share one Lua state.
    textures/ or root *.png   sprite overrides      sounds/ or root *.ogg/wav/mp3   sound overrides
    maps/<name>.json|.tmx     Tiled maps (CSV or array tile data). Layers: "collision", "spawns", "triggers", "camera"
  Sprites: bottom-centre of the PNG sits on the entity's feet, 1 px = 1 world unit. A PNG at least twice as wide
  as tall, width a multiple of its height, is an animation strip of square frames (8 fps; crops: growth stages).
  Keys: player_0..player_3 (or player), mob_skinwalker, mob_demon, mob_clown, mob_clown_blue, mob_stag,
        mob_wendigo, mob_crow, crop_turnip/carrot/corn/pumpkin/moonbloom, prop_well, prop_sellbox, prop_barrel,
        prop_log, prop_tree, prop_jack, prop_boat, prop_coin, ground (whole 2600x1800 world), tileset (tile grid
        for maps, first tile = gid 1). Sounds: sfx_ + click, step, plant, water, harvest, coin, sell, buy, err,
        well, oil, door_open, door_close, candle_out, candle_lit, skin_arrive, skin_leave, skin_hit, demon, clown,
        clown_ok, clown_blue, stag, roar, scare, down, revive, respawn, caw, drop, cast, bite, catch, horror, saw,
        part, emote, dawn, dusk, win, lose, unlock, upgrade, tip, lamp  (e.g. sfx_harvest.ogg).
  Lua events (define a global function with the name):
    onUpdate(dt) onPlayerMove(x,y) onCropHarvest(tileX,tileY,crop) onCropWater(tileX,tileY)
    onMonsterSpawn(kind,x,y) onNightFall() onItemUse(item,x,y) onItemBuy(itemId,slot) onSceneEnter(sceneId)
    onAddonsReady(count)
  Lua functions: spawnMonster(kind,x,y)  [skinwalker, demon, clown, clown_blue, stag, wendigo]
    modifyPlayerStat(stat,n)  [coins, wood, lives = team;  oil, water, stamina = every player]
    createItem(id,name,price)  [buy it from a script: buy("id"); price("id"), can_afford("id") work too]   changeScene("addon:map")
    playSound(key,vol)  addTriggerZone(x,y,w,h,luaSourceOrPath)  log(text)
  Game-changing calls only act on the host / solo player, so LAN games stay in sync.
  LAN: the host sends its addon list when someone joins. A joiner missing an addon is asked "Download required
  files from Host?" (YES downloads it; NO goes back to the menu). Joiners run exactly the host's addon set until they
  leave; then their own choices come back. Net protocol version is now 2 (old builds cannot join new hosts).
  A sample addon is in sample_addon/pumpkin_bonus - copy that folder into the addons folder.
