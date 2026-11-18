# HELLO FARM - your first script
# The character can only be controlled by code. Every call below walks
# there by itself (path finding) and waits until the job is done.
#
# Press RUN (or turn on "auto-run") and watch. Errors show up in red
# in the console with the line number and what went wrong.

print("Hello! I have", coins(), "coins and", seeds(Crop.Turnip), "turnip seeds")

while True:
    # 1. plant a seed on every empty tile we have seeds for
    for t in tiles():
        if t.empty and seeds(Crop.Turnip) > 0:
            plant(Crop.Turnip, t)

    # 2. water every plant that is getting dry (plants only grow while wet!)
    for t in tiles():
        if t.growing and t.water < 0.5:
            if water_left() < 1:
                refill()              # walks to the well
            water(t)

    # 3. pick everything that is ripe
    for t in tiles():
        if t.ready:
            harvest(t)

    # 4. turn the harvest into coins, then buy new seeds
    if carry_value() > 0:
        sell()
    want = 9 - seeds(Crop.Turnip)               # 9 tiles in a plot
    if want > 0 and coins() >= price(Crop.Turnip):
        buy_seeds(Crop.Turnip, min(want, coins() // price(Crop.Turnip)))

    wait(1)                           # always wait in a loop, or the script hogs the CPU
