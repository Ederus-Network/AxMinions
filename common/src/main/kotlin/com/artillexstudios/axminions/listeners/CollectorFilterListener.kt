package com.artillexstudios.axminions.listeners

import com.artillexstudios.axapi.utils.StringUtils
import com.artillexstudios.axminions.api.config.Messages
import com.artillexstudios.axminions.api.utils.CoolDown
import com.artillexstudios.axminions.gui.CollectorFilterMenu
import com.artillexstudios.axminions.minions.CollectorFilters
import com.artillexstudios.axminions.minions.Minions
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent

/** Ederus: clicks in the collector filter menu (see CollectorFilterMenu). */
class CollectorFilterListener : Listener {
    private val coolDown = CoolDown<Player>()

    @EventHandler
    fun onInventoryDragEvent(event: InventoryDragEvent) {
        if (event.inventory.holder !is CollectorFilterMenu) return

        event.isCancelled = true
    }

    @EventHandler
    fun onInventoryClickEvent(event: InventoryClickEvent) {
        val menu = event.inventory.holder as? CollectorFilterMenu ?: return
        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return
        val clicked = event.clickedInventory ?: return

        if (coolDown.contains(player)) return
        coolDown.add(player, 200)

        val minion = menu.minion
        // It may have been picked up while the menu was open
        if (!Minions.getMinions().contains(minion)) {
            player.sendMessage(StringUtils.formatToString(Messages.PREFIX() + CollectorFilterMenu.message("gone", "<red>Ese minion ya no está colocado.")))
            player.closeInventory()
            return
        }

        val locationId = minion.getLocationId()

        // An item of the player's own inventory: add its material
        if (clicked == player.inventory) {
            val material = event.currentItem?.type ?: return
            if (material == Material.AIR) return
            val filter = CollectorFilters.getOrCreate(locationId)
            if (material in filter.materials) {
                player.sendMessage(StringUtils.formatToString(Messages.PREFIX() + CollectorFilterMenu.message("already", "<gray>Ese objeto ya está en el filtro.")))
                return
            }
            if (filter.materials.size >= CollectorFilters.MAX_ITEMS) {
                player.sendMessage(StringUtils.formatToString(Messages.PREFIX() + CollectorFilterMenu.message("full", "<red>El filtro está lleno.")))
                return
            }
            filter.materials.add(material)
            CollectorFilters.save(locationId)
            menu.render()
            return
        }

        if (clicked != event.inventory) return

        when (event.slot) {
            CollectorFilterMenu.BACK_SLOT -> {
                minion.openInventory(player)
                return
            }

            CollectorFilterMenu.MODE_SLOT -> {
                val filter = CollectorFilters.getOrCreate(locationId)
                filter.whitelist = !filter.whitelist
            }

            CollectorFilterMenu.CLEAR_SLOT -> {
                val filter = CollectorFilters.get(locationId) ?: return
                filter.materials.clear()
            }

            else -> {
                val material = menu.materialAt(event.slot) ?: return
                CollectorFilters.get(locationId)?.materials?.remove(material)
            }
        }

        CollectorFilters.save(locationId)
        menu.render()
    }
}
