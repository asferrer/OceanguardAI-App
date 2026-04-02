package com.oceanguard.ai.inference

enum class ReportAudience {
    /** PhD researchers, marine biologists — scientific nomenclature, methodological rigor. */
    SCIENTIFIC,

    /** Conservation NGOs, coastal managers — balanced technical/operational, action-focused. */
    NGO_MANAGER,

    /** Divers, volunteers, general public — accessible language, inspire action. */
    CITIZEN;

    companion object {
        /** Maps a SettingsRepository key ("scientific", "ngo", "citizen") to the enum value. */
        fun fromKey(key: String): ReportAudience = when (key) {
            "ngo"     -> NGO_MANAGER
            "citizen" -> CITIZEN
            else      -> SCIENTIFIC
        }
    }
}
