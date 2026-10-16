package app.phonemcp.i18n

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

object Locales {
    const val SYSTEM = "system"
    const val ZH_TW = "zh-TW"
    const val EN = "en"

    /** Switches the whole app (activities recreate themselves) using the platform per-app language APIs. */
    fun apply(language: String) {
        AppCompatDelegate.setApplicationLocales(
            if (language == SYSTEM) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(language)
        )
    }

    /** For services and notifications, which AppCompat does not localise on Android 11-12. */
    fun localized(ctx: Context, language: String): Context {
        if (language == SYSTEM) return ctx
        val cfg = Configuration(ctx.resources.configuration)
        cfg.setLocale(Locale.forLanguageTag(language))
        return ctx.createConfigurationContext(cfg)
    }
}
