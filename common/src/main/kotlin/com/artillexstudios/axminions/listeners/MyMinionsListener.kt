package com.artillexstudios.axminions.listeners

import com.artillexstudios.axapi.utils.StringUtils
import com.artillexstudios.axminions.api.config.Config
import com.artillexstudios.axminions.api.config.Messages
import com.artillexstudios.axminions.api.utils.CoolDown
import com.artillexstudios.axminions.gui.MyMinionsMenu
import com.artillexstudios.axminions.minions.MinionCharging
import com.artillexstudios.axminions.minions.Minions
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent

class MyMinionsListener : Listener {
    private val coolDown = CoolDown<Player>()

    @EventHandler
    fun onInventoryDragEvent(event: InventoryDragEvent) {
        if (event.inventory.holder !is MyMinionsMenu) return

        event.isCancelled = true
    }

    @EventHandler
    fun onInventoryClickEvent(event: InventoryClickEvent) {
        val menu = event.inventory.holder as? MyMinionsMenu ?: return
        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return
        if (event.clickedInventory != event.inventory) return

        if (coolDown.contains(player)) return
        coolDown.add(player, 250)

        when (event.slot) {
            MyMinionsMenu.PREVIOUS_SLOT -> {
                menu.changePage(-1)
                return
            }

            MyMinionsMenu.NEXT_SLOT -> {
                menu.changePage(1)
                return
            }
        }

        val minion = menu.minionAt(event.slot) ?: return

        // It may have been picked up or changed hands while the menu was open
        if (minion.getOwnerUUID() != player.uniqueId || !Minions.getMinions().contains(minion)) {
            player.sendMessage(StringUtils.formatToString(Messages.PREFIX() + MyMinionsMenu.message("gone", "<red>Ese minion ya no está colocado.")))
            menu.refresh()
            return
        }

        if (!Config.CHARGE_ENABLED()) return

        // Same rules, items and price as the charge button in the minion GUI
        MinionCharging.charge(player, minion, event.isShiftClick)
        minion.updateInventories()
        menu.refresh()
    }
}
