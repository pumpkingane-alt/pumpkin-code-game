-- Sample addon. Every function named like an event is called automatically.
createItem("oil_flask", "Oil flask (+60 oil)", 12)     -- appears in the cabin shop, tab "Addons"

function onCropHarvest(tileX, tileY, crop)
  modifyPlayerStat("coins", 2)                           -- host/solo only, replicated to everyone
end

function onItemBuy(itemId, slot)
  if itemId == "oil_flask" then modifyPlayerStat("oil", 60) end
end

function onNightFall()
  log("night has fallen")
end
