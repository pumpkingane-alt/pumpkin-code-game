package com.pumpkinpanic.coop;

/** Example scripts shown in the editor (EXAMPLES). Generated from plain .py files. */
final class ScriptTemplates {
    private ScriptTemplates() {}

    static final String[] IDS = {"hello_farm", "smart_farmer", "survival", "full_auto", "fisher", "crew_helper", "api_tour", "blank"};
    static final String[] TITLES = {"Hello farm", "Smart farmer", "Survival guard", "Full auto", "Fisher", "Crew helper", "API tour", "Blank"};
    static final String[] INFO = {"Plant, water, harvest and sell: the first script", "Best crop for your plots, shopping plan, upgrades", "A second thread that hides from night and monsters", "Plays the whole game, builds the boat, sails (endless ready)", "Fish at the dock and sell the catch", "Revive friends and cheer up clowns", "Prints everything you can read from the game", "An empty script with a few ideas"};

    static final String[] CODE = {
            "# HELLO FARM - your first script\n" +
            "# The character can only be controlled by code. Every call below walks\n" +
            "# there by itself (path finding) and waits until the job is done.\n" +
            "#\n" +
            "# Press RUN (or turn on \"auto-run\") and watch. Errors show up in red\n" +
            "# in the console with the line number and what went wrong.\n" +
            "\n" +
            "print(\"Hello! I have\", coins(), \"coins and\", seeds(Crop.Turnip), \"turnip seeds\")\n" +
            "\n" +
            "while True:\n" +
            "    # 1. plant a seed on every empty tile we have seeds for\n" +
            "    for t in tiles():\n" +
            "        if t.empty and seeds(Crop.Turnip) > 0:\n" +
            "            plant(Crop.Turnip, t)\n" +
            "\n" +
            "    # 2. water every plant that is getting dry (plants only grow while wet!)\n" +
            "    for t in tiles():\n" +
            "        if t.growing and t.water < 0.5:\n" +
            "            if water_left() < 1:\n" +
            "                refill()              # walks to the well\n" +
            "            water(t)\n" +
            "\n" +
            "    # 3. pick everything that is ripe\n" +
            "    for t in tiles():\n" +
            "        if t.ready:\n" +
            "            harvest(t)\n" +
            "\n" +
            "    # 4. turn the harvest into coins, then buy new seeds\n" +
            "    if carry_value() > 0:\n" +
            "        sell()\n" +
            "    want = 9 - seeds(Crop.Turnip)               # 9 tiles in a plot\n" +
            "    if want > 0 and coins() >= price(Crop.Turnip):\n" +
            "        buy_seeds(Crop.Turnip, min(want, coins() // price(Crop.Turnip)))\n" +
            "\n" +
            "    wait(1)                           # always wait in a loop, or the script hogs the CPU\n",

            "# SMART FARMER - grows the best crop you can, buys upgrades by itself.\n" +
            "# No night protection here: add survival.py (see the \"Survival\" example)\n" +
            "# or write your own guard.\n" +
            "\n" +
            "CROPS_BY_PLOTS = [Crop.Turnip, Crop.Carrot, Crop.Corn, Crop.Pumpkin, Crop.Moonbloom]\n" +
            "\n" +
            "def best_crop():\n" +
            "    # more unlocked plots = better crops allowed\n" +
            "    return CROPS_BY_PLOTS[plots() - 1]\n" +
            "\n" +
            "# what to buy, in this order (the first one you do not have yet)\n" +
            "def next_goal():\n" +
            "    if level(Item.WateringCan) < 1:\n" +
            "        return Item.WateringCan\n" +
            "    if plots() < 2:\n" +
            "        return Item.PlotKey\n" +
            "    if level(Item.OilTank) < 1:\n" +
            "        return Item.OilTank\n" +
            "    if plots() < 3:\n" +
            "        return Item.PlotKey\n" +
            "    if level(Item.WateringCan) < 2:\n" +
            "        return Item.WateringCan\n" +
            "    if plots() < 4:\n" +
            "        return Item.PlotKey\n" +
            "    return None\n" +
            "\n" +
            "def shop():\n" +
            "    goal = next_goal()\n" +
            "    if goal != None and can_afford(goal):\n" +
            "        print(\"buying\", goal)\n" +
            "        buy(goal)\n" +
            "\n" +
            "def farm_once():\n" +
            "    crop = best_crop()\n" +
            "    for t in tiles():\n" +
            "        if t.ready:\n" +
            "            harvest(t)\n" +
            "    if carry_value() >= 30:\n" +
            "        sell()\n" +
            "    empty = [t for t in tiles() if t.empty]\n" +
            "    missing = len(empty) - seeds(crop)\n" +
            "    if missing > 0:\n" +
            "        n = min(missing, coins() // price(crop))\n" +
            "        if n > 0:\n" +
            "            buy_seeds(crop, n)\n" +
            "    for t in tiles():\n" +
            "        if t.empty and seeds(crop) > 0:\n" +
            "            plant(crop, t)\n" +
            "    for t in tiles():\n" +
            "        if t.growing and t.water < 0.5:\n" +
            "            if water_left() < 1:\n" +
            "                refill()\n" +
            "            water(t)\n" +
            "\n" +
            "while True:\n" +
            "    farm_once()\n" +
            "    shop()\n" +
            "    wait(1)\n",

            "# SURVIVAL - a guard that runs next to your farming code.\n" +
            "#\n" +
            "# spawn(guard, priority=5) starts guard() as a second thread. A thread with\n" +
            "# a higher priority gets the character first, so when the guard calls\n" +
            "# hide() the farming thread simply waits until it is safe again.\n" +
            "#\n" +
            "# Use it on its own, or copy it into your own script. (You can also save it\n" +
            "# as survival.py and write  from survival import guard  in main.py.)\n" +
            "\n" +
            "def unsafe():\n" +
            "    if darkness() > 0.65:                 # <-- tweak: hide earlier / later\n" +
            "        return True\n" +
            "    for m in monsters():\n" +
            "        if m.kind in (\"skinwalker\", \"stag\") and m.hostile:\n" +
            "            return True\n" +
            "    return False\n" +
            "\n" +
            "def run_from(m):\n" +
            "    sprint(True)\n" +
            "    px, py = pos()\n" +
            "    goto(clamp(px + (px - m.x) * 2, 700, 1900), clamp(py + (py - m.y) * 2, 450, 1250))\n" +
            "    sprint(False)\n" +
            "\n" +
            "def guard():\n" +
            "    while True:\n" +
            "        for m in monsters():\n" +
            "            if m.waiting:                  # a clown waits for a cheer\n" +
            "                cheer()\n" +
            "            elif m.kind == \"clown\" and m.hostile and m.dist < 320:\n" +
            "                run_from(m)                # an angry blue clown!\n" +
            "        if unsafe():\n" +
            "            while unsafe():\n" +
            "                # the demon is scared of the lantern\n" +
            "                demon = [m for m in monsters() if m.kind == \"demon\" and m.hostile]\n" +
            "                lantern(len(demon) > 0 and oil_left() > 5)\n" +
            "                hide(1)                    # stay in the cabin for a second\n" +
            "            lantern(False)\n" +
            "        wait(0.25)\n" +
            "\n" +
            "# ---- demo: start the guard and do something simple in the main thread\n" +
            "spawn(guard, priority=5)\n" +
            "while True:\n" +
            "    for t in tiles():\n" +
            "        if t.empty and seeds(Crop.Turnip) > 0:\n" +
            "            plant(Crop.Turnip, t)\n" +
            "        elif t.ready:\n" +
            "            harvest(t)\n" +
            "        elif t.growing and t.water < 0.5 and water_left() >= 1:\n" +
            "            water(t)\n" +
            "    if water_left() < 1:\n" +
            "        refill()\n" +
            "    if carry_value() >= 20:\n" +
            "        sell()\n" +
            "    wait(1)\n",

            "# ===== FULL AUTO =====\n" +
            "# Farms, sells, upgrades, builds the boat and sails away - again and again in\n" +
            "# endless mode (everything restarts on the next island).  Read it top to bottom,\n" +
            "# change anything you like (lines marked  <-- tweak  are good places to start).\n" +
            "\n" +
            "CROPS_BY_PLOTS = [Crop.Turnip, Crop.Carrot, Crop.Corn, Crop.Pumpkin, Crop.Moonbloom]\n" +
            "\n" +
            "def best_crop():\n" +
            "    return CROPS_BY_PLOTS[plots() - 1]\n" +
            "\n" +
            "# ---------------------------------------------------------------- shopping plan\n" +
            "def next_goal():\n" +
            "    if level(Item.WateringCan) < 1:\n" +
            "        return Item.WateringCan\n" +
            "    if plots() < 2:\n" +
            "        return Item.PlotKey\n" +
            "    if not owned(Item.Saw):\n" +
            "        return Item.Saw\n" +
            "    if not owned(Item.Hull):\n" +
            "        return Item.Hull\n" +
            "    if plots() < 3:\n" +
            "        return Item.PlotKey\n" +
            "    if not owned(Item.Propeller):\n" +
            "        return Item.Propeller\n" +
            "    if not owned(Item.FuelCan):\n" +
            "        return Item.FuelCan\n" +
            "    if not owned(Item.Engine):\n" +
            "        return Item.Engine\n" +
            "    return None\n" +
            "\n" +
            "def spend():\n" +
            "    goal = next_goal()\n" +
            "    if goal != None and can_afford(goal):\n" +
            "        print(\"buying\", goal)\n" +
            "        buy(goal)\n" +
            "\n" +
            "# ---------------------------------------------------------------- survival (runs in parallel, beats farming)\n" +
            "def unsafe():\n" +
            "    if darkness() > 0.65:          # <-- tweak: hide earlier / later\n" +
            "        return True\n" +
            "    for m in monsters():\n" +
            "        if m.kind in (\"skinwalker\", \"stag\") and m.hostile:\n" +
            "            return True\n" +
            "    return False\n" +
            "\n" +
            "def run_from(m):\n" +
            "    sprint(True)\n" +
            "    px, py = pos()\n" +
            "    goto(clamp(px + (px - m.x) * 2, 700, 1900), clamp(py + (py - m.y) * 2, 450, 1250))\n" +
            "    sprint(False)\n" +
            "\n" +
            "def guard():\n" +
            "    while True:\n" +
            "        for m in monsters():\n" +
            "            if m.waiting:\n" +
            "                cheer()\n" +
            "            elif m.kind == \"clown\" and m.hostile and m.dist < 320:\n" +
            "                run_from(m)\n" +
            "        if unsafe():\n" +
            "            while unsafe():\n" +
            "                demon = [m for m in monsters() if m.kind == \"demon\" and m.hostile]\n" +
            "                lantern(len(demon) > 0 and oil_left() > 5)\n" +
            "                hide(1)\n" +
            "                spend()            # we are inside anyway: do some shopping\n" +
            "            lantern(False)\n" +
            "        wait(0.25)\n" +
            "\n" +
            "# ---------------------------------------------------------------- farming\n" +
            "def farm():\n" +
            "    crop = best_crop()\n" +
            "    for t in tiles():\n" +
            "        if t.ready:\n" +
            "            harvest(t)\n" +
            "    if carry_value() >= 30:\n" +
            "        sell()\n" +
            "    empty = [t for t in tiles() if t.empty]\n" +
            "    if len(empty) > seeds(crop):\n" +
            "        n = min(len(empty) - seeds(crop), coins() // price(crop))\n" +
            "        if n > 0:\n" +
            "            buy_seeds(crop, n)\n" +
            "    for t in tiles():\n" +
            "        if t.empty and seeds(crop) > 0:\n" +
            "            plant(crop, t)\n" +
            "    for t in tiles():\n" +
            "        if t.growing and t.water < 0.5:\n" +
            "            if water_left() < 1:\n" +
            "                refill()\n" +
            "            water(t)\n" +
            "\n" +
            "def main():\n" +
            "    spawn(guard, priority=5)\n" +
            "    while True:\n" +
            "        if boat_ready():\n" +
            "            print(\"sailing away!\")\n" +
            "            sail()                 # classic mode: you win. endless mode: next island, harder!\n" +
            "            wait(2)\n" +
            "            continue\n" +
            "        farm()\n" +
            "        goal = next_goal()\n" +
            "        if goal == Item.Hull and owned(Item.Saw) and wood() < 8:\n" +
            "            chop(8 - wood())\n" +
            "        spend()\n" +
            "        if len(coins_on_ground()) > 0:\n" +
            "            collect_coins()\n" +
            "        wait(1)\n" +
            "\n" +
            "main()\n",

            "# FISHER - fish at the dock and sell the catch.\n" +
            "# fish() reels in bites for you and raises the lantern when something\n" +
            "# creepy bites at night. It returns how many fish it caught.\n" +
            "\n" +
            "while True:\n" +
            "    if is_night() or threat():\n" +
            "        hide(2)                    # not safe out there\n" +
            "        continue\n" +
            "    caught = fish(3)               # go to the dock, catch 3 fish\n" +
            "    print(\"caught\", caught, \"fish, worth\", carry_value(), \"coins\")\n" +
            "    sell()\n" +
            "    if coins() >= 60 and not owned(Item.Saw):\n" +
            "        buy(Item.Saw)\n" +
            "    wait(0.5)\n",

            "# CREW HELPER - looks after your friends and the clowns.\n" +
            "# Handy when you play together: each player can run their own script.\n" +
            "# (emote(4) shows a \"Thanks\" bubble)\n" +
            "\n" +
            "def help_crew():\n" +
            "    for p in crew():\n" +
            "        if p.down and not p.me:\n" +
            "            print(\"reviving\", p.name)\n" +
            "            revive(p)\n" +
            "\n" +
            "while True:\n" +
            "    help_crew()\n" +
            "    for m in monsters():\n" +
            "        if m.waiting:\n" +
            "            print(\"cheering up the clown\")\n" +
            "            cheer()\n" +
            "    wait(0.5)\n",

            "# API TOUR - run it and read the console. Great for learning what exists.\n" +
            "# Tip: help(plant) shows how a function works, dir(obj) lists attributes.\n" +
            "\n" +
            "print(\"== you ==\")\n" +
            "print(\"position\", pos(), \"inside cabin:\", inside())\n" +
            "print(\"coins\", coins(), \"wood\", wood(), \"lives\", lives())\n" +
            "print(\"water\", water_left(), \"/\", water_max(), \"  oil\", oil_left(), \"/\", oil_max())\n" +
            "print(\"inventory\", inventory())\n" +
            "\n" +
            "print(\"== time ==\")\n" +
            "print(\"day\", day(), daytime(), \"darkness\", round(darkness(), 2), \"night in\", round(time_to_night()), \"s\")\n" +
            "print(\"island\", island(), \"danger x\", danger(), \"endless:\", is_endless())\n" +
            "\n" +
            "print(\"== farm ==\")\n" +
            "for t in tiles():\n" +
            "    print(t)                            # a tile knows its crop, growth and water\n" +
            "print(\"prices: saw\", price(Item.Saw), \" plot key\", price(Item.PlotKey), \" corn seed\", price(Crop.Corn))\n" +
            "\n" +
            "print(\"== boat ==\")\n" +
            "print(\"parts built\", boat_parts(), \"of 4; still needed:\", missing_parts())\n" +
            "\n" +
            "print(\"== crew and monsters ==\")\n" +
            "for p in crew():\n" +
            "    print(p.name, p.state, \"at\", int(p.x), int(p.y))\n" +
            "print(\"monsters out:\", monsters())\n" +
            "\n" +
            "print(\"== a taste of the language ==\")\n" +
            "squares = [n * n for n in range(1, 6)]\n" +
            "print(squares, sum(squares), f\"{3.14159:.2f}\")\n" +
            "scores = {\"turnip\": 9, \"pumpkin\": 66}\n" +
            "for name, value in scores.items():\n" +
            "    print(name, \"sells for\", value)\n" +
            "\n" +
            "help()                                   # all functions by category\n",

            "# Your script. A few ideas to start:\n" +
            "#   plant(Crop.Turnip)      walks to empty soil and plants\n" +
            "#   water()                 waters the nearest dry plant\n" +
            "#   harvest()               picks the nearest ripe crop\n" +
            "#   sell()                  sells everything you carry\n" +
            "#   buy(Item.Saw)           shopping - the cabin door opens by itself\n" +
            "#   help()                  lists every function\n" +
            "# Everything is code: loops, functions, lists, threads (spawn) ...\n" +
            "\n" +
            "while True:\n" +
            "    wait(1)\n"

    };

    /** The script a new player starts with. */
    static String starter() { return CODE[0]; }
}
