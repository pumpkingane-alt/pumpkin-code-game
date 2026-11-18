# ===== FULL AUTO =====
# Farms, sells, upgrades, builds the boat and sails away - again and again in
# endless mode (everything restarts on the next island).  Read it top to bottom,
# change anything you like (lines marked  <-- tweak  are good places to start).

CROPS_BY_PLOTS = [Crop.Turnip, Crop.Carrot, Crop.Corn, Crop.Pumpkin, Crop.Moonbloom]

def best_crop():
    return CROPS_BY_PLOTS[plots() - 1]

# ---------------------------------------------------------------- shopping plan
def next_goal():
    if level(Item.WateringCan) < 1:
        return Item.WateringCan
    if plots() < 2:
        return Item.PlotKey
    if not owned(Item.Saw):
        return Item.Saw
    if not owned(Item.Hull):
        return Item.Hull
    if plots() < 3:
        return Item.PlotKey
    if not owned(Item.Propeller):
        return Item.Propeller
    if not owned(Item.FuelCan):
        return Item.FuelCan
    if not owned(Item.Engine):
        return Item.Engine
    return None

def spend():
    goal = next_goal()
    if goal != None and can_afford(goal):
        print("buying", goal)
        buy(goal)

# ---------------------------------------------------------------- survival (runs in parallel, beats farming)
def unsafe():
    if darkness() > 0.65:          # <-- tweak: hide earlier / later
        return True
    for m in monsters():
        if m.kind in ("skinwalker", "stag") and m.hostile:
            return True
    return False

def run_from(m):
    sprint(True)
    px, py = pos()
    goto(clamp(px + (px - m.x) * 2, 700, 1900), clamp(py + (py - m.y) * 2, 450, 1250))
    sprint(False)

def guard():
    while True:
        for m in monsters():
            if m.waiting:
                cheer()
            elif m.kind == "clown" and m.hostile and m.dist < 320:
                run_from(m)
        if unsafe():
            while unsafe():
                demon = [m for m in monsters() if m.kind == "demon" and m.hostile]
                lantern(len(demon) > 0 and oil_left() > 5)
                hide(1)
                spend()            # we are inside anyway: do some shopping
            lantern(False)
        wait(0.25)

# ---------------------------------------------------------------- farming
def farm():
    crop = best_crop()
    for t in tiles():
        if t.ready:
            harvest(t)
    if carry_value() >= 30:
        sell()
    empty = [t for t in tiles() if t.empty]
    if len(empty) > seeds(crop):
        n = min(len(empty) - seeds(crop), coins() // price(crop))
        if n > 0:
            buy_seeds(crop, n)
    for t in tiles():
        if t.empty and seeds(crop) > 0:
            plant(crop, t)
    for t in tiles():
        if t.growing and t.water < 0.5:
            if water_left() < 1:
                refill()
            water(t)

def main():
    spawn(guard, priority=5)
    while True:
        if boat_ready():
            print("sailing away!")
            sail()                 # classic mode: you win. endless mode: next island, harder!
            wait(2)
            continue
        farm()
        goal = next_goal()
        if goal == Item.Hull and owned(Item.Saw) and wood() < 8:
            chop(8 - wood())
        spend()
        if len(coins_on_ground()) > 0:
            collect_coins()
        wait(1)

main()
