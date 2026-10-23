package app.parabol.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

/**
 * Persistent storage: accounts (profiles), per-profile favorites/hidden groups,
 * stream format, and recordings (scheduled + completed).
 *
 * Favorite keys:   "live:<streamId>", "vod:<streamId>", "series:<seriesId>"
 * Hidden keys:     "live:<categoryId>", "vod:<categoryId>", "series:<categoryId>"
 * Both are stored PER PROFILE, since different accounts have different content.
 */
class Store(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("parabol", Context.MODE_PRIVATE)
    private val gson = Gson()

    // ---- Profiles (accounts) ----

    fun getProfiles(): List<Profile> {
        migrateLegacySingleAccountIfNeeded()
        val json = prefs.getString("profiles", null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<Profile>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun setProfiles(profiles: List<Profile>) {
        prefs.edit().putString("profiles", gson.toJson(profiles)).apply()
    }

    fun addProfile(name: String, baseUrl: String, username: String, password: String): Profile {
        val profile = Profile(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { username },
            baseUrl = baseUrl.trimEnd('/'),
            username = username,
            password = password
        )
        val updated = getProfiles() + profile
        setProfiles(updated)
        if (activeProfileId.isBlank()) activeProfileId = profile.id
        return profile
    }

    /**
     * Adds an account, or updates the existing one when the same server + username is
     * entered again (e.g. after a password change). Keeps its id, so its favorites,
     * hidden groups and recordings stay attached to it.
     */
    fun upsertProfile(name: String, baseUrl: String, username: String, password: String): Profile {
        val normalizedUrl = baseUrl.trimEnd('/')
        val profiles = getProfiles()
        val existing = profiles.firstOrNull {
            it.baseUrl.equals(normalizedUrl, ignoreCase = true) && it.username == username
        } ?: return addProfile(name, baseUrl, username, password)
        val updated = existing.copy(
            name = name.ifBlank { existing.name },
            baseUrl = normalizedUrl,
            password = password
        )
        setProfiles(profiles.map { if (it.id == existing.id) updated else it })
        return updated
    }

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(listener)

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(listener)

    fun removeProfile(id: String) {
        setProfiles(getProfiles().filter { it.id != id })
        prefs.edit()
            .remove("favorites_$id")
            .remove("hiddenGroups_$id")
            .remove("epgOffset_$id")
            .apply()
        if (activeProfileId == id) {
            activeProfileId = getProfiles().firstOrNull()?.id ?: ""
        }
    }

    var activeProfileId: String
        get() = prefs.getString("activeProfileId", "") ?: ""
        set(v) = prefs.edit().putString("activeProfileId", v).apply()

    fun activeProfile(): Profile? = getProfiles().firstOrNull { it.id == activeProfileId }

    val hasCredentials: Boolean
        get() = activeProfile() != null

    /** One-time migration from the old single-account storage format. */
    private fun migrateLegacySingleAccountIfNeeded() {
        if (prefs.contains("profiles")) return
        val legacyBaseUrl = prefs.getString("baseUrl", "") ?: ""
        val legacyUsername = prefs.getString("username", "") ?: ""
        val legacyPassword = prefs.getString("password", "") ?: ""
        if (legacyBaseUrl.isBlank() || legacyUsername.isBlank() || legacyPassword.isBlank()) {
            prefs.edit().putString("profiles", gson.toJson(emptyList<Profile>())).apply()
            return
        }
        val profile = Profile(
            id = UUID.randomUUID().toString(),
            name = legacyUsername,
            baseUrl = legacyBaseUrl.trimEnd('/'),
            username = legacyUsername,
            password = legacyPassword
        )
        prefs.edit()
            .putString("profiles", gson.toJson(listOf(profile)))
            .putString("activeProfileId", profile.id)
            // Migrate old global favorites/hidden groups into this profile's namespace
            .putStringSet("favorites_${profile.id}", prefs.getStringSet("favorites", emptySet()))
            .putStringSet("hiddenGroups_${profile.id}", prefs.getStringSet("hiddenGroups", emptySet()))
            .remove("baseUrl")
            .remove("username")
            .remove("password")
            .remove("favorites")
            .remove("hiddenGroups")
            .apply()
    }

    // ---- Size of the menus on a TV: "small", "normal" or "large" (device preference) ----
    var tvUiSize: String
        get() = prefs.getString("tvUiSize", "normal") ?: "normal"
        set(v) = prefs.edit().putString("tvUiSize", v).apply()

    // ---- Stream format for live TV (shared across profiles; a device/app preference) ----
    var useHls: Boolean
        get() = prefs.getBoolean("useHls", true)
        set(v) = prefs.edit().putBoolean("useHls", v).apply()

    // ---- TV guide time correction in hours (scoped to the active profile) ----
    // Many providers deliver guide data shifted by the server's timezone; this lets the
    // user correct it per account, like other players do.
    fun getEpgOffsetHours(profileId: String = activeProfileId): Int {
        if (profileId.isBlank()) return 0
        return prefs.getInt("epgOffset_$profileId", 0)
    }

    fun setEpgOffsetHours(hours: Int) {
        val id = activeProfileId
        if (id.isBlank()) return
        prefs.edit().putInt("epgOffset_$id", hours.coerceIn(-12, 12)).apply()
    }

    // ---- Favorites (scoped to the active profile) ----
    fun getFavorites(): Set<String> {
        val id = activeProfileId
        if (id.isBlank()) return emptySet()
        return prefs.getStringSet("favorites_$id", emptySet()) ?: emptySet()
    }

    fun setFavorites(value: Set<String>) {
        val id = activeProfileId
        if (id.isBlank()) return
        prefs.edit().putStringSet("favorites_$id", value).apply()
    }

    // ---- Hidden groups (scoped to the active profile) ----
    fun getHiddenGroups(): Set<String> {
        val id = activeProfileId
        if (id.isBlank()) return emptySet()
        return prefs.getStringSet("hiddenGroups_$id", emptySet()) ?: emptySet()
    }

    fun setHiddenGroups(value: Set<String>) {
        val id = activeProfileId
        if (id.isBlank()) return
        prefs.edit().putStringSet("hiddenGroups_$id", value).apply()
    }

    // ---- Recordings (scheduled + completed) ----

    fun getScheduledRecordings(): List<ScheduledRecording> {
        val json = prefs.getString("scheduledRecordings", null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<ScheduledRecording>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun setScheduledRecordings(value: List<ScheduledRecording>) {
        prefs.edit().putString("scheduledRecordings", gson.toJson(value)).apply()
    }

    fun addScheduledRecording(recording: ScheduledRecording) {
        setScheduledRecordings(getScheduledRecordings() + recording)
    }

    fun removeScheduledRecording(id: String) {
        setScheduledRecordings(getScheduledRecordings().filter { it.id != id })
    }

    fun getCompletedRecordings(): List<CompletedRecording> {
        val json = prefs.getString("completedRecordings", null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<CompletedRecording>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun setCompletedRecordings(value: List<CompletedRecording>) {
        prefs.edit().putString("completedRecordings", gson.toJson(value)).apply()
    }

    fun addCompletedRecording(recording: CompletedRecording) {
        setCompletedRecordings(getCompletedRecordings() + recording)
    }

    fun removeCompletedRecording(id: String) {
        setCompletedRecordings(getCompletedRecordings().filter { it.id != id })
    }
}
