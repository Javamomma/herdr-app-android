package herdr.core

/** How the phone signs in to the Mac: only with its own key. */
object Login {
    const val KEY = "key"

    /** Older builds also saved `"password"`; any saved value now means key login. */
    @Suppress("UNUSED_PARAMETER")
    fun auth(saved: String?): String = KEY

    /** Name of the secret an older build stored the Mac password under (deleted on start). */
    const val OLD_PASSWORD_SECRET = "password"
}
