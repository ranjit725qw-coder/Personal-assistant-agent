package com.jarves.mh.data

import android.content.Context
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One provider's API key pool: keys usable right now plus total saved.
 * Keys live (newline/comma separated) in the encrypted [ApiKeyVault];
 * this snapshot only filters out keys exhausted earlier today.
 */
data class KeyPoolSnapshot(val available: List<String>, val total: Int)

/**
 * Parses multi-key vault text. Backwards compatible: one saved key
 * behaves exactly like before, as a pool of one.
 */
object ApiKeyPool {
    fun parse(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split('\n', '\r', ',', ';')
            .map { it.trim() }
            .filter { it.length >= 8 }
            .distinct()
    }

    fun count(raw: String?): Int = parse(raw).size

    /** Short hash used to flag exhausted keys without storing them in plain text. */
    fun keyHash(key: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
}

/**
 * Remembers which pool keys hit a rate limit (429), per provider, for the
 * current day — so the runner never wastes time retrying a dead key twice.
 * Only key hashes are stored; raw keys never leave the vault.
 * Flags reset automatically when the date changes.
 */
class ExhaustedKeyStore(context: Context) {
    private val preferences = context.getSharedPreferences("pocket_key_pool", Context.MODE_PRIVATE)

    @Synchronized
    private fun exhaustedToday(providerId: String): MutableSet<String> {
        val today = ApiKeyPool.today()
        if (preferences.getString(dayKey(providerId), "") != today) {
            preferences.edit()
                .putString(dayKey(providerId), today)
                .putStringSet(setKey(providerId), emptySet())
                .apply()
            return mutableSetOf()
        }
        return preferences.getStringSet(setKey(providerId), emptySet())?.toMutableSet() ?: mutableSetOf()
    }

    @Synchronized
    fun isExhausted(providerId: String, key: String): Boolean =
        ApiKeyPool.keyHash(key) in exhaustedToday(providerId)

    @Synchronized
    fun markExhausted(providerId: String, key: String) {
        if (key.isBlank()) return
        val updated = exhaustedToday(providerId).apply { add(ApiKeyPool.keyHash(key)) }
        preferences.edit().putStringSet(setKey(providerId), updated).apply()
    }

    fun snapshot(providerId: String, pool: List<String>): KeyPoolSnapshot =
        KeyPoolSnapshot(pool.filterNot { isExhausted(providerId, it) }, pool.size)

    private fun dayKey(providerId: String) = "day_$providerId"

    private fun setKey(providerId: String) = "exhausted_$providerId"
}
