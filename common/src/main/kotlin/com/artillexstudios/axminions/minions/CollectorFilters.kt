package com.artillexstudios.axminions.minions

import com.artillexstudios.axminions.AxMinionsPlugin
import com.artillexstudios.axminions.data.H2DataHandler
import org.bukkit.Material
import org.bukkit.NamespacedKey
import java.util.concurrent.ConcurrentHashMap

/**
 * Ederus: item filter of the collector minion.
 *
 * Kept per minion (by its location id) in the table axminions_collector_filters,
 * and copied to the minion item when it is picked up, so it survives moving it.
 * An empty filter collects everything, whatever the mode.
 */
object CollectorFilters {
    const val MAX_ITEMS = 45

    /** Key on the minion item that carries the filter while it is picked up. */
    val ITEM_KEY: NamespacedKey by lazy { NamespacedKey(AxMinionsPlugin.INSTANCE, "collector_filter") }

    class Filter(var whitelist: Boolean = true, val materials: LinkedHashSet<Material> = linkedSetOf())

    private val filters = ConcurrentHashMap<Int, Filter>()

    fun load() {
        val handler = AxMinionsPlugin.dataHandler as? H2DataHandler ?: return
        filters.clear()
        handler.loadCollectorFilters().forEach { (id, data) ->
            decode(data)?.let { filters[id] = it }
        }
    }

    fun get(locationId: Int): Filter? = filters[locationId]

    fun getOrCreate(locationId: Int): Filter = filters.getOrPut(locationId) { Filter() }

    /** True if the collector at [locationId] should pick up [material]. */
    fun allows(locationId: Int, material: Material): Boolean {
        val filter = filters[locationId] ?: return true
        if (filter.materials.isEmpty()) return true
        val listed = material in filter.materials
        return if (filter.whitelist) listed else !listed
    }

    fun save(locationId: Int) {
        val handler = AxMinionsPlugin.dataHandler as? H2DataHandler ?: return
        val data = encode(locationId)
        AxMinionsPlugin.dataQueue.submit {
            if (data == null) {
                handler.deleteCollectorFilter(locationId)
            } else {
                handler.saveCollectorFilter(locationId, data)
            }
        }
    }

    fun remove(locationId: Int) {
        filters.remove(locationId)
        val handler = AxMinionsPlugin.dataHandler as? H2DataHandler ?: return
        AxMinionsPlugin.dataQueue.submit {
            handler.deleteCollectorFilter(locationId)
        }
    }

    /** Sets the filter of a freshly placed minion from the text saved on its item. */
    fun restore(locationId: Int, data: String?) {
        val filter = decode(data ?: return) ?: return
        filters[locationId] = filter
        save(locationId)
    }

    /** "W:STONE,OAK_LOG" (only these) or "B:..." (all but these); null when there is nothing to keep. */
    fun encode(locationId: Int): String? {
        val filter = filters[locationId] ?: return null
        if (filter.materials.isEmpty() && filter.whitelist) return null
        return (if (filter.whitelist) "W:" else "B:") + filter.materials.joinToString(",") { it.name }
    }

    private fun decode(data: String): Filter? {
        if (data.length < 2 || data[1] != ':') return null
        val filter = Filter(data[0] != 'B')
        data.substring(2).split(',').forEach { name ->
            if (name.isBlank()) return@forEach
            val material = Material.matchMaterial(name) ?: return@forEach
            if (filter.materials.size < MAX_ITEMS) filter.materials.add(material)
        }
        return filter
    }
}
