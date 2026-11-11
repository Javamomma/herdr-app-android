package herdr.app

import herdr.core.get
import herdr.core.json
import herdr.core.semver
import herdr.core.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** New releases, from GitHub Releases (no account, nothing sent but the request). */
object Updates {
    /** Where releases are published. */
    const val REPO = "Javamomma/herdr-app-android"

    /** The latest release's version and page, or null (offline, none yet). */
    suspend fun latest(): Pair<String, String>? = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
            c.connectTimeout = 8_000
            c.readTimeout = 8_000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            if (c.responseCode != 200) return@runCatching null
            val body = json.parseToJsonElement(c.inputStream.use { it.readBytes().decodeToString() })
            val tag = body["tag_name"].str ?: return@runCatching null
            tag.removePrefix("v") to (body["html_url"].str ?: "https://github.com/$REPO/releases/latest")
        }.getOrNull()
    }

    fun newer(found: String, current: String): Boolean {
        val a = semver(found)
        val b = semver(current)
        return compareValuesBy(a, b, { it.first }, { it.second }, { it.third }) > 0
    }
}
