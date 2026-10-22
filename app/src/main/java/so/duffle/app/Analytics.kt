package so.duffle.app

import android.content.Context
import com.posthog.PostHog
import com.posthog.PersonProfiles
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig

/**
 * How the app is used, counted with PostHog (the same project as the website): installs and
 * opens, screens, and how many people get through each step (connect a wallet, sign in, pay,
 * buy, claim).
 *
 * Anonymous by construction: nobody is ever identified, no person profiles are made, and only
 * steps and a few plain labels (a token's symbol, a shop category) are sent. Never a wallet
 * address, a name, an amount, an email or a phone number. No session recording. The website's
 * privacy page says exactly this; change both together.
 *
 * Off on any build without a PostHog key, and never throws.
 */
object Analytics {

    private var ready = false

    /** Call from each screen that can log, before anything is logged. Idempotent. */
    fun init(context: Context) {
        if (ready || BuildConfig.POSTHOG_KEY.isBlank()) return
        ready = runCatching {
            PostHogAndroid.setup(
                context.applicationContext,
                PostHogAndroidConfig(apiKey = BuildConfig.POSTHOG_KEY, host = BuildConfig.POSTHOG_HOST).apply {
                    captureApplicationLifecycleEvents = true
                    captureScreenViews = true
                    sessionReplay = false
                    personProfiles = PersonProfiles.NEVER
                },
            )
            true
        }.getOrDefault(false)
    }

    /** One step. [labels]: short plain strings only, never anything that identifies a person. */
    fun event(name: String, vararg labels: Pair<String, String>) {
        if (!ready) return
        runCatching { PostHog.capture(event = name, properties = labels.associate { (k, v) -> k to v.take(40) }) }
    }
}
