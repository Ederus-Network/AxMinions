package com.artillexstudios.axminions.minions

import com.artillexstudios.axapi.serializers.Serializers
import com.artillexstudios.axapi.utils.StringUtils
import com.artillexstudios.axminions.AxMinionsPlugin
import com.artillexstudios.axminions.api.AxMinionsAPI
import com.artillexstudios.axminions.api.config.Messages
import com.artillexstudios.axminions.api.minions.Minion
import com.artillexstudios.axminions.data.H2DataHandler
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom

/**
 * Ederus: boost slot of level 10 minions.
 *
 * Each minion type lists the items it accepts in its own file, under boost.items.<ID>, where
 * ID is the MMOItems id of a catalyst, or SELLWAND_X<multiplier>[_INFINITA] for an EDM sell wand.
 * Values of an entry (all optional):
 *   duration: seconds it lasts from the moment it is first put in a minion (0 = permanent)
 *   speed: percent faster (the minion waits that much less between actions)
 *   double-chance: percent chance that each plain stack it produces comes out twice
 *   sell-multiplier: what the seller earns is multiplied by this
 *   keys: list of "key:percent:Name", per catch, given with key-command (fisher)
 * The item lives in the table axminions_boosts and goes back to the owner when the minion is picked up.
 */
object MinionBoosts {

    class Definition(
        val id: String,
        val duration: Long,
        val speed: Double,
        val doubleChance: Double,
        val sellMultiplier: Double,
        val keys: List<Triple<String, Double, String>>
    )

    private val boosts = ConcurrentHashMap<Int, ItemStack>()

    val EXPIRES_KEY: NamespacedKey by lazy { NamespacedKey(AxMinionsPlugin.INSTANCE, "boost_expires") }
    /** The EDM sell wand keeps its data as <namespace>:sellwand_multiplier / sellwand_uses (today "ederussellwands"). */
    private fun wandKey(data: org.bukkit.persistence.PersistentDataContainer, name: String): NamespacedKey? {
        return data.keys.firstOrNull { it.key == name }
    }

    fun unlockLevel(minion: Minion): Int {
        return minion.getType().getConfig().get("boost.level", 10)
    }

    fun unlocked(minion: Minion): Boolean = minion.getLevel() >= unlockLevel(minion)

    fun load() {
        val handler = AxMinionsPlugin.dataHandler as? H2DataHandler ?: return
        boosts.clear()
        handler.loadBoosts().forEach { (id, data) ->
            try {
                boosts[id] = Serializers.ITEM_STACK.deserialize(data)
            } catch (_: Throwable) {
            }
        }
    }

    fun get(locationId: Int): ItemStack? = boosts[locationId]

    fun set(locationId: Int, item: ItemStack?) {
        val handler = AxMinionsPlugin.dataHandler as? H2DataHandler
        if (item == null) {
            boosts.remove(locationId)
            AxMinionsPlugin.dataQueue.submit { handler?.deleteBoost(locationId) }
        } else {
            boosts[locationId] = item
            val data = Serializers.ITEM_STACK.serialize(item)
            AxMinionsPlugin.dataQueue.submit { handler?.saveBoost(locationId, data) }
        }
    }

    /** Takes the boost out of a minion that is being picked up or removed; the item is returned. */
    fun take(locationId: Int): ItemStack? {
        val item = boosts[locationId] ?: return null
        set(locationId, null)
        return item
    }

    /** What identifies [item] in boost.items: the MMOItems id, or SELLWAND_X3_INFINITA for an EDM wand. */
    fun idOf(item: ItemStack?): String? {
        if (item == null || item.type.isAir || !item.hasItemMeta()) return null
        val data = item.itemMeta!!.persistentDataContainer
        val multiplierKey = wandKey(data, "sellwand_multiplier")
        val multiplier = multiplierKey?.let { data.get(it, PersistentDataType.DOUBLE) }
        if (multiplier != null) {
            val uses = wandKey(data, "sellwand_uses")?.let { data.get(it, PersistentDataType.INTEGER) } ?: 0
            val label = if (multiplier == Math.floor(multiplier)) multiplier.toInt().toString() else multiplier.toString().replace('.', '_')
            return "SELLWAND_X" + label + if (uses == -1) "_INFINITA" else ""
        }
        return mmoItemsId(item)
    }

    private fun mmoItemsId(item: ItemStack): String? {
        return try {
            val nbtClass = Class.forName("io.lumine.mythic.lib.api.item.NBTItem")
            val nbt = nbtClass.getMethod("get", ItemStack::class.java).invoke(null, item)
            val id = nbtClass.getMethod("getString", String::class.java).invoke(nbt, "MMOITEMS_ITEM_ID") as? String
            if (id.isNullOrBlank()) null else id
        } catch (_: Throwable) {
            null
        }
    }

    /** The entry of boost.items that accepts [item] in this minion, or null. */
    fun definitionFor(minion: Minion, item: ItemStack?): Definition? {
        val id = idOf(item) ?: return null
        val config = minion.getType().getConfig()
        val route = "boost.items.$id"
        if (config.getSection(route) == null) return null
        val keys = config.get("$route.keys", listOf<String>()).mapNotNull { line ->
            val parts = line.split(':', limit = 3)
            if (parts.size < 2) return@mapNotNull null
            val chance = parts[1].trim().toDoubleOrNull() ?: return@mapNotNull null
            Triple(parts[0].trim(), chance, if (parts.size == 3) parts[2].trim() else parts[0].trim())
        }
        fun number(key: String, default: Double): Double {
            val value: Any? = config.get<Any>("$route.$key", default)
            return (value as? Number)?.toDouble() ?: (value as? String)?.toDoubleOrNull() ?: default
        }
        return Definition(
            id,
            number("duration", 0.0).toLong(),
            number("speed", 0.0),
            number("double-chance", 0.0),
            number("sell-multiplier", 1.0),
            keys
        )
    }

    fun expiresAt(item: ItemStack): Long? {
        return item.itemMeta?.persistentDataContainer?.get(EXPIRES_KEY, PersistentDataType.LONG)
    }

    /** First time a timed boost goes into a minion its clock starts; it never stops after that. */
    fun startClock(item: ItemStack, definition: Definition) {
        if (definition.duration <= 0 || expiresAt(item) != null) return
        val meta = item.itemMeta ?: return
        meta.persistentDataContainer.set(EXPIRES_KEY, PersistentDataType.LONG, System.currentTimeMillis() + definition.duration * 1000L)
        item.itemMeta = meta
    }

    /**
     * The working boost of [minion], or null. A timed boost that ran out is removed here,
     * the owner is told if online and the minion recalculates its speed.
     */
    fun active(minion: Minion): Definition? {
        if (!unlocked(minion)) return null
        val item = boosts[minion.getLocationId()] ?: return null
        val definition = definitionFor(minion, item) ?: return null
        val expires = expiresAt(item)
        if (definition.duration > 0 && expires != null && expires <= System.currentTimeMillis()) {
            if (boosts.remove(minion.getLocationId(), item)) {
                set(minion.getLocationId(), null)
                minion.markDirty()
                com.artillexstudios.axapi.scheduler.Scheduler.get().run { _ ->
                    minion.updateInventories()
                    minion.getOwner()?.player?.sendMessage(StringUtils.formatToString(Messages.PREFIX() +
                        message("expired", "<#FFAA00>El potenciador de uno de tus minions se ha agotado.")))
                }
            }
            return null
        }
        return definition
    }

    /** Multiplier for the time between actions (1.0 = normal). */
    fun speedFactor(minion: Minion): Double {
        val speed = active(minion)?.speed ?: return 1.0
        return (1.0 - speed / 100.0).coerceIn(0.1, 1.0)
    }

    fun sellMultiplier(minion: Minion): Double {
        val multiplier = active(minion)?.sellMultiplier ?: return 1.0
        return if (multiplier > 0) multiplier else 1.0
    }

    /** Whether a produced stack comes out twice. Only plain items (nothing custom) can double. */
    fun rollDouble(minion: Minion, item: ItemStack): Boolean {
        val chance = active(minion)?.doubleChance ?: return false
        if (chance <= 0) return false
        if (!item.isSimilar(ItemStack(item.type))) return false
        return ThreadLocalRandom.current().nextDouble(100.0) < chance
    }

    /** Fisher: per catch, each listed key may be given to the owner with key-command. */
    fun rollKeys(minion: Minion) {
        val keys = active(minion)?.keys ?: return
        if (keys.isEmpty()) return
        val owner = minion.getOwner()?.name ?: return
        val command = minion.getType().getConfig().get("boost.key-command", "crates key give %player% %key% 1")
        for ((key, chance, name) in keys) {
            if (ThreadLocalRandom.current().nextDouble(100.0) >= chance) continue
            val line = command.replace("%player%", owner).replace("%key%", key)
            com.artillexstudios.axapi.scheduler.Scheduler.get().run { _ ->
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line)
            }
            minion.getOwner()?.player?.sendMessage(StringUtils.formatToString(Messages.PREFIX() +
                message("key", "<#33FF33>Tu pescador ha sacado una llave: <white><key>").replace("<key>", name)))
        }
    }

    fun message(route: String, default: String): String {
        return AxMinionsAPI.INSTANCE.getMessages().get("boost.$route", default)
    }

    fun messageList(route: String, default: List<String>): List<String> {
        return AxMinionsAPI.INSTANCE.getMessages().get("boost.$route", default)
    }

    fun formatLeft(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val days = total / 86400
        val hours = (total % 86400) / 3600
        val minutes = (total % 3600) / 60
        return when {
            days > 0 -> "${days}d ${hours}h"
            hours > 0 -> "${hours}h ${minutes}m"
            else -> "${minutes}m"
        }
    }
}
