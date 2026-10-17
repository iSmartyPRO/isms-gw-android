package app.simgate.data

import androidx.compose.runtime.mutableStateOf
import java.util.Locale

/**
 * Bilingual UI (ru / en). Strings are defined as a pair right where they are used:
 *   t("Отправлено", "Sent")
 * The app language is observable Compose state (the UI recomposes when it changes).
 * For web panel HTTP requests the language is overridden for the duration of the request (ThreadLocal).
 */
object I18n {
    const val RU = "ru"
    const val EN = "en"

    /** Current app language. */
    val lang = mutableStateOf(EN)
    private val override = ThreadLocal<String?>()

    fun systemLang(): String = if (Locale.getDefault().language == "ru") RU else EN

    fun current(): String = override.get() ?: lang.value
    fun isEn() = current() == EN

    fun t(ru: String, en: String): String = if (isEn()) en else ru

    /** Runs a block with a different language (e.g. the web panel language from the X-ISMS-Lang header). */
    fun <T> with(language: String?, block: () -> T): T {
        val prev = override.get()
        override.set(language?.lowercase()?.take(2)?.takeIf { it == RU || it == EN })
        try { return block() } finally { override.set(prev) }
    }

    /** Apply the language setting; English is the default until the user picks Russian. */
    fun apply(setting: String) { lang.value = if (setting == RU) RU else EN }

    fun set(language: String) {
        ConfigStore.update { it.copy(language = language) }
        apply(language)
    }
}

fun t(ru: String, en: String) = I18n.t(ru, en)
