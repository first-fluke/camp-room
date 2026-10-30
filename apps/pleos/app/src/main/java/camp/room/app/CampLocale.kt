package camp.room.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import java.text.NumberFormat
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Currency
import java.util.Locale

/** Supported app language is independent of opaque API identifiers and source names. */
object CampLocale {
    private const val PREFERENCES = "camp_room_preferences"
    private const val LANGUAGE = "app_language"

    fun selection(context: Context): String =
        context
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(LANGUAGE, "system") ?: "system"

    fun language(context: Context): String =
        when (val value = selection(context)) {
            "ko",
            "en",
            -> {
                value
            }

            else -> {
                if (Resources
                        .getSystem()
                        .configuration.locales[0]
                        .language == "ko"
                ) {
                    "ko"
                } else {
                    "en"
                }
            }
        }

    fun locale(context: Context): Locale = if (language(context) == "ko") Locale.KOREAN else Locale.ENGLISH

    fun wrap(context: Context): Context {
        val locale = locale(context)
        val config = Configuration(context.resources.configuration)
        config.setLocales(LocaleList(locale))
        config.setLayoutDirection(locale)
        return context.createConfigurationContext(config)
    }

    fun select(
        context: Context,
        value: String,
    ) {
        require(value in setOf("system", "ko", "en"))
        context
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(LANGUAGE, value)
            .apply()
    }

    fun activity(context: Context): Activity? {
        var current = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return current as? Activity
    }
}

/**
 * Application-scoped resource access for ViewModel/domain messages; no Activity reference is
 * retained.
 */
object CampText {
    private lateinit var application: Context
    private val contexts = ResourceContextCache<Configuration, Context> { Configuration(it) }

    fun initialize(context: Context) {
        application = context.applicationContext
        contexts.clear()
    }

    fun context(): Context =
        contexts.get(CampLocale.language(application), application.resources.configuration) {
            CampLocale.wrap(application)
        }

    fun locale(): Locale = CampLocale.locale(application)

    fun get(
        @StringRes id: Int,
        vararg arguments: Any?,
    ): String {
        val context = context()
        return if (arguments.isEmpty()) {
            context.getString(id)
        } else {
            context.getString(id, *arguments.map { it ?: "—" }.toTypedArray())
        }
    }

    fun quantity(
        id: Int,
        count: Int,
    ): String = context().resources.getQuantityString(id, count, count)

    fun currency(value: Int?): String {
        if (value == null) return get(R.string.camp_check_price)
        return NumberFormat
            .getCurrencyInstance(locale())
            .apply {
                currency = Currency.getInstance("KRW")
                maximumFractionDigits = 0
                minimumFractionDigits = 0
            }.format(value)
    }

    fun date(value: String): String =
        runCatching {
            LocalDate
                .parse(value)
                .format(
                    DateTimeFormatter.ofPattern(
                        if (locale().language == "ko") "M.d (EEE)" else "MMM d (EEE)",
                        locale(),
                    ),
                )
        }.getOrDefault(value)

    fun timestamp(value: String): String =
        runCatching {
            OffsetDateTime
                .parse(value)
                .atZoneSameInstant(ZoneId.systemDefault())
                .format(
                    DateTimeFormatter
                        .ofLocalizedDateTime(FormatStyle.SHORT)
                        .withLocale(locale()),
                )
        }.getOrDefault(value)
}

@Composable
fun tr(
    @StringRes id: Int,
    vararg arguments: Any?,
): String =
    if (arguments.isEmpty()) {
        stringResource(id)
    } else {
        stringResource(id, *arguments.map { it ?: "—" }.toTypedArray())
    }

@Composable
fun guestCount(count: Int): String = pluralStringResource(R.plurals.camp_guest_count, count, count)
