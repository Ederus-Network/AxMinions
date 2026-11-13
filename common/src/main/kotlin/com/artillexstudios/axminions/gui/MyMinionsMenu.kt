package com.artillexstudios.axminions.gui

import com.artillexstudios.axapi.utils.ItemBuilder
import com.artillexstudios.axapi.utils.StringUtils
import com.artillexstudios.axminions.api.AxMinionsAPI
import com.artillexstudios.axminions.api.config.Config
import com.artillexstudios.axminions.api.config.Messages
import com.artillexstudios.axminions.api.minions.Minion
import com.artillexstudios.axminions.api.utils.TimeUtils
import com.artillexstudios.axminions.api.warnings.Warnings
import com.artillexstudios.axminions.minions.Minions
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack

/**
 * /myminions: every minion the player owns (all loaded worlds, loaded or
 * unloaded chunks), with remote charging. Texts live in messages.yml under
 * "my-minions" and every key has a default here, so old files keep working.
 */
class MyMinionsMenu(private val player: Player) : InventoryHolder {
    companion object {
        const val SIZE = 54
        const val PER_PAGE = 45
        const val PREVIOUS_SLOT = 45
        const val INFO_SLOT = 49
        const val NEXT_SLOT = 53

        private val DEFAULT_LORE = listOf(
            "",
            " <gray>- <white>Mundo: <#FFCC00><world>",
            " <gray>- <white>Posici\u00f3n: <#FFCC00><x>, <y>, <z>",
            " <gray>- <white>Carga: <#FFCC00><charge>",
            "<status>",
            "",
            "<#FB8E08><b>(!)</b> Clic para recargarlo",
            "<#FB8E08><b>(!)</b> Shift + clic para llenar la carga"
        )

        private val DEFAULT_LORE_NO_CHARGE = listOf(
            "",
            " <gray>- <white>Mundo: <#FFCC00><world>",
            " <gray>- <white>Posici\u00f3n: <#FFCC00><x>, <y>, <z>",
            "<status>"
        )

        fun message(route: String, default: String): String {
            return AxMinionsAPI.INSTANCE.getMessages().get("my-minions.$route", default)
        }

        private fun messageList(route: String, default: List<String>): List<String> {
            return AxMinionsAPI.INSTANCE.getMessages().get("my-minions.$route", default)
        }

        fun ownedMinions(player: Player): List<Minion> {
            val uuid = player.uniqueId
            return Minions.getMinions()
                .filter { it.getOwnerUUID() == uuid }
                .sortedWith(compareBy<Minion>({ it.getLocation().world?.name ?: "" }, { it.getLocation().blockX }, { it.getLocation().blockZ }, { it.getLocation().blockY }))
        }
    }

    private val inventory: Inventory
    private var minions: List<Minion> = emptyList()
    private var page = 0

    init {
        minions = ownedMinions(player)
        inventory = Bukkit.createInventory(this, SIZE, title())
    }

    override fun getInventory(): Inventory {
        return inventory
    }

    fun pages(): Int {
        return maxOf(1, (minions.size + PER_PAGE - 1) / PER_PAGE)
    }

    private fun title(): String {
        return StringUtils.formatToString(
            message("title", "<#00AAFF>Mis minions <gray>(<page>/<pages>)"),
            Placeholder.unparsed("page", (page + 1).toString()),
            Placeholder.unparsed("pages", pages().toString())
        )
    }

    fun open() {
        render()
        player.openInventory(inventory)
    }

    fun changePage(delta: Int) {
        val target = page + delta
        if (target < 0 || target >= pages()) return
        page = target
        // The title shows the page, so a new page needs a new inventory
        val menu = MyMinionsMenu(player)
        menu.page = page
        menu.open()
    }

    /** Minion shown in [slot] of the current page, or null. */
    fun minionAt(slot: Int): Minion? {
        if (slot < 0 || slot >= PER_PAGE) return null
        return minions.getOrNull(page * PER_PAGE + slot)
    }

    /** Re-reads the player's minions and redraws the current page. */
    fun refresh() {
        minions = ownedMinions(player)
        if (page >= pages()) page = pages() - 1
        render()
    }

    private fun render() {
        inventory.clear()
        val filler = ItemBuilder.create(AxMinionsAPI.INSTANCE.getConfig().getConfig().getSection("gui.items.filler")).get()
        for (slot in PER_PAGE until SIZE) {
            inventory.setItem(slot, filler)
        }

        val start = page * PER_PAGE
        for (i in 0 until PER_PAGE) {
            val minion = minions.getOrNull(start + i) ?: break
            inventory.setItem(i, entry(minion))
        }

        if (page > 0) {
            inventory.setItem(PREVIOUS_SLOT, ItemBuilder.create(Material.ARROW)
                .setName(message("previous", "<#FFCC00><b>\u00ab</b> P\u00e1gina anterior"))
                .get())
        }

        if (page < pages() - 1) {
            inventory.setItem(NEXT_SLOT, ItemBuilder.create(Material.ARROW)
                .setName(message("next", "<#FFCC00>P\u00e1gina siguiente <b>\u00bb</b>"))
                .get())
        }

        val count = Placeholder.unparsed("amount", minions.size.toString())
        inventory.setItem(INFO_SLOT, ItemBuilder.create(Material.BOOK)
            .setName(message("info.name", "<#00AAFF><b>Tus minions"), count)
            .setLore(messageList("info.lore", listOf(
                "",
                " <gray>- <white>Colocados: <#FFCC00><amount>",
                "",
                "<gray>Recargarlos desde aqu\u00ed cuesta lo mismo",
                "<gray>que hacerlo al lado del minion."
            )), count)
            .get())
    }

    private fun entry(minion: Minion): ItemStack {
        val location = minion.getLocation()
        val level = minion.getLevel()
        val now = System.currentTimeMillis()
        val chargeEnabled = Config.CHARGE_ENABLED()
        val outOfCharge = chargeEnabled && minion.getCharge() < now
        val warning = minion.getWarning()

        val status = when {
            outOfCharge -> message("status.no-charge", " <gray>- <white>Estado: <#FF3333>Sin carga")
            warning != null && warning != Warnings.NO_CHARGE -> message("status.warning", " <gray>- <white>Aviso: <warning>")
            !minion.isTicking() -> message("status.unloaded", " <gray>- <white>Estado: <gray>En pausa (zona sin cargar)")
            else -> message("status.working", " <gray>- <white>Estado: <#33FF33>Trabajando")
        }

        val template = if (chargeEnabled) {
            messageList("entry.lore", DEFAULT_LORE)
        } else {
            messageList("entry.lore-no-charge", DEFAULT_LORE_NO_CHARGE)
        }

        val lore = mutableListOf<String>()
        for (line in template) {
            if (line.trim() == "<status>") {
                if (status.isNotBlank()) lore.add(status)
            } else {
                lore.add(line)
            }
        }

        val typeName = minion.getType().getConfig().get("name", minion.getType().getName())
        val resolvers = arrayOf<TagResolver>(
            Placeholder.parsed("type", typeName),
            Placeholder.parsed("level_color", Messages.LEVEL_COLOR(level)),
            Placeholder.unparsed("level", level.toString()),
            Placeholder.unparsed("world", location.world?.name ?: "---"),
            Placeholder.unparsed("x", location.blockX.toString()),
            Placeholder.unparsed("y", location.blockY.toString()),
            Placeholder.unparsed("z", location.blockZ.toString()),
            Placeholder.unparsed("charge", TimeUtils.format(minion.getCharge() - now)),
            Placeholder.parsed("warning", warning?.getContent() ?: "")
        )

        return ItemBuilder.create(minion.getType().getItem(level))
            .setName(message("entry.name", "<type> <gray>(Nivel <level_color><level><gray>)"), *resolvers)
            .setLore(lore, *resolvers)
            .get()
    }
}
