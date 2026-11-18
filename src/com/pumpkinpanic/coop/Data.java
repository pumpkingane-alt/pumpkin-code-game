package com.pumpkinpanic.coop;

/** Static tuning data, map layout and palette. Java 7 compatible (AIDE). */
final class Data {
    private Data() {}

    static final int WW = 2600, WH = 1800;
    static final float WX0 = 210, WY0 = 215, WX1 = 2390, WY1 = 1478;
    static final float DK_X0 = 1262, DK_X1 = 1338, DK_Y1 = 1655;

    // day cycle (seconds)
    static final float CYC = 240f, DUSK0 = 140f, NIGHT0 = 170f, DAWN0 = 225f;

    // cabin
    static final float CX0 = 900, CY0 = 470, CX1 = 1300, CY1 = 760;
    static final float IX0 = 925, IY0 = 556, IX1 = 1275, IY1 = 742;
    static final float DOOR_X = 1100, DOOR_Y = 752;
    static final float BED_X = 975, BED_Y = 625, BENCH_X = 1240, BENCH_Y = 690, CLOSET_X = 1242, CLOSET_Y = 602;
    static final float[] CANDLE_X = {1055, 1100, 1145};
    static final float CANDLE_Y = 560;

    // yard props
    static final float WELL_X = 930, WELL_Y = 1015, SELL_X = 1235, SELL_Y = 818, OIL_X = 962, OIL_Y = 818;
    static final float FISH_X = 1290, FISH_Y = 1625, BOAT_X = 1400, BOAT_Y = 1585;

    // obstacles x,y,w,h. 0..4 cabin walls, 5 door blocker (monsters only), 6.. fence + props
    static final float[][] OBS = {
        {900, 470, 400, 85}, {900, 470, 26, 290}, {1274, 470, 26, 290}, {900, 742, 165, 18}, {1135, 742, 165, 18},
        {1065, 742, 70, 18},
        {560, 396, 1400, 14},
        {560, 400, 14, 540}, {560, 1080, 14, 214},
        {1946, 400, 14, 460}, {1946, 1000, 14, 294},
        {560, 1280, 670, 14}, {1370, 1280, 590, 14},
        {906, 990, 50, 40}, {1208, 800, 56, 36}, {942, 800, 40, 38}
    };
    static final int OBS_DOOR = 5;

    // plots (top-left), 3x3 tiles each
    static final float[][] PLOTS = {{1350, 520}, {640, 520}, {640, 840}, {1480, 880}, {1700, 640}};
    static final float PLOT_SZ = 200, TILE = 52, TILE_PAD = 22;
    static float tileX(int i) { int p = i / 9, c = (i % 9) % 3; return PLOTS[p][0] + TILE_PAD + c * TILE + TILE / 2; }
    static float tileY(int i) { int p = i / 9, r = (i % 9) / 3; return PLOTS[p][1] + TILE_PAD + r * TILE + TILE / 2; }

    static final float[][] LOGS = {{380, 700}, {330, 1100}, {470, 1360}, {2150, 600}, {2260, 1000}, {2100, 1330}, {1200, 310}, {1700, 300}};
    static final float[][] EDGE = {{110, 300}, {100, 900}, {140, 1400}, {1300, 100}, {2500, 300}, {2510, 900}, {2460, 1400}, {700, 110}, {1900, 110}};

    // crops
    static final String[] CROP = {"Turnip", "Carrot", "Corn", "Pumpkin", "Moonbloom"};
    static final float[] CROP_TIME = {40, 62, 90, 130, 170};
    static final int[] CROP_SELL = {9, 19, 34, 66, 125};
    static final int[] SEED_COST = {3, 7, 13, 26, 50};
    static final int[] CROP_COL = {0xFFB070C8, 0xFFF08A24, 0xFFF2D13C, 0xFFF28C28, 0xFF8FD8FF};

    // upgrades (cost to reach level 1,2,3)
    static final String[] UP_NAME = {"Lantern glass", "Oil tank", "Watering can", "Sturdy boots"};
    static final String[] UP_DESC = {"Wider light", "More oil", "More water, wider pour", "Run faster"};
    static final int[][] UP_COST = {{45, 110, 220}, {35, 90, 180}, {40, 100, 200}, {50, 120, 230}};
    static final int SAW_COST = 60;
    static final int[] KEY_COST = {70, 160, 300, 480};
    static final String[] PART_NAME = {"Hull planks", "Engine", "Propeller", "Fuel can"};
    static final int[] PART_COST = {90, 300, 170, 140};
    static final int PART_WOOD = 8;
    static final float[] LAMP_R = {150, 190, 235, 285};
    static final float[] OIL_CAP = {100, 160, 230, 320};
    static final int[] CAN_CAP = {5, 8, 12, 18};
    static final float[] WATER_R = {54, 72, 92, 112};

    static final String[] HAT = {"No hat", "Straw hat", "Witch hat", "Daisy", "Beanie", "Crown", "Party hat"};
    static final int[] HAT_COST = {0, 25, 60, 35, 30, 150, 40};

    // difficulty: normal, hard, nightmare
    static final String[] DIFF = {"Normal", "Hard", "Nightmare"};
    static final float[] SKIN_FIRST = {90, 70, 50}, SKIN_GAP = {135, 105, 80}, SKIN_SPD = {175, 200, 230};
    static final float[] CLOWN_FIRST = {110, 85, 60}, CLOWN_GAP = {110, 85, 65}, CLOWN_WAIT = {55, 45, 36};
    static final float[] STAG_START = {240, 150, 40}, STAG_SPD = {255, 280, 310};
    static final int[] LIVES = {6, 3, 0};
    static final float[] BLEED = {40, 30, 18};
    static final float[] COST_MUL = {1f, 1.15f, 1.3f};

    // endless mode: every island you escape to is this much harder (frequency of monsters, speed, bleed-out time)
    static final float ENDLESS_STEP = 1.5f, ENDLESS_SPEED_CAP = 2.2f, ENDLESS_COIN_KEEP = 0.5f, ENDLESS_MIN_BLEED = 8f;
    static final float LAND_X = 1285, LAND_Y = 1468;      // where the crew washes ashore on a new island (shore south of the garden)

    static final String[] TIPS = {
        "Caw! Welcome. You cannot move by hand: tap CODE and write a script.",
        "Try:  plant(Crop.Turnip)  - your character walks to the soil by itself.",
        "Water it:  water()   (the well:  refill())",
        "Ripe crop? harvest()  - or loop over tiles():  for t in tiles(): ...",
        "Sell what you carry:  sell()   - then buy(Item.Saw), buy_seeds(...)",
        "Shop calls walk into the cabin for you:  buy(Item.Lantern)",
        "Night is coming. Raise the lantern with lantern(True) or hide()",
        "Creepy music? Check monsters() and hide() in the cabin until it stops.",
        "A clown! cheer() reaches it and cheers it up before it turns sad.",
        "The boat is fixed! Gather the crew and call sail()"
    };

    static final String[] EMOTE = {"!", "?", "Help!", "Run!", "Thanks"};

    // palette
    static final int INK = 0xFF2A1608, PUMPKIN = 0xFFF28C28, PUMPKIN_D = 0xFFC95F12, LEAF = 0xFF5C8A33, CREAM = 0xFFF6E7C1,
        PARCH = 0xFFEAD7A5, WOOD = 0xFF8B5A2B, WOOD_D = 0xFF5C3A1E, DIRT = 0xFF6B4423, SOIL = 0xFF4A2E18, SOIL_W = 0xFF33200F,
        NIGHT = 0xFF080A22, PLUM = 0xFF3B1F5C, CANDLE = 0xFFFFD36B;
    static final int[] HEAD_COL = {0xFFF28C28, 0xFFEDE4CC, 0xFF86B04A, 0xFF6C8FD0};
    static final int[] HEAD_COL_D = {0xFFC95F12, 0xFFB8AC8E, 0xFF5B8030, 0xFF4468A8};
    static final String[] SLOT_NAME = {"Ember", "Ghost", "Sprout", "Frost"};
}
