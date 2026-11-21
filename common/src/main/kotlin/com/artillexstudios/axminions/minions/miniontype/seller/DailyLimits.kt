package com.artillexstudios.axminions.minions.miniontype.seller

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Ederus: how many units of each limited material the seller minions of one owner sold today.
 * Without Bukkit. Only materials with a limit are counted and everything is dropped when the day
 * changes, so it cannot grow without end. The day is a text the caller gives (server local date).
 */
class DailyLimits {
    private var day: String = ""
    private val sold = HashMap<String, Int>()
    private var dirty = false

    private fun key(owner: UUID, material: String): String = "$owner;$material"

    private fun roll(today: String) {
        if (day == today) return
        day = today
        sold.clear()
        dirty = true
    }

    /** How many of [amount] can still be sold today. A negative [limit] means the material has no limit. */
    @Synchronized
    fun allowed(owner: UUID, material: String, limit: Int, amount: Int, today: String): Int {
        if (amount <= 0) return 0
        if (limit < 0) return amount
        roll(today)
        val left = limit - (sold[key(owner, material)] ?: 0)
        return if (left <= 0) 0 else minOf(left, amount)
    }

    @Synchronized
    fun add(owner: UUID, material: String, amount: Int, today: String) {
        if (amount <= 0) return
        roll(today)
        val k = key(owner, material)
        val now = (sold[k] ?: 0).toLong() + amount.toLong()
        sold[k] = if (now > Int.MAX_VALUE) Int.MAX_VALUE else now.toInt()
        dirty = true
    }

    @Synchronized
    fun sold(owner: UUID, material: String, today: String): Int {
        roll(today)
        return sold[key(owner, material)] ?: 0
    }

    @Synchronized
    fun isDirty(): Boolean = dirty

    @Synchronized
    fun entries(): Int = sold.size

    /** Writes the counters. A temporary file is moved over the real one so a crash never leaves it half written. */
    @Synchronized
    fun save(file: File): Boolean {
        return try {
            val text = StringBuilder("day=").append(day).append('\n')
            for ((k, v) in sold) {
                text.append(k).append(';').append(v).append('\n')
            }
            file.absoluteFile.parentFile?.mkdirs()
            val tmp = File(file.absoluteFile.parentFile, file.name + ".tmp")
            Files.write(tmp.toPath(), text.toString().toByteArray(StandardCharsets.UTF_8))
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            dirty = false
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Reads the counters. A file of another day, a missing file or a broken line count as nothing sold. */
    @Synchronized
    fun load(file: File, today: String) {
        day = today
        sold.clear()
        dirty = false
        if (!file.isFile) return
        try {
            val lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)
            if (lines.isEmpty() || lines[0].trim() != "day=$today") return
            for (i in 1 until lines.size) {
                val parts = lines[i].trim().split(';')
                if (parts.size != 3) continue
                val count = parts[2].toIntOrNull() ?: continue
                if (count <= 0) continue
                try {
                    UUID.fromString(parts[0])
                } catch (_: IllegalArgumentException) {
                    continue
                }
                sold[parts[0] + ";" + parts[1]] = count
            }
        } catch (_: Exception) {
            sold.clear()
        }
    }
}
