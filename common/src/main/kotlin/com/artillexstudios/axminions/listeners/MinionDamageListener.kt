package com.artillexstudios.axminions.listeners

import com.artillexstudios.axminions.AxMinionsPlugin
import com.artillexstudios.axminions.api.events.MinionKillEntityEvent
import com.artillexstudios.axminions.api.warnings.Warnings
import com.artillexstudios.axminions.nms.NMSHandler
import dev.rosewood.rosestacker.api.RoseStackerAPI
import com.artillexstudios.axminions.integrations.stacker.RoseStackerIntegration
import com.artillexstudios.axminions.minions.miniontype.SlayerContext
import org.bukkit.Bukkit
import java.util.concurrent.ThreadLocalRandom
import java.util.Random
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.inventory.ItemStack

class MinionDamageListener : Listener {
    private val random = Random()

    @EventHandler
    fun onMinionKillEntityEvent(event: MinionKillEntityEvent) {
        val minion = event.minion
        val entitySize = AxMinionsPlugin.integrations.getStackerIntegration().getStackSize(event.target)
        if (AxMinionsPlugin.integrations.getStackerIntegration() is RoseStackerIntegration && entitySize > 1) {
            // chance can be 0.01 = 1% or 0.1 = 10% or 1 = 100%
            val chance = minion.getType().getDouble("chance-kill-stacked-amount", minion.getLevel())
            val amount = minion.getType().getLong("stacked-amount", minion.getLevel())
            val stackedEntity = RoseStackerAPI.getInstance().getStackedEntity(event.target)!!
            val amountToKill = (if (entitySize > amount) amount else entitySize) as Long
            if (random.nextDouble() <= chance) {
                stackedEntity.killPartialStack(null, (amountToKill as Number).toInt())
                minion.setActions((minion.getActionAmount() + (amountToKill - 1L) as Long) as Long)
            }
        } else {
            event.minion.setActions(event.minion.getActionAmount() + entitySize)
        }

        val coerced = (event.minion.getStorage() + ThreadLocalRandom.current().nextInt(1, 4) * entitySize).coerceIn(
                0.0,
                event.minion.getType().getLong("storage", event.minion.getLevel()).toDouble()
        )
        event.minion.setStorage(coerced)
    }

    /**
     * Ederus: a killer minion keeps only the loot of its own kills, never what lies around (other kills, the
     * player's mob coins): that is the collector's job. Kills happen inside the minion's hit, while
     * SlayerContext names it; group members that StainStacker publishes inside that hit count too.
     * Runs after SmartSpawner (HIGHEST) and UltimateMobCoins, so it sees the final drops; what does not fit
     * in the minion's container stays in the drops and falls as usual.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMinionKillDrops(event: EntityDeathEvent) {
        val minion = SlayerContext.current ?: return
        if (event.entity is Player || event.drops.isEmpty()) return
        if (!killedByMinion(event)) return
        if (minion.getLinkedInventory() == null) return

        val kept = ArrayList<ItemStack>()
        for (drop in event.drops) {
            if (drop == null || drop.type.isAir || drop.amount <= 0) continue
            val left = minion.addWithRemaining(drop.clone())
            if (left == null) {
                kept.add(drop)
                continue
            }
            left.values.forEach { if (!it.type.isAir && it.amount > 0) kept.add(it) }
        }

        if (kept.isNotEmpty()) {
            Warnings.CONTAINER_FULL.display(minion)
        }
        event.drops.clear()
        event.drops.addAll(kept)
    }

    private fun killedByMinion(event: EntityDeathEvent): Boolean {
        // The minion hits with a fox that never enters the world: the killing hit names it as damager,
        // and StainStacker gives that same hit to the group members it publishes.
        val hit = event.entity.lastDamageCause as? EntityDamageByEntityEvent ?: return false
        return hit.damager.uniqueId == NMSHandler.get().getAnimalUUID()
    }

    @EventHandler
    fun onEntityDamageByEntityEvent(event: EntityDamageByEntityEvent) {
        val entity = event.entity
        if (entity is LivingEntity) {
            if (event.damager.uniqueId == NMSHandler.get().getAnimalUUID() && event.finalDamage > entity.health) {
                Bukkit.getPluginManager().callEvent(MinionKillEntityEvent(NMSHandler.get().getMinion() ?: return, entity))
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onEntityDamageByEntityEvente(event: EntityDamageByEntityEvent) {
        if (event.damager.uniqueId != NMSHandler.get().getAnimalUUID()) {
            return
        }

        if (event.entity is Player) {
            event.isCancelled = true
            event.damage = 0.0
            return
        }

        event.isCancelled = false
    }
}