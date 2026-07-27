package com.nomananik.expensemanager

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Remote, admin-controlled configuration for the app.
 *
 * This is fetched from a JSON file hosted in your GitHub repo (see CONFIG_URL below).
 * To act as the "admin panel": open that file on github.com, click the pencil (Edit) icon,
 * change a value, and commit. The next time anyone opens the app, the new behavior applies —
 * no app update or server required.
 *
 * If the fetch fails for any reason (no internet, file missing, bad JSON), [default] is used
 * so the app always keeps working normally.
 */
data class RemoteConfig(
    val websiteUrl: String,
    val maintenanceMode: Boolean,
    val maintenanceTitle: String,
    val maintenanceMessage: String,
    val latestVersionCode: Int,
    val minSupportedVersionCode: Int,
    val forceUpdate: Boolean,
    val updateUrl: String,
    val updateMessage: String,
    val announcementEnabled: Boolean,
    val announcementTitle: String,
    val announcementMessage: String
) {
    companion object {

        // ⚠️ IMPORTANT: point this at YOUR repo (owner/repo/branch/path).
        // We deliberately use the GitHub API (api.github.com) instead of raw.githubusercontent.com.
        // raw.githubusercontent.com sits behind a CDN that can cache a stale copy for ~5 minutes
        // per device, even with cache-busting query params — which made maintenance/announcement
        // changes take minutes (or a force-stop) to show up. api.github.com is not behind that
        // same cache, so changes are reflected on the very next app open.
        private const val CONFIG_OWNER = "nomanwafi"
        private const val CONFIG_REPO = "expense-manager-app"
        private const val CONFIG_BRANCH = "main"
        private const val CONFIG_PATH = "remote_config.json"
        private const val CONFIG_API_URL =
            "https://api.github.com/repos/$CONFIG_OWNER/$CONFIG_REPO/contents/$CONFIG_PATH?ref=$CONFIG_BRANCH"

        private const val CONNECT_TIMEOUT_MS = 4000
        private const val READ_TIMEOUT_MS = 4000

        fun default(context: Context): RemoteConfig = RemoteConfig(
            websiteUrl = context.getString(R.string.web_url),
            maintenanceMode = false,
            maintenanceTitle = context.getString(R.string.maintenance_default_title),
            maintenanceMessage = context.getString(R.string.maintenance_default_message),
            latestVersionCode = 0,
            minSupportedVersionCode = 0,
            forceUpdate = false,
            updateUrl = "",
            updateMessage = context.getString(R.string.update_required_default_message),
            announcementEnabled = false,
            announcementTitle = "",
            announcementMessage = ""
        )

        /** Fetches and parses remote_config.json. Always returns a usable config — never throws. */
        suspend fun fetch(context: Context): RemoteConfig = withContext(Dispatchers.IO) {
            val fallback = default(context)
            try {
                val connection = URL(CONFIG_API_URL).openConnection() as HttpURLConnection
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.requestMethod = "GET"
                // Ask the Contents API to return the raw file body directly (not base64-wrapped JSON).
                connection.setRequestProperty("Accept", "application/vnd.github.raw+json")
                connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                connection.setRequestProperty("Cache-Control", "no-cache")

                val responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    connection.disconnect()
                    return@withContext fallback
                }

                val body = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()

                val json = JSONObject(body)
                RemoteConfig(
                    websiteUrl = json.optString("website_url", fallback.websiteUrl),
                    maintenanceMode = json.optBoolean("maintenance_mode", false),
                    maintenanceTitle = json.optString("maintenance_title", fallback.maintenanceTitle),
                    maintenanceMessage = json.optString("maintenance_message", fallback.maintenanceMessage),
                    latestVersionCode = json.optInt("latest_version_code", 0),
                    minSupportedVersionCode = json.optInt("min_supported_version_code", 0),
                    forceUpdate = json.optBoolean("force_update", false),
                    updateUrl = json.optString("update_url", ""),
                    updateMessage = json.optString("update_message", fallback.updateMessage),
                    announcementEnabled = json.optBoolean("announcement_enabled", false),
                    announcementTitle = json.optString("announcement_title", ""),
                    announcementMessage = json.optString("announcement_message", "")
                )
            } catch (e: Exception) {
                fallback
            }
        }
    }
}
