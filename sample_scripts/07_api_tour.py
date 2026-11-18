# API TOUR - run it and read the console. Great for learning what exists.
# Tip: help(plant) shows how a function works, dir(obj) lists attributes.

print("== you ==")
print("position", pos(), "inside cabin:", inside())
print("coins", coins(), "wood", wood(), "lives", lives())
print("water", water_left(), "/", water_max(), "  oil", oil_left(), "/", oil_max())
print("inventory", inventory())

print("== time ==")
print("day", day(), daytime(), "darkness", round(darkness(), 2), "night in", round(time_to_night()), "s")
print("island", island(), "danger x", danger(), "endless:", is_endless())

print("== farm ==")
for t in tiles():
    print(t)                            # a tile knows its crop, growth and water
print("prices: saw", price(Item.Saw), " plot key", price(Item.PlotKey), " corn seed", price(Crop.Corn))

print("== boat ==")
print("parts built", boat_parts(), "of 4; still needed:", missing_parts())

print("== crew and monsters ==")
for p in crew():
    print(p.name, p.state, "at", int(p.x), int(p.y))
print("monsters out:", monsters())

print("== a taste of the language ==")
squares = [n * n for n in range(1, 6)]
print(squares, sum(squares), f"{3.14159:.2f}")
scores = {"turnip": 9, "pumpkin": 66}
for name, value in scores.items():
    print(name, "sells for", value)

help()                                   # all functions by category
