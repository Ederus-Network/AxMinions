package com.artillexstudios.axminions.integrations.mobcoins

import java.lang.reflect.Method
import java.math.BigDecimal
import java.util.UUID
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

// Ederus: the collector must not store UltimateMobCoins items in its chest.
// Coins are credited to the minion owner's balance instead (same path as /mobcoins redeem).
object MobCoinsHook {
    private val IS_MOB_COIN = NamespacedKey("ultimatemobcoins", "is_mob_coin")
    private val MOB_COIN_AMOUNT = NamespacedKey("ultimatemobcoins", "mob_coin_amount")

    private var userManager: Any? = null
    private var depositMethod: Method? = null

    fun isMobCoin(stack: ItemStack): Boolean {
        val meta = stack.itemMeta ?: return false
        // UltimateMobCoins stores the flag as a boolean, which is a byte underneath.
        return meta.persistentDataContainer.has(IS_MOB_COIN, PersistentDataType.BYTE)
    }

    // Value of the whole stack, or null if the item has no amount stored.
    fun valueOf(stack: ItemStack, amount: Int): BigDecimal? {
        val perItem = stack.itemMeta?.persistentDataContainer?.get(MOB_COIN_AMOUNT, PersistentDataType.DOUBLE) ?: return null
        return perItem.toBigDecimal().multiply(amount.toBigDecimal())
    }

    // Calls UserManager.depositCoinsAsync(UUID, BigDecimal); it loads offline users from the database.
    fun deposit(owner: UUID, value: BigDecimal): Boolean {
        resolve()
        val manager = userManager ?: return false
        val method = depositMethod ?: return false
        return try {
            method.invoke(manager, owner, value)
            true
        } catch (exception: Exception) {
            Bukkit.getLogger().warning("[AxMinions] Could not deposit mobcoins to $owner: ${exception.message}")
            false
        }
    }

    private fun resolve() {
        if (userManager != null) return
        val plugin = Bukkit.getPluginManager().getPlugin("UltimateMobCoins") ?: return
        if (!plugin.isEnabled) return
        try {
            val manager = plugin.javaClass.getMethod("getUserManager").invoke(plugin) ?: return
            depositMethod = manager.javaClass.getMethod("depositCoinsAsync", UUID::class.java, BigDecimal::class.java)
            userManager = manager
        } catch (exception: Exception) {
            Bukkit.getLogger().warning("[AxMinions] UltimateMobCoins hook unavailable: ${exception.message}")
        }
    }
}
