package com.artillexstudios.axminions.listeners

import com.artillexstudios.axapi.utils.ItemBuilder
import com.artillexstudios.axapi.utils.StringUtils
import com.artillexstudios.axminions.AxMinionsPlugin
import com.artillexstudios.axminions.api.AxMinionsAPI
import com.artillexstudios.axminions.api.config.Config
import com.artillexstudios.axminions.api.config.Messages
import com.artillexstudios.axminions.api.events.MinionToolEvent
import com.artillexstudios.axminions.api.minions.Direction
import com.artillexstudios.axminions.api.minions.Minion
import com.artillexstudios.axminions.api.minions.miniontype.MinionTypes
import com.artillexstudios.axminions.api.utils.CoolDown
import com.artillexstudios.axminions.api.utils.Keys
import com.artillexstudios.axminions.api.utils.fastFor
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import java.text.NumberFormat
import java.util.Locale
import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

class MinionInventoryListener : Listener {
    companion object {
        // Same short format as <storage> in the minion menu (815K, 1.2M...)
        private val MONEY_FORMAT = NumberFormat.getCompactNumberInstance(Locale.ENGLISH, NumberFormat.Style.SHORT)
    }

    private val coolDown = CoolDown<Player>()

    @EventHandler
    fun onInventoryDragEvent(event: InventoryDragEvent) {
        if (event.inventory.holder !is Minion) return

        event.isCancelled = true
    }

    @EventHandler
    fun onInventoryClickEvent(event: InventoryClickEvent) {
        val minion = event.inventory.holder as? Minion ?: return
        if (event.clickedInventory == null) return
        event.isCancelled = true
        val player = event.whoClicked as Player

        if (coolDown.contains(player)) {
            return
        }

        coolDown.add(player, 250)

        val allowedTools = arrayListOf<Material>()
        run breaking@{
            minion.getType().getConfig().getStringList("tool.material").fastFor {
                if (it.equals("*")) {
                    allowedTools.addAll(Material.entries)
                    return@breaking
                }

                allowedTools.add(Material.matchMaterial(it) ?: return@fastFor)
            }
        }

        // Ederus: a boost from the player's inventory goes into the level 10 slot
        if (event.clickedInventory == player.inventory && event.currentItem != null
            && minion.getType().getName() != "collector"
            && com.artillexstudios.axminions.minions.MinionBoosts.definitionFor(minion, event.currentItem) != null) {
            val boosts = com.artillexstudios.axminions.minions.MinionBoosts
            if (!boosts.unlocked(minion)) {
                player.sendMessage(StringUtils.formatToString(Messages.PREFIX() + boosts.message("need-level", "<red>El potenciador se desbloquea en el nivel <level>.").replace("<level>", boosts.unlockLevel(minion).toString())))
                return
            }
            val clickedItem = event.currentItem!!
            val single = clickedItem.clone()
            single.amount = 1
            val definition = boosts.definitionFor(minion, single)!!
            val expires = boosts.expiresAt(single)
            if (definition.duration > 0 && expires != null && expires <= System.currentTimeMillis()) {
                player.sendMessage(StringUtils.formatToString(Messages.PREFIX() + boosts.message("spent", "<red>Ese potenciador ya est\u00e1 agotado.")))
                return
            }
            val previous = boosts.get(minion.getLocationId())
            clickedItem.amount = clickedItem.amount - 1
            boosts.startClock(single, definition)
            boosts.set(minion.getLocationId(), single)
            if (previous != null) {
                player.inventory.addItem(previous).values.forEach { player.world.dropItemNaturally(player.location, it) }
            }
            minion.markDirty()
            minion.updateInventories()
            return
        }

        if (event.clickedInventory == player.inventory && event.currentItem != null) {
            if (event.currentItem!!.type !in allowedTools) {
                player.sendMessage(StringUtils.formatToString(Messages.PREFIX() + Messages.WRONG_TOOL()))
                return
            }

            val minionToolEvent = MinionToolEvent(minion, player, event.currentItem!!.clone(), minion.getTool()!!.clone())
            Bukkit.getPluginManager().callEvent(minionToolEvent)
            if (minionToolEvent.isCancelled) return

            if (minion.getTool()?.type != Material.AIR) {
                val current = minionToolEvent.newTool
                val tool = minionToolEvent.oldTool
                minion.setTool(current)
                minion.updateArmour()
                event.currentItem!!.amount = 0
                event.clickedInventory!!.addItem(tool)
            } else {
                minion.setTool(minionToolEvent.newTool)
                event.currentItem!!.amount = 0
            }

            minion.updateInventories()
            return
        }

        if (event.slot == AxMinionsAPI.INSTANCE.getConfig().get("gui.items.item.slot")) {
            if (minion.getTool()?.type == Material.AIR) return
            if (player.inventory.firstEmpty() == -1) {
                player.sendMessage(StringUtils.formatToString(Messages.PREFIX() + Messages.ERROR_INVENTORY_FULL()))
                return
            }
            val tool = minion.getTool()?.clone() ?: return

            val minionToolEvent = MinionToolEvent(minion, player, ItemStack(Material.AIR), tool)
            Bukkit.getPluginManager().callEvent(minionToolEvent)
            if (minionToolEvent.isCancelled) return

            minion.setTool(ItemStack(Material.AIR))
            minion.updateArmour()
            val toolMeta = minionToolEvent.oldTool.itemMeta ?: return
            toolMeta.persistentDataContainer.remove(Keys.GUI)
            minionToolEvent.oldTool.setItemMeta(toolMeta)

            player.inventory.addItem(minionToolEvent.oldTool)
            minion.updateInventories()
            return
        }

        if (!(event.clickedInventory?.getItem(event.slot)?.hasItemMeta() ?: return)) {
            return
        }

        val meta = event.clickedInventory?.getItem(event.slot)?.itemMeta ?: return
        if (!meta.persistentDataContainer.has(Keys.GUI, PersistentDataType.STRING)) return
        val type = meta.persistentDataContainer.get(Keys.GUI, PersistentDataType.STRING)

        when (type) {
            // Ederus: collector filter menu, from the unlock level
            "filter" -> {
                if (!com.artillexstudios.axminions.minions.MinionBoosts.unlocked(minion)) return
                com.artillexstudios.axminions.gui.CollectorFilterMenu(player, minion).open()
                return
            }

            // Ederus: take the boost out of the level 10 slot
            "boost" -> {
                val boosts = com.artillexstudios.axminions.minions.MinionBoosts
                val current = boosts.get(minion.getLocationId()) ?: return
                if (player.inventory.firstEmpty() == -1) {
                    player.sendMessage(StringUtils.formatToString(Messages.PREFIX() + Messages.ERROR_INVENTORY_FULL()))
                    return
                }
                boosts.set(minion.getLocationId(), null)
                player.inventory.addItem(current)
                minion.markDirty()
                minion.updateInventories()
                return
            }

            "rotate" -> {
                when (minion.getDirection()) {
                    Direction.NORTH -> minion.setDirection(Direction.WEST)
                    Direction.EAST -> minion.setDirection(Direction.NORTH)
                    Direction.SOUTH -> minion.setDirection(Direction.EAST)
                    Direction.WEST -> minion.setDirection(Direction.SOUTH)
                }
            }

            "link" -> {
                if (minion.getLinkedChest() != null) {
                    minion.setLinkedChest(null)
                    player.sendMessage(StringUtils.formatToString(Messages.PREFIX() + Messages.LINK_UNLINK()))
                    return
                }

                player.sendMessage(StringUtils.formatToString(Messages.PREFIX() + Messages.LINK_START()))
                LinkingListener.linking[player] = minion
                player.closeInventory()
            }

            "upgrade" -> {
                val money = minion.getType().getDouble("requirements.money", minion.getLevel() + 1)
                val actions = minion.getType().getDouble("requirements.actions", minion.getLevel() + 1)

                if (minion.getType().hasReachedMaxLevel(minion)) {
                    return
                }

                if (minion.getActionAmount() < actions) {
                    sendFail(player)
                    return
                }

                AxMinionsPlugin.integrations.getEconomyIntegration()?.let {
                    if (it.getBalance(player) < money) {
                        sendFail(player)
                        return
                    }

                    it.takeBalance(player, money)
                }

                if (Config.UPGRADE_SOUND().isNotBlank()) {
                    player.playSound(
                        player,
                        Sound.valueOf(Config.UPGRADE_SOUND().uppercase(Locale.ENGLISH)),
                        1.0f,
                        1.0f
                    )
                }

                minion.setLevel(minion.getLevel() + 1)
            }

            "statistics" -> {
                val stored = minion.getStorage()
                val isSeller = minion.getType() == MinionTypes.getMinionTypes()["seller"]

                if (stored == 0.0) {
                    if (isSeller) {
                        player.sendMessage(StringUtils.formatToString(Messages.PREFIX() + Messages.COLLECT_EMPTY()))
                    }
                    return
                }

                if (isSeller) {
                    AxMinionsPlugin.integrations.getEconomyIntegration()?.let {
                        minion.getOwner()?.let { owner ->
                            it.giveBalance(owner, stored)
                            minion.setStorage(0.0)
                            player.sendMessage(
                                StringUtils.formatToString(
                                    Messages.PREFIX() + Messages.COLLECT_SUCCESS(),
                                    Placeholder.unparsed("amount", MONEY_FORMAT.format(stored))
                                )
                            )
                        }
                    }
                } else {
                    player.giveExp(stored.toInt())
                    minion.setStorage(0.0)
                }

                AxMinionsPlugin.dataQueue.submit {
                    AxMinionsPlugin.dataHandler.saveMinion(minion)
                }
            }

            "charge" -> {
                if (event.isShiftClick) {
                    val itemCache = mutableListOf<Pair<ItemStack, String>>()
                    val section = Config.CHARGE_ITEMS()

                    for (key in section.keys) {
                        itemCache.add(Pair(ItemBuilder.create(section.getSection(key.toString())).get(), key.toString()))
                    }

                    while (true) {
                        val chargeSeconds = (minion.getCharge() - System.currentTimeMillis()) / 1000

                        if ((Config.MAX_CHARGE() * 60) - chargeSeconds < Config.MINIMUM_CHARGE()) {
                            player.sendMessage(StringUtils.formatToString(Messages.PREFIX() + Messages.CHARGE_NOT_ENOUGH_TIME_PASSED()))
                            return
                        }

                        var chargeAmount = Config.CHARGE_AMOUNT()
                        var itemCharge = false

                        for (pair in itemCache) {
                            if (player.inventory.containsAtLeast(pair.first, 1)) {
                                itemCharge = true
                                chargeAmount = section.getSection(pair.second).getInt("charge")
                                player.inventory.removeItem(pair.first)
                                break
                            }
                        }

                        if (Config.CHARGE_PRICE() <= 0 && !itemCharge) {
                            player.sendMessage(StringUtils.formatToString(Messages.PREFIX() + Messages.CHARGE_FAIL()))
                            return
                        }

                        if (!itemCharge) {
                            if ((AxMinionsPlugin.integrations.getEconomyIntegration()?.getBalance(player)
                                    ?: return) < Config.CHARGE_PRICE()
                            ) {
                                player.sendMessage(StringUtils.formatToString(Messages.PREFIX() + Messages.CHARGE_FAIL()))
                                return
                            }

                            AxMinionsPlugin.integrations.getEconomyIntegration()?.let {
                                minion.getOwner()?.let { player ->
                                    it.takeBalance(player, Config.CHARGE_PRICE())
                                }
                            }
                        }

                        if (chargeSeconds + chargeAmount > Config.MAX_CHARGE() * 60L) {
                            minion.setCharge(System.currentTimeMillis() + Config.MAX_CHARGE() * 60L * 1000L)
                            return
                        }

                        if (minion.getCharge() < System.currentTimeMillis()) {
                            minion.setCharge(System.currentTimeMillis() + chargeAmount * 1000)
                        } else {
                            minion.setCharge(minion.getCharge() + chargeAmount * 1000)
                        }

                        if (Messages.CHARGE().isNotBlank()) {
                            player.sendMessage(StringUtils.formatToString(Messages.PREFIX() + Messages.CHARGE()))
                        }
                    }
                } else {
                    val chargeSeconds = (minion.getCharge() - System.currentTimeMillis()) / 1000

                    if ((Config.MAX_CHARGE() * 60) - chargeSeconds < Config.MINIMUM_CHARGE()) {
                        player.sendMessage(StringUtils.formatToString(Messages.PREFIX() + Messages.CHARGE_NOT_ENOUGH_TIME_PASSED()))
                        return
                    }

                    var chargeAmount = Config.CHARGE_AMOUNT()
                    var itemCharge = false
                    val section = Config.CHARGE_ITEMS()

                    for (key in section.keys) {
                        val item = ItemBuilder.create(section.getSection(key.toString())).get()
                        if (player.inventory.containsAtLeast(item, 1)) {
                            itemCharge = true
                            chargeAmount = section.getSection(key.toString()).getInt("charge")
                            player.inventory.removeItem(item)
                            break
                        }
                    }

                    if (Config.CHARGE_PRICE() <= 0 && !itemCharge) {
                        player.sendMessage(StringUtils.formatToString(Messages.PREFIX() + Messages.CHARGE_FAIL()))
                        return
                    }

                    if (!itemCharge) {
                        if ((AxMinionsPlugin.integrations.getEconomyIntegration()?.getBalance(player)
                                ?: return) < Config.CHARGE_PRICE()
                        ) {
                            player.sendMessage(StringUtils.formatToString(Messages.PREFIX() + Messages.CHARGE_FAIL()))
                            return
                        }

                        AxMinionsPlugin.integrations.getEconomyIntegration()?.let {
                            minion.getOwner()?.let { player ->
                                it.takeBalance(player, Config.CHARGE_PRICE())
                            }
                        }
                    }

                    if (chargeSeconds + chargeAmount > Config.MAX_CHARGE() * 60L) {
                        minion.setCharge(System.currentTimeMillis() + Config.MAX_CHARGE() * 60L * 1000L)
                        return
                    }

                    if (minion.getCharge() < System.currentTimeMillis()) {
                        minion.setCharge(System.currentTimeMillis() + chargeAmount * 1000)
                    } else {
                        minion.setCharge(minion.getCharge() + chargeAmount * 1000)
                    }

                    if (Messages.CHARGE().isNotBlank()) {
                        player.sendMessage(StringUtils.formatToString(Messages.PREFIX() + Messages.CHARGE()))
                    }
                }
            }
        }

        minion.updateInventories()
    }

    @EventHandler
    fun onInventoryCloseEvent(event: InventoryCloseEvent) {
        val holder = event.inventory.holder as? Minion ?: return

        holder.removeOpenInventory(event.inventory)
    }

    private fun sendFail(player: Player) {
        when (Config.UPGRADE_FAIL()) {
            "title" -> {
                player.closeInventory()
                player.sendTitle(StringUtils.formatToString(Messages.UPGRADE_FAIL()), "", 10, 70, 20)
            }

            "subtitle" -> {
                player.closeInventory()
                player.sendTitle("", StringUtils.formatToString(Messages.UPGRADE_FAIL()), 10, 70, 20)
            }

            "actionbar" -> {
                player.closeInventory()
                player.spigot().sendMessage(
                    ChatMessageType.ACTION_BAR,
                    *TextComponent.fromLegacyText(StringUtils.formatToString(Messages.UPGRADE_FAIL()))
                )
            }

            else -> {
                player.sendMessage(StringUtils.formatToString(Messages.PREFIX() + Messages.UPGRADE_FAIL()))
            }
        }
    }
}