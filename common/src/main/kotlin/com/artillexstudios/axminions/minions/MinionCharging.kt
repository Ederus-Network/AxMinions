package com.artillexstudios.axminions.minions

import com.artillexstudios.axapi.libs.boostedyaml.block.implementation.Section
import com.artillexstudios.axapi.utils.ItemBuilder
import com.artillexstudios.axapi.utils.StringUtils
import com.artillexstudios.axminions.AxMinionsPlugin
import com.artillexstudios.axminions.api.config.Config
import com.artillexstudios.axminions.api.config.Messages
import com.artillexstudios.axminions.api.minions.Minion
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * Charge logic of the minion GUI "charge" button, shared with /myminions
 * so both places use exactly the same rules, items and price.
 */
object MinionCharging {

    /**
     * Charges [minion] paid by [player]. With [fill] (shift click) it keeps
     * charging until a rule stops it, like the original shift click did.
     */
    fun charge(player: Player, minion: Minion, fill: Boolean) {
        val section: Section? = Config.CHARGE_ITEMS()
        val itemCache = mutableListOf<Pair<ItemStack, String>>()
        if (section != null) {
            for (key in section.keys) {
                itemCache.add(Pair(ItemBuilder.create(section.getSection(key.toString())).get(), key.toString()))
            }
        }

        while (true) {
            if (!chargeOnce(player, minion, section, itemCache)) return
            if (!fill) return
        }
    }

    /**
     * One charge step. Returns true when charge was added and another step may follow.
     */
    private fun chargeOnce(player: Player, minion: Minion, section: Section?, itemCache: List<Pair<ItemStack, String>>): Boolean {
        val chargeSeconds = (minion.getCharge() - System.currentTimeMillis()) / 1000

        if ((Config.MAX_CHARGE() * 60) - chargeSeconds < Config.MINIMUM_CHARGE()) {
            player.sendMessage(StringUtils.formatToString(Messages.PREFIX() + Messages.CHARGE_NOT_ENOUGH_TIME_PASSED()))
            return false
        }

        var chargeAmount = Config.CHARGE_AMOUNT()
        var itemCharge = false

        for (pair in itemCache) {
            if (player.inventory.containsAtLeast(pair.first, 1)) {
                itemCharge = true
                chargeAmount = section!!.getSection(pair.second).getInt("charge")
                player.inventory.removeItem(pair.first)
                break
            }
        }

        if (Config.CHARGE_PRICE() <= 0 && !itemCharge) {
            player.sendMessage(StringUtils.formatToString(Messages.PREFIX() + Messages.CHARGE_FAIL()))
            return false
        }

        if (!itemCharge) {
            val economy = AxMinionsPlugin.integrations.getEconomyIntegration() ?: return false
            if (economy.getBalance(player) < Config.CHARGE_PRICE()) {
                player.sendMessage(StringUtils.formatToString(Messages.PREFIX() + Messages.CHARGE_FAIL()))
                return false
            }

            minion.getOwner()?.let { owner ->
                economy.takeBalance(owner, Config.CHARGE_PRICE())
            }
        }

        if (chargeSeconds + chargeAmount > Config.MAX_CHARGE() * 60L) {
            minion.setCharge(System.currentTimeMillis() + Config.MAX_CHARGE() * 60L * 1000L)
            return false
        }

        if (minion.getCharge() < System.currentTimeMillis()) {
            minion.setCharge(System.currentTimeMillis() + chargeAmount * 1000)
        } else {
            minion.setCharge(minion.getCharge() + chargeAmount * 1000)
        }

        if (Messages.CHARGE().isNotBlank()) {
            player.sendMessage(StringUtils.formatToString(Messages.PREFIX() + Messages.CHARGE()))
        }
        return true
    }
}
