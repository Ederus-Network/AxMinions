package com.artillexstudios.axminions.minions.miniontype.seller

/**
 * Ederus: pure rules of the seller minion, without Bukkit, so they can be tested alone.
 */
object SellerRules {

    /** Whether a minion of this level sells worn gear. fromLevel 0 or less turns it off. */
    @JvmStatic
    fun sellsDamaged(level: Int, fromLevel: Int): Boolean {
        return fromLevel > 0 && level >= fromLevel
    }

    /** Share of durability left: 1.0 for a new or unbreakable item, 0.0 for a broken one. */
    @JvmStatic
    fun durabilityFactor(maxDurability: Int, damage: Int): Double {
        if (maxDurability <= 0) return 1.0
        val worn = damage.coerceIn(0, maxDurability)
        return (maxDurability - worn).toDouble() / maxDurability.toDouble()
    }

    /** Price of a worn pile: what the same pile is worth new, times the durability it has left. */
    @JvmStatic
    fun damagedPrice(cleanPrice: Double, maxDurability: Int, damage: Int): Double {
        if (cleanPrice.isNaN() || cleanPrice.isInfinite() || cleanPrice <= 0.0) return 0.0
        return cleanPrice * durabilityFactor(maxDurability, damage)
    }

    /** Price of the part of a pile that fits under a limit (the price of a pile is linear in its amount). */
    @JvmStatic
    fun partialPrice(pilePrice: Double, selling: Int, amount: Int): Double {
        if (amount <= 0 || selling <= 0) return 0.0
        if (selling >= amount) return pilePrice
        return pilePrice * selling.toDouble() / amount.toDouble()
    }
}
