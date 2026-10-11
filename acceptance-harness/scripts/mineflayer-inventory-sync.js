// Mineflayer 4.35.0 handles set_slot/window_items but omits these modern packets.
// Apply server-authoritative state; never simulate successful fixture actions.
module.exports = function syncInventory(bot, Item) {
  bot._client.on('set_player_inventory', packet => {
    const source = packet.slotId
    if (!Number.isInteger(source) || source < 0 || source > 40) return
    let slot = source
    if (source < 9) slot = source + 36
    else if (source < 40 && source >= 36) slot = 44 - source
    else if (source === 40) slot = 45
    const item = Item.fromNotch(packet.contents)
    bot._setSlot(slot, item, bot.inventory)
    const window = bot.currentWindow
    if (window && source < 36) {
      const index = window.inventoryStart + (source < 9 ? source + 27 : source - 9)
      bot._setSlot(index, Item.fromNotch(packet.contents), window)
    }
  })
  bot._client.on('set_cursor_item', packet => {
    const window = bot.currentWindow || bot.inventory
    window.selectedItem = Item.fromNotch(packet.contents)
  })
}
