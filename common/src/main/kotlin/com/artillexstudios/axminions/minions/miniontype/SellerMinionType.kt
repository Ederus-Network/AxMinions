package com.artillexstudios.axminions.minions.miniontype

import com.artillexstudios.axminions.AxMinionsPlugin
import com.artillexstudios.axminions.api.minions.Minion
import com.artillexstudios.axminions.api.minions.miniontype.MinionType
import com.artillexstudios.axminions.api.warnings.Warnings
import com.artillexstudios.axminions.api.AxMinionsAPI
import com.artillexstudios.axminions.api.integrations.types.PricesIntegration
import com.artillexstudios.axminions.minions.MinionTicker
import com.artillexstudios.axminions.minions.miniontype.seller.DailyLimits
import com.artillexstudios.axminions.minions.miniontype.seller.SellerRules
import com.artillexstudios.axminions.utils.Enchantments
import java.io.File
import java.time.LocalDate
import kotlin.math.roundToInt
import org.bukkit.Material
import org.bukkit.block.Container
import org.bukkit.inventory.DoubleChestInventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable

class SellerMinionType : MinionType("seller", AxMinionsPlugin.INSTANCE.getResource("minions/seller.yml")!!) {

    companion object {
        // Ederus: what each owner sold today of the materials with a daily limit (seller.yml: daily-limits).
        private val dailyLimits = DailyLimits()
        private var limitsLoaded = false
        private var lastLimitsSave = 0L
        private const val LIMITS_SAVE_EVERY_MS = 60_000L

        // Used when seller.yml on the server has no sell-damaged / daily-limits section yet.
        private val DEFAULT_DAMAGED = listOf(
            "BOW", "CROSSBOW", "TRIDENT",
            "LEATHER_HELMET", "LEATHER_CHESTPLATE", "LEATHER_LEGGINGS", "LEATHER_BOOTS",
            "CHAINMAIL_HELMET", "CHAINMAIL_CHESTPLATE", "CHAINMAIL_LEGGINGS", "CHAINMAIL_BOOTS",
            "IRON_HELMET", "IRON_CHESTPLATE", "IRON_LEGGINGS", "IRON_BOOTS",
            "GOLDEN_HELMET", "GOLDEN_CHESTPLATE", "GOLDEN_LEGGINGS", "GOLDEN_BOOTS",
            "COPPER_HELMET", "COPPER_CHESTPLATE", "COPPER_LEGGINGS", "COPPER_BOOTS",
            "GOLDEN_SWORD", "STONE_SWORD", "IRON_SWORD", "IRON_SHOVEL", "IRON_AXE", "GOLDEN_AXE"
        )
        private const val DEFAULT_DAMAGED_FROM_LEVEL = 10
        private val DEFAULT_LIMITS = mapOf("BLAZE_ROD" to 10000)

        private fun limitsFile(): File {
            return File(AxMinionsAPI.INSTANCE.getAxMinionsDataFolder(), "seller-daily-limits.txt")
        }

        /** Writes the daily counters if they changed. Called when the plugin stops. */
        @JvmStatic
        fun saveLimits() {
            if (!limitsLoaded || !dailyLimits.isDirty()) return
            dailyLimits.save(limitsFile())
            lastLimitsSave = System.currentTimeMillis()
        }
    }

    // Ederus: settings read from seller.yml, refreshed every few seconds so a reload is picked up.
    private var settingsAt = 0L
    private var damagedFromLevel = DEFAULT_DAMAGED_FROM_LEVEL
    private var damagedMaterials: Set<Material> = emptySet()
    private var limits: Map<Material, Int> = emptyMap()
    private var today = ""

    private fun refreshSettings() {
        val now = System.currentTimeMillis()
        if (settingsAt != 0L && now - settingsAt < 10_000L) return
        settingsAt = now
        val doc = getConfig().backingDocument

        damagedFromLevel = if (doc.contains("sell-damaged.from-level")) {
            doc.getInt("sell-damaged.from-level", DEFAULT_DAMAGED_FROM_LEVEL)
        } else DEFAULT_DAMAGED_FROM_LEVEL

        val names: List<String> = if (doc.contains("sell-damaged.materials")) {
            doc.getStringList("sell-damaged.materials")
        } else DEFAULT_DAMAGED
        val materials = HashSet<Material>()
        for (name in names) {
            // Materials this server version does not have (copper armor on old versions) are skipped.
            val material = Material.matchMaterial(name) ?: continue
            materials.add(material)
        }
        damagedMaterials = materials

        val read = HashMap<Material, Int>()
        if (doc.isSection("daily-limits")) {
            for (name in doc.getSection("daily-limits").getRoutesAsStrings(false)) {
                val material = Material.matchMaterial(name) ?: continue
                val limit = doc.getInt("daily-limits.$name", -1)
                if (limit >= 0) read[material] = limit
            }
        } else if (!doc.contains("daily-limits")) {
            for ((name, limit) in DEFAULT_LIMITS) {
                val material = Material.matchMaterial(name) ?: continue
                read[material] = limit
            }
        }
        limits = read
    }

    override fun shouldRun(minion: Minion): Boolean {
        return MinionTicker.getTick() % minion.getNextAction() == 0L
    }

    override fun onToolDirty(minion: Minion) {
        val minionImpl = minion as com.artillexstudios.axminions.minions.Minion
        minionImpl.setRange(getDouble("range", minion.getLevel()))
        val tool = Enchantments.EFFICIENCY?.let { minion.getTool()?.getEnchantmentLevel(it)?.div(10.0) } ?: 0.1
        val efficiency = 1.0 - if (tool > 0.9) 0.9 else tool
        minionImpl.setNextAction((getLong("speed", minion.getLevel()) * efficiency).roundToInt())
    }

    override fun run(minion: Minion) {
        if (minion.getLinkedInventory() != null && minion.getLinkedInventory()?.firstEmpty() != -1) {
            Warnings.remove(minion, Warnings.CONTAINER_FULL)
        }

        if (minion.getLinkedChest() == null) {
            Warnings.NO_CONTAINER.display(minion)
            return
        }

        val type = minion.getLinkedChest()!!.block.type
        if (type == Material.CHEST && minion.getLinkedInventory() !is DoubleChestInventory && hasChestOnSide(minion.getLinkedChest()!!.block)) {
            minion.setLinkedChest(minion.getLinkedChest())
        }

        if (type == Material.CHEST && minion.getLinkedInventory() is DoubleChestInventory && !hasChestOnSide(minion.getLinkedChest()!!.block)) {
            minion.setLinkedChest(minion.getLinkedChest())
        }

        if (type != Material.CHEST && type != Material.TRAPPED_CHEST && type != Material.BARREL) {
            Warnings.NO_CONTAINER.display(minion)
            minion.setLinkedChest(null)
            return
        }

        if (minion.getLinkedInventory() == null) {
            Warnings.NO_CONTAINER.display(minion)
            minion.setLinkedChest(null)
            return
        }

        Warnings.remove(minion, Warnings.NO_CONTAINER)

        if (!minion.canUseTool()) {
            Warnings.NO_TOOL.display(minion)
            return
        }

        Warnings.remove(minion, Warnings.NO_TOOL)

        if (AxMinionsPlugin.integrations.getPricesIntegration() === null) {
            return
        }

        refreshSettings()
        today = LocalDate.now().toString()
        if (!limitsLoaded) {
            dailyLimits.load(limitsFile(), today)
            limitsLoaded = true
        }

        for (it in minion.getLinkedInventory()!!.contents) {
            if (it == null || it.type == Material.AIR) {
                continue
            }

            val itemMeta = it.itemMeta
            var keepsItems = false
            if (itemMeta is org.bukkit.inventory.meta.BlockStateMeta) {
                val blockState = itemMeta.blockState
                if (blockState is Container) {
                    val stacks = arrayOfNulls<ItemStack>(blockState.inventory.size)
                    for ((counter, stack) in blockState.inventory.contents.withIndex()) {
                        val success = sellItemStack(minion, stack)
                        if (!success) {
                            stacks[counter] = stack
                            keepsItems = true
                        }
                    }
                    blockState.inventory.contents = stacks
                    itemMeta.blockState = blockState
                    it.itemMeta = itemMeta
                }
            }

            // Ederus: a shulker that still holds what could not be sold (daily limit, full storage) is never
            // sold or deleted itself: delete-unsellable used to throw it away with everything inside.
            if (keepsItems) {
                continue
            }

            val success = sellItemStack(minion, it)
            if (success) {
                it.amount = 0
            }
        }

        if (dailyLimits.isDirty() && System.currentTimeMillis() - lastLimitsSave >= LIMITS_SAVE_EVERY_MS) {
            lastLimitsSave = System.currentTimeMillis()
            dailyLimits.save(limitsFile())
        }
    }

    // Returns if the item should be removed
    private fun sellItemStack(minion: Minion, it: ItemStack?): Boolean {
        // Empty slots of a shulker box come here as null: nothing to sell and nothing to keep.
        if (it == null || it.type == Material.AIR || it.amount <= 0) {
            return true
        }

        val prices = AxMinionsPlugin.integrations.getPricesIntegration()!!
        var price = prices.getPrice(it)

        if (price <= 0) {
            // Ederus: from a level on, worn gear that mobs drop sells by the durability it has left.
            price = damagedPrice(minion, it, prices)
        }

        if (price <= 0) {
            return getConfig().get("delete-unsellable")
        }

        // Ederus: daily limit per owner and material. What goes over it stays in the container, never deleted.
        var selling = it.amount
        val limit = limits[it.type]
        if (limit != null) {
            selling = dailyLimits.allowed(minion.getOwnerUUID(), it.type.name, limit, it.amount, today)
            if (selling <= 0) {
                return false
            }
            price = SellerRules.partialPrice(price, selling, it.amount)
        }

        price *= getDouble("multiplier", minion.getLevel())
        // Ederus: boost of the level 10 slot (SellWand x3 Infinita)
        price *= com.artillexstudios.axminions.minions.MinionBoosts.sellMultiplier(minion)

        if (minion.getStorage() + price > getDouble("storage", minion.getLevel())) {
            return false
        }

        minion.setActions(minion.getActionAmount() + selling)
        minion.damageTool()
        minion.setStorage(minion.getStorage() + price)

        if (limit != null) {
            dailyLimits.add(minion.getOwnerUUID(), it.type.name, selling, today)
        }

        if (selling < it.amount) {
            // Only part of the pile fit under the limit: the rest stays where it was.
            it.amount = it.amount - selling
            return false
        }
        return true
    }

    /**
     * Ederus: price of worn gear for minions of the configured level: the price of the same pile new,
     * times the durability left. 0 when it does not apply. Vanilla enchantments do not raise the price.
     */
    private fun damagedPrice(minion: Minion, it: ItemStack, prices: PricesIntegration): Double {
        if (!SellerRules.sellsDamaged(minion.getLevel(), damagedFromLevel)) return 0.0
        if (!damagedMaterials.contains(it.type)) return 0.0
        if (!isPlainGear(it)) return 0.0

        val damage = (it.itemMeta as? Damageable)?.damage ?: 0
        val clean = prices.getPrice(ItemStack(it.type, it.amount))
        return SellerRules.damagedPrice(clean, it.type.maxDurability.toInt(), damage)
    }

    /**
     * True only for gear that differs from a new one in its damage and vanilla enchantments, nothing else.
     * Anything with a name, lore, model, plugin data (MMOItems, AdvancedEnchantments, GodItems...) or any
     * other change is never sold this way.
     */
    private fun isPlainGear(it: ItemStack): Boolean {
        return try {
            val meta = it.itemMeta ?: return false
            if (meta.hasDisplayName() || meta.hasLore() || meta.hasCustomModelData()) return false
            if (!meta.persistentDataContainer.isEmpty) return false

            val reference = ItemStack(it.type, it.amount)
            val referenceMeta = reference.itemMeta ?: return false
            if (referenceMeta is Damageable && meta is Damageable) {
                referenceMeta.damage = meta.damage
            }
            for ((enchantment, level) in meta.enchants) {
                referenceMeta.addEnchant(enchantment, level, true)
            }
            reference.itemMeta = referenceMeta
            // isSimilar compares the whole meta: whatever else the item carries makes it differ.
            reference.isSimilar(it)
        } catch (_: Throwable) {
            false
        }
    }
}
