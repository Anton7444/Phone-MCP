package app.phonemcp.data

import android.content.Context
import app.phonemcp.PrivilegeMode
import app.phonemcp.TunnelType
import app.phonemcp.policy.PathPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class AppSettings(
    val mode: PrivilegeMode? = null,
    val setupDone: Boolean = false,
    val tunnel: TunnelType = TunnelType.QUICK,
    val namedHostname: String = "",
    val port: Int = 8000,
    /** 0 = never */
    val autoStopMinutes: Int = 120,
    /** 0 = same as auto-stop */
    val wakeLockMaxMinutes: Int = 0,
    val wakeLock: Boolean = true,
    val wifiLock: Boolean = true,
    val startOnBoot: Boolean = false,
    val biometricOnStart: Boolean = true,
    val approval: Boolean = true,
    val readOnly: Boolean = false,
    /** Bypasses tool toggles, read-only, path rules and ordinary approvals. Critical commands still need two confirmations. */
    val fullAccess: Boolean = false,
    val toolOverrides: Map<String, Boolean> = emptyMap(),
    val allowPaths: List<String> = PathPolicy.DEFAULT_ALLOW,
    val denyPaths: List<String> = PathPolicy.DEFAULT_DENY,
    /** "system", "zh-TW" or "en" */
    val language: String = "system",
    val auditRetentionDays: Int = 30
) {
    /** Minutes the wake lock may be held; 0 means no timeout. */
    val effectiveWakeLockMinutes: Int get() = if (wakeLockMaxMinutes > 0) wakeLockMaxMinutes else autoStopMinutes
}

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _flow = MutableStateFlow(load())
    val flow: StateFlow<AppSettings> = _flow.asStateFlow()
    val value: AppSettings get() = _flow.value

    @Synchronized
    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_flow.value)
        save(next)
        _flow.value = next
    }

    private fun load(): AppSettings {
        val d = AppSettings()
        return try {
            AppSettings(
                mode = prefs.getString("mode", null)?.let { runCatching { PrivilegeMode.valueOf(it) }.getOrNull() },
                setupDone = prefs.getBoolean("setupDone", d.setupDone),
                tunnel = prefs.getString("tunnel", null)?.let { runCatching { TunnelType.valueOf(it) }.getOrNull() } ?: d.tunnel,
                namedHostname = prefs.getString("namedHostname", d.namedHostname) ?: d.namedHostname,
                port = prefs.getInt("port", d.port).takeIf { it in 1024..65535 } ?: d.port,
                autoStopMinutes = prefs.getInt("autoStopMinutes", d.autoStopMinutes).coerceAtLeast(0),
                wakeLockMaxMinutes = prefs.getInt("wakeLockMaxMinutes", d.wakeLockMaxMinutes).coerceAtLeast(0),
                wakeLock = prefs.getBoolean("wakeLock", d.wakeLock),
                wifiLock = prefs.getBoolean("wifiLock", d.wifiLock),
                startOnBoot = prefs.getBoolean("startOnBoot", d.startOnBoot),
                biometricOnStart = prefs.getBoolean("biometricOnStart", d.biometricOnStart),
                approval = prefs.getBoolean("approval", d.approval),
                readOnly = prefs.getBoolean("readOnly", d.readOnly),
                fullAccess = prefs.getBoolean("fullAccess", d.fullAccess),
                toolOverrides = prefs.getString("toolOverrides", null)?.let(::parseMap) ?: emptyMap(),
                allowPaths = prefs.getString("allowPaths", null)?.let(::parseList) ?: d.allowPaths,
                denyPaths = prefs.getString("denyPaths", null)?.let(::parseList)?.let { it + PathPolicy.DEFAULT_DENY.filter { e -> e in PathPolicy.ADDED_DENY && e !in it } } ?: d.denyPaths,
                language = prefs.getString("language", d.language) ?: d.language,
                auditRetentionDays = prefs.getInt("auditRetentionDays", d.auditRetentionDays).coerceAtLeast(1)
            )
        } catch (_: Exception) {
            d
        }
    }

    private fun save(s: AppSettings) {
        prefs.edit()
            .putString("mode", s.mode?.name)
            .putBoolean("setupDone", s.setupDone)
            .putString("tunnel", s.tunnel.name)
            .putString("namedHostname", s.namedHostname)
            .putInt("port", s.port)
            .putInt("autoStopMinutes", s.autoStopMinutes)
            .putInt("wakeLockMaxMinutes", s.wakeLockMaxMinutes)
            .putBoolean("wakeLock", s.wakeLock)
            .putBoolean("wifiLock", s.wifiLock)
            .putBoolean("startOnBoot", s.startOnBoot)
            .putBoolean("biometricOnStart", s.biometricOnStart)
            .putBoolean("approval", s.approval)
            .putBoolean("readOnly", s.readOnly)
            .putBoolean("fullAccess", s.fullAccess)
            .putString("toolOverrides", JSONObject(s.toolOverrides as Map<*, *>).toString())
            .putString("allowPaths", JSONArray(s.allowPaths).toString())
            .putString("denyPaths", JSONArray(s.denyPaths).toString())
            .putString("language", s.language)
            .putInt("auditRetentionDays", s.auditRetentionDays)
            .apply()
    }

    private fun parseList(json: String): List<String> {
        val a = JSONArray(json)
        return (0 until a.length()).map { a.getString(it) }
    }

    private fun parseMap(json: String): Map<String, Boolean> {
        val o = JSONObject(json)
        return o.keys().asSequence().associateWith { o.getBoolean(it) }
    }
}
