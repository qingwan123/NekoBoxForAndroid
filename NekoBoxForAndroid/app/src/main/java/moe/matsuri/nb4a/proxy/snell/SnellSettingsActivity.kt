package moe.matsuri.nb4a.proxy.snell

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.ui.profile.ProfileSettingsActivity
import moe.matsuri.nb4a.proxy.PreferenceBinding
import moe.matsuri.nb4a.proxy.PreferenceBindingManager
import moe.matsuri.nb4a.proxy.Type

class SnellSettingsActivity : ProfileSettingsActivity<SnellBean>() {
    override fun createEntity() = SnellBean().applyDefaultValues()

    private val pbm = PreferenceBindingManager()
    private val name = pbm.add(PreferenceBinding(Type.Text, "name"))
    private val serverAddress = pbm.add(PreferenceBinding(Type.Text, "serverAddress"))
    private val serverPort = pbm.add(PreferenceBinding(Type.TextToInt, "serverPort"))
    private val psk = pbm.add(PreferenceBinding(Type.Text, "psk"))
    private val version = pbm.add(PreferenceBinding(Type.TextToInt, "version"))
    private val obfsMode = pbm.add(PreferenceBinding(Type.Text, "obfsMode"))
    private val obfsHost = pbm.add(PreferenceBinding(Type.Text, "obfsHost"))
    private val mode = pbm.add(PreferenceBinding(Type.Text, "mode"))
    private val userKey = pbm.add(PreferenceBinding(Type.Text, "userKey"))
    private val reuse = pbm.add(PreferenceBinding(Type.Bool, "reuse"))

    override fun SnellBean.init() {
        pbm.writeToCacheAll(this)
    }

    override fun SnellBean.serialize() {
        pbm.fromCacheAll(this)
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?
    ) {
        addPreferencesFromResource(R.xml.snell_preferences)

        findPreference<EditTextPreference>(Key.SERVER_PORT)!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        }
        findPreference<EditTextPreference>("psk")!!.apply {
            summaryProvider = PasswordSummaryProvider
        }
        // version 4 用 obfs，version 6 用 mode/userkey/reuse
        val v5Mode = findPreference<ListPreference>("version")
        v5Mode?.setOnPreferenceChangeListener { _, newValue ->
            val isV4 = newValue?.toString() == "4"
            findPreference<androidx.preference.Preference>("catObfs")?.isVisible = isV4
            findPreference<androidx.preference.Preference>("catV6")?.isVisible = !isV4
            true
        }
        val isV4 = (v5Mode?.value ?: "4") == "4"
        findPreference<androidx.preference.Preference>("catObfs")?.isVisible = isV4
        findPreference<androidx.preference.Preference>("catV6")?.isVisible = !isV4
    }
}
