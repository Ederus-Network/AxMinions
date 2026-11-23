package com.artillexstudios.axminions.gui

import com.artillexstudios.axapi.utils.ItemBuilder
import com.artillexstudios.axapi.utils.StringUtils
import com.artillexstudios.axminions.api.AxMinionsAPI
import com.artillexstudios.axminions.api.minions.Minion
import com.artillexstudios.axminions.minions.CollectorFilters
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack

/**
 * Ederus: filter menu of the collector minion. Click an item of your inventory to
 * add it, click an item of the filter to take it out. Texts in messages.yml under
 * "collector-filter", every key has a default here.
 */
class CollectorFilterMenu(val player: Player, val minion: Minion) : InventoryHolder {
    companion object {
        const val SIZE = 54
        const val BACK_SLOT = 45
        const val MODE_SLOT = 48
        const val INFO_SLOT = 49
        const val CLEAR_SLOT = 50

        fun message(route: String, default: String): String {
            return AxMinionsAPI.INSTANCE.getMessages().get("collector-filter.$route", default)
        }

        fun messageList(route: String, default: List<String>): List<String> {
            return AxMinionsAPI.INSTANCE.getMessages().get("collector-filter.$route", default)
        }
    }

    private val inventory: Inventory = Bukkit.createInventory(
        this, SIZE, StringUtils.formatToString(message("title", "<#FFAA00>Filtro del recolector"))
    )

    override fun getInventory(): Inventory = inventory

    fun open() {
        render()
        player.openInventory(inventory)
    }

    /** Material shown in [slot], or null. */
    fun materialAt(slot: Int): Material? {
        if (slot < 0 || slot >= CollectorFilters.MAX_ITEMS) return null
        return CollectorFilters.get(minion.getLocationId())?.materials?.elementAtOrNull(slot)
    }

    fun render() {
        inventory.clear()
        val filter = CollectorFilters.get(minion.getLocationId())
        val materials = filter?.materials ?: emptySet()
        val whitelist = filter?.whitelist ?: true

        val filler = ItemBuilder.create(AxMinionsAPI.INSTANCE.getConfig().getConfig().getSection("gui.items.filler")).get()
        for (slot in CollectorFilters.MAX_ITEMS until SIZE) {
            inventory.setItem(slot, filler)
        }

        materials.forEachIndexed { index, material ->
            if (index >= CollectorFilters.MAX_ITEMS || !material.isItem) return@forEachIndexed
            inventory.setItem(index, ItemBuilder.create(ItemStack(material))
                .setLore(messageList("entry.lore", listOf("", "<#FF5555><b>[!]</b> Clic para quitarlo del filtro")))
                .get())
        }

        inventory.setItem(BACK_SLOT, ItemBuilder.create(Material.ARROW)
            .setName(message("back", "<#FFCC00><b>\u00ab</b> Volver al minion"))
            .get())

        val count = Placeholder.unparsed("amount", materials.size.toString())
        val max = Placeholder.unparsed("max", CollectorFilters.MAX_ITEMS.toString())
        if (whitelist) {
            inventory.setItem(MODE_SLOT, ItemBuilder.create(Material.LIME_DYE)
                .setName(message("mode.whitelist.name", "<#33FF33><b>Modo: solo estos"))
                .setLore(messageList("mode.whitelist.lore", listOf(
                    "",
                    " <gray>- <white>Recoge <#33FF33>solo</#33FF33> los objetos del filtro.",
                    " <gray>- <white>Con el filtro vac\u00edo recoge todo.",
                    "",
                    "<#FFCC00><b>[!]</b> Clic para cambiar a <white>todo menos estos"
                )))
                .get())
        } else {
            inventory.setItem(MODE_SLOT, ItemBuilder.create(Material.RED_DYE)
                .setName(message("mode.blacklist.name", "<#FF5555><b>Modo: todo menos estos"))
                .setLore(messageList("mode.blacklist.lore", listOf(
                    "",
                    " <gray>- <white>Recoge todo <#FF5555>menos</#FF5555> los objetos del filtro.",
                    " <gray>- <white>Con el filtro vac\u00edo recoge todo.",
                    "",
                    "<#FFCC00><b>[!]</b> Clic para cambiar a <white>solo estos"
                )))
                .get())
        }

        inventory.setItem(INFO_SLOT, ItemBuilder.create(Material.BOOK)
            .setName(message("info.name", "<#FFAA00><b>C\u00f3mo funciona"))
            .setLore(messageList("info.lore", listOf(
                "",
                " <gray>- <white>Haz clic en un objeto de tu inventario",
                "   <white>para a\u00f1adirlo (no se gasta).",
                " <gray>- <white>Haz clic en un objeto de arriba para quitarlo.",
                "",
                " <gray>- <white>Objetos en el filtro: <#FFCC00><amount>/<max>"
            )), count, max)
            .get())

        inventory.setItem(CLEAR_SLOT, ItemBuilder.create(Material.BARRIER)
            .setName(message("clear.name", "<#FF5555><b>Vaciar filtro"))
            .setLore(messageList("clear.lore", listOf("", "<gray>Quita todos los objetos del filtro.")))
            .get())
    }
}
