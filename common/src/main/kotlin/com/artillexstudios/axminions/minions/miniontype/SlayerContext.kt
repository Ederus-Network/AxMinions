package com.artillexstudios.axminions.minions.miniontype

import com.artillexstudios.axminions.api.minions.Minion

/**
 * Ederus: the slayer minion that is attacking right now, only while its hit runs (kills are published
 * inside that call). UltimateMobCoins reads it by reflection to know the level and the owner.
 */
object SlayerContext {
    @JvmStatic
    @Volatile
    var current: Minion? = null
}
