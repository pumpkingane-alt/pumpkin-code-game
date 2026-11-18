# FISHER - fish at the dock and sell the catch.
# fish() reels in bites for you and raises the lantern when something
# creepy bites at night. It returns how many fish it caught.

while True:
    if is_night() or threat():
        hide(2)                    # not safe out there
        continue
    caught = fish(3)               # go to the dock, catch 3 fish
    print("caught", caught, "fish, worth", carry_value(), "coins")
    sell()
    if coins() >= 60 and not owned(Item.Saw):
        buy(Item.Saw)
    wait(0.5)
