package tachiyomi.domain.release.interactor

import tachiyomi.core.preference.Preference
import tachiyomi.core.preference.PreferenceStore
import tachiyomi.domain.release.model.Release
import tachiyomi.domain.release.service.ReleaseService
import java.time.Instant
import java.time.temporal.ChronoUnit

class GetApplicationRelease(
    private val service: ReleaseService,
    private val preferenceStore: PreferenceStore,
) {

    private val lastChecked: Preference<Long> by lazy {
        preferenceStore.getLong("last_app_check", 0)
    }

    suspend fun await(arguments: Arguments): Result {
        val now = Instant.now()

        // Limit checks to once every 3 days at most
        if (arguments.forceCheck.not() && now.isBefore(Instant.ofEpochMilli(lastChecked.get()).plus(3, ChronoUnit.DAYS))) {
            return Result.NoNewUpdate
        }

        val release = service.latest(arguments.repository)

        lastChecked.set(now.toEpochMilli())

        // Check if latest version is different from current version
        // SY -->
        val isNewVersion = isNewVersion(arguments.isPreview, arguments.syDebugVersion, arguments.versionName, release.version)
        // SY <--
        return when {
            isNewVersion && arguments.isThirdParty -> Result.ThirdPartyInstallation
            isNewVersion -> Result.NewUpdate(release)
            else -> Result.NoNewUpdate
        }
    }

    // SY -->
    private fun isNewVersion(isPreview: Boolean, syDebugVersion: String, versionName: String, versionTag: String): Boolean {
        // Removes prefixes like "r" or "v"
        val newVersion = versionTag.replace("[^\\d.]".toRegex(), "")
        return if (isPreview) {
            // Preview builds: based on releases in "jobobby04/TachiyomiSYPreview" repo
            // tagged as something like "508"
            val currentInt = syDebugVersion.toIntOrNull()
            currentInt != null && newVersion.toInt() > currentInt
        } else {
            // Release builds: based on releases in "jobobby04/TachiyomiSY" repo
            // tagged as something like "0.1.2"
            val oldVersion = versionName.replace("[^\\d.]".toRegex(), "")

            val newSemVer = newVersion.split(".").map { it.toInt() }
            val oldSemVer = oldVersion.split(".").map { it.toInt() }

            oldSemVer.mapIndexed { index, i ->
                if (newSemVer[index] > i) {
                    return true
                }
            }

            false
        }
    }
    // SY <--

    data class Arguments(
        val isPreview: Boolean,
        val isThirdParty: Boolean,
        val commitCount: Int,
        val versionName: String,
        val repository: String,
        // SY -->
        val syDebugVersion: String,
        // SY <--
        val forceCheck: Boolean = false,
    )

    sealed class Result {
        class NewUpdate(val release: Release) : Result()
        object NoNewUpdate : Result()
        object ThirdPartyInstallation : Result()
    }
}
