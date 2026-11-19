package com.artillexstudios.axminions.minions

import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

/**
 * Ederus: mob coins of UltimateMobCoins carry their value, so two coins never stack. Before a minion
 * stores a coin, UltimateMobCoins adds it to a coin already in the chest, so the values add up in one slot.
 */
object MobCoinStacking {
    private val MOB_COIN = NamespacedKey("ultimatemobcoins", "is_mob_coin")
    private var absorb: MethodHandle? = null
    private var loader: ClassLoader? = null

    fun isMobCoin(item: ItemStack): Boolean =
        item.hasItemMeta() && MOB_COIN in item.itemMeta!!.persistentDataContainer.keys

    /** True when the coin was added to one already in the inventory and must not be stored again. */
    fun absorb(inventory: Inventory, item: ItemStack): Boolean {
        if (!isMobCoin(item)) return false
        val handle = handle() ?: return false
        return try {
            handle.invoke(inventory, item) as Boolean
        } catch (_: Throwable) {
            false
        }
    }

    private fun handle(): MethodHandle? {
        val plugin = Bukkit.getPluginManager().getPlugin("UltimateMobCoins")
        if (plugin == null || !plugin.isEnabled) return null
        val current = plugin.javaClass.classLoader
        if (absorb != null && loader === current) return absorb
        absorb = try {
            val owner = Class.forName("nl.chimpgamer.ultimatemobcoins.paper.listeners.HopperMergeListener", true, current)
            MethodHandles.publicLookup().findStatic(owner, "absorb",
                MethodType.methodType(Boolean::class.javaPrimitiveType, Inventory::class.java, ItemStack::class.java))
        } catch (_: Throwable) {
            null
        }
        loader = current
        return absorb
    }
}
