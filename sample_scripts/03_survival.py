# SURVIVAL - a guard that runs next to your farming code.
#
# spawn(guard, priority=5) starts guard() as a second thread. A thread with
# a higher priority gets the character first, so when the guard calls
# hide() the farming thread simply waits until it is safe again.
#
# Use it on its own, or copy it into your own script. (You can also save it
# as survival.py and write  from survival import guard  in main.py.)

def unsafe():
    if darkness() > 0.65:                 # <-- tweak: hide earlier / later
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
            if m.waiting:                  # a clown waits for a cheer
                cheer()
            elif m.kind == "clown" and m.hostile and m.dist < 320:
                run_from(m)                # an angry blue clown!
        if unsafe():
            while unsafe():
                # the demon is scared of the lantern
                demon = [m for m in monsters() if m.kind == "demon" and m.hostile]
                lantern(len(demon) > 0 and oil_left() > 5)
                hide(1)                    # stay in the cabin for a second
            lantern(False)
        wait(0.25)

# ---- demo: start the guard and do something simple in the main thread
spawn(guard, priority=5)
while True:
    for t in tiles():
        if t.empty and seeds(Crop.Turnip) > 0:
            plant(Crop.Turnip, t)
        elif t.ready:
            harvest(t)
        elif t.growing and t.water < 0.5 and water_left() >= 1:
            water(t)
    if water_left() < 1:
        refill()
    if carry_value() >= 20:
        sell()
    wait(1)
