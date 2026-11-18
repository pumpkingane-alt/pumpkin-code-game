# SMART FARMER - grows the best crop you can, buys upgrades by itself.
# No night protection here: add survival.py (see the "Survival" example)
# or write your own guard.

CROPS_BY_PLOTS = [Crop.Turnip, Crop.Carrot, Crop.Corn, Crop.Pumpkin, Crop.Moonbloom]

def best_crop():
    # more unlocked plots = better crops allowed
    return CROPS_BY_PLOTS[plots() - 1]

# what to buy, in this order (the first one you do not have yet)
def next_goal():
    if level(Item.WateringCan) < 1:
        return Item.WateringCan
    if plots() < 2:
        return Item.PlotKey
    if level(Item.OilTank) < 1:
        return Item.OilTank
    if plots() < 3:
        return Item.PlotKey
    if level(Item.WateringCan) < 2:
        return Item.WateringCan
    if plots() < 4:
        return Item.PlotKey
    return None

def shop():
    goal = next_goal()
    if goal != None and can_afford(goal):
        print("buying", goal)
        buy(goal)

def farm_once():
    crop = best_crop()
    for t in tiles():
        if t.ready:
            harvest(t)
    if carry_value() >= 30:
        sell()
    empty = [t for t in tiles() if t.empty]
    missing = len(empty) - seeds(crop)
    if missing > 0:
        n = min(missing, coins() // price(crop))
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

while True:
    farm_once()
    shop()
    wait(1)
