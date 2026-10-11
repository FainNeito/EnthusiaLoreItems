const test = require('node:test')
const assert = require('node:assert/strict')
const { EventEmitter } = require('node:events')
const syncInventory = require('./mineflayer-inventory-sync')

function fixture() {
  const bot = {
    _client: new EventEmitter(),
    inventory: { slots: Array(46).fill(null) },
    currentWindow: null,
    _setSlot(slot, item, window) { window.slots[slot] = item }
  }
  syncInventory(bot, { fromNotch: value => value })
  return bot
}

test('server removal clears stale held inventory and maps equipment slots', () => {
  const bot = fixture()
  bot.inventory.slots[36] = { name: 'diamond_sword' }
  bot._client.emit('set_player_inventory', { slotId: 0, contents: null })
  assert.equal(bot.inventory.slots[36], null)
  for (const [source, target] of [[8, 44], [9, 9], [35, 35], [36, 8], [39, 5], [40, 45]]) {
    const item = { name: `item-${source}` }
    bot._client.emit('set_player_inventory', { slotId: source, contents: item })
    assert.equal(bot.inventory.slots[target], item)
  }
})

test('open-container player slots and cursor use server contents', () => {
  const bot = fixture()
  bot.currentWindow = { inventoryStart: 27, slots: Array(63).fill(null) }
  const sword = { name: 'diamond_sword' }
  bot._client.emit('set_player_inventory', { slotId: 0, contents: sword })
  assert.equal(bot.currentWindow.slots[54], sword)
  bot._client.emit('set_cursor_item', { contents: sword })
  assert.equal(bot.currentWindow.selectedItem, sword)
  bot._client.emit('set_cursor_item', { contents: null })
  assert.equal(bot.currentWindow.selectedItem, null)
  const before = bot.inventory.slots.slice()
  for (const slotId of [-1, 41, 0.5]) bot._client.emit('set_player_inventory', { slotId, contents: sword })
  assert.deepEqual(bot.inventory.slots, before)
})
