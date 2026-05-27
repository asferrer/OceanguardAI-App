package com.oceanguard.ai.data.species

import androidx.appcompat.app.AppCompatDelegate
import java.util.Locale

/**
 * Locale-aware resolution of species display names for the BioDex UI.
 *
 * The app lets users override the UI language independently of the system
 * locale (Settings → Language, persisted in `SettingsRepository.appLanguage`
 * and applied via [AppCompatDelegate.setApplicationLocales]). Resolving names
 * with [Locale.getDefault] alone misses that in-app override on several Android
 * versions, so this helper reads the AppCompat per-app locale first and only
 * falls back to the system default when no override is set.
 */
object SpeciesNames {

    /**
     * BCP-47 language tag (e.g. "en", "es") for the current app UI language.
     *
     * Resolution order:
     *  1. AppCompat per-app locale ([AppCompatDelegate.getApplicationLocales]) —
     *     reflects the in-app Language setting.
     *  2. System default locale ([Locale.getDefault]) — when no override is set.
     */
    fun currentLanguage(): String {
        val appLocales = AppCompatDelegate.getApplicationLocales()
        val tag = appLocales.takeUnless { it.isEmpty }?.get(0)?.language
        return tag?.takeIf { it.isNotBlank() } ?: Locale.getDefault().language
    }

    /**
     * Localised common name for [entry] in [language].
     *
     * Falls back to the English common name, then to the scientific name when
     * neither the requested language nor English has a common name.
     *
     * @param entry    Catalogue entry, or null (returns [fallback]).
     * @param language BCP-47 language tag; defaults to [currentLanguage].
     * @param fallback Value returned when [entry] is null.
     */
    fun commonName(
        entry: SpeciesCatalogEntry?,
        language: String = currentLanguage(),
        fallback: String = "???",
    ): String {
        if (entry == null) return fallback
        return entry.commonNames[language]
            ?: entry.commonNames["en"]
            ?: entry.scientificName
    }
}
