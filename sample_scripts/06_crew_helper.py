# CREW HELPER - looks after your friends and the clowns.
# Handy when you play together: each player can run their own script.
# (emote(4) shows a "Thanks" bubble)

def help_crew():
    for p in crew():
        if p.down and not p.me:
            print("reviving", p.name)
            revive(p)

while True:
    help_crew()
    for m in monsters():
        if m.waiting:
            print("cheering up the clown")
            cheer()
    wait(0.5)
