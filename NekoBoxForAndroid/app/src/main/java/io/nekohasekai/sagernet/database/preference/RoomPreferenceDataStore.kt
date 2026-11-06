package io.nekohasekai.sagernet.database.preference

import androidx.preference.PreferenceDataStore

@Suppress("MemberVisibilityCanBePrivate", "unused")
open class RoomPreferenceDataStore(private val kvPairDao: KeyValuePair.Dao) :
    PreferenceDataStore() {

    fun getBoolean(key: String) = kvPairDao[key]?.boolean
    fun getFloat(key: String) = kvPairDao[key]?.float
    fun getInt(key: String) = kvPairDao[key]?.long?.toInt()
    fun getLong(key: String) = kvPairDao[key]?.long
    fun getString(key: String) = kvPairDao[key]?.string
    fun getStringSet(key: String) = kvPairDao[key]?.stringSet
    fun reset() = kvPairDao.reset()

    override fun getBoolean(key: String, defValue: Boolean) = getBoolean(key) ?: defValue
    override fun getFloat(key: String, defValue: Float) = getFloat(key) ?: defValue
    override fun getInt(key: String, defValue: Int) = getInt(key) ?: defValue
    override fun getLong(key: String, defValue: Long) = getLong(key) ?: defValue
    override fun getString(key: String, defValue: String?) = getString(key) ?: defValue
    override fun getStringSet(key: String, defValue: MutableSet<String>?) =
        getStringSet(key) ?: defValue

    fun putBoolean(key: String, value: Boolean?) =
        if (value == null) remove(key) else putBoolean(key, value)

    fun putFloat(key: String, value: Float?) =
        if (value == null) remove(key) else putFloat(key, value)

    fun putInt(key: String, value: Int?) =
        if (value == null) remove(key) else putLong(key, value.toLong())

    fun putLong(key: String, value: Long?) = if (value == null) remove(key) else putLong(key, value)
    private fun putIfChanged(pair: KeyValuePair) {
        val previous = kvPairDao[pair.key]
        if (previous?.valueType == pair.valueType && previous.value.contentEquals(pair.value)) return
        kvPairDao.put(pair)
        fireChangeListener(pair.key)
    }

    override fun putBoolean(key: String, value: Boolean) = putIfChanged(KeyValuePair(key).put(value))

    override fun putFloat(key: String, value: Float) = putIfChanged(KeyValuePair(key).put(value))

    override fun putInt(key: String, value: Int) = putIfChanged(KeyValuePair(key).put(value.toLong()))

    override fun putLong(key: String, value: Long) = putIfChanged(KeyValuePair(key).put(value))

    override fun putString(key: String, value: String?) =
        if (value == null) remove(key) else putIfChanged(KeyValuePair(key).put(value))

    override fun putStringSet(key: String, values: MutableSet<String>?) =
        if (values == null) remove(key) else putIfChanged(KeyValuePair(key).put(values))

    fun remove(key: String) {
        if (kvPairDao.delete(key) > 0) fireChangeListener(key)
    }

    private val listeners = HashSet<OnPreferenceDataStoreChangeListener>()
    private fun fireChangeListener(key: String) {
        val listeners = synchronized(listeners) {
            listeners.toList()
        }
        listeners.forEach { it.onPreferenceDataStoreChanged(this, key) }
    }

    fun registerChangeListener(listener: OnPreferenceDataStoreChangeListener) {
        synchronized(listeners) {
            listeners.add(listener)
        }
    }

    fun unregisterChangeListener(listener: OnPreferenceDataStoreChangeListener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }
}
