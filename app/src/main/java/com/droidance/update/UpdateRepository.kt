// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.update

import com.droidance.BuildConfig
import com.droidance.settings.GeneralSettingsRepository
import com.droidance.update.model.AppReleaseInfo
import com.droidance.update.model.GitHubReleaseDto
import com.droidance.update.model.UpdateCheckError
import com.droidance.update.model.UpdateCheckState
import com.droidance.update.model.sha256Digest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UpdateRepository internal constructor(
    okHttpClient: OkHttpClient,
    private val recordCheckTime: (Long) -> Unit,
    private val currentVersion: String,
    private val retryDelayMillis: Long = 1_000L,
    private val repository: String = BuildConfig.UPDATE_REPOSITORY
) {
    @Inject constructor(
        okHttpClient: OkHttpClient,
        generalSettingsRepository: GeneralSettingsRepository
    ) : this(okHttpClient, generalSettingsRepository::setLastUpdateCheckTime,
        BuildConfig.VERSION_NAME)

    private val client = okHttpClient.forUpdates().newBuilder()
        .callTimeout(30, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun checkLatestRelease(force: Boolean = false): UpdateCheckState = withContext(Dispatchers.IO) {
        if (repository.isBlank()) return@withContext UpdateCheckState.Disabled
        val request = Request.Builder()
            .url("https://api.github.com/repos/$repository/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Droidance-AppUpdate/${BuildConfig.VERSION_NAME}")
            .apply { if (force) cacheControl(CacheControl.FORCE_NETWORK) }
            .build()
        // Metadata is never proxied: a mirror must not choose the expected APK digest.
        for (attempt in 0..2) {
            try {
                return@withContext client.withUpdateResponse(request) { response ->
                    if (!response.isSuccessful) throw UpdateHttpException(response.code)
                    val body = response.body?.string() ?: throw SerializationException("Empty response body")
                    val dto = json.decodeFromString<GitHubReleaseDto>(body)
                    if (!VersionComparator.isValid(dto.tagName) || !VersionComparator.isValid(currentVersion)) {
                        throw SerializationException("Release and installed versions must use SemVer")
                    }
                    val asset = dto.assets.firstOrNull { isReleaseAsset(it.downloadUrl, it.name, repository) }
                        ?: return@withUpdateResponse UpdateCheckState.CheckFailed(UpdateCheckError.NO_RELEASE_FOUND)
                    val release = AppReleaseInfo(
                        tagName = dto.tagName,
                        versionName = dto.tagName.removePrefix("v").removePrefix("V"),
                        releaseTitle = dto.name?.takeIf { it.isNotBlank() } ?: dto.tagName,
                        releaseNotes = dto.body.orEmpty(),
                        publishedAt = dto.publishedAt.orEmpty(),
                        htmlUrl = "https://github.com/$repository/releases",
                        apkDownloadUrl = asset.downloadUrl,
                        apkFileName = asset.name,
                        apkSizeBytes = asset.size,
                        sha256 = asset.sha256Digest()
                    )
                    recordCheckTime(System.currentTimeMillis())
                    if (VersionComparator.isNewer(dto.tagName, currentVersion)) {
                        UpdateCheckState.UpdateAvailable(release, currentVersion)
                    } else {
                        UpdateCheckState.UpToDate(currentVersion)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SerializationException) {
                return@withContext UpdateCheckState.CheckFailed(UpdateCheckError.PARSE_ERROR, e.message)
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                if (e is UpdateHttpException) {
                    if (e.status == 403 || e.status == 429) {
                        return@withContext UpdateCheckState.CheckFailed(UpdateCheckError.RATE_LIMITED, e.message)
                    }
                    if (e.status == 404) {
                        return@withContext UpdateCheckState.CheckFailed(UpdateCheckError.NO_RELEASE_FOUND, e.message)
                    }
                    if (!e.retryable) {
                        return@withContext UpdateCheckState.CheckFailed(UpdateCheckError.NETWORK_ERROR, e.message)
                    }
                }
                if (attempt == 2) {
                    return@withContext UpdateCheckState.CheckFailed(UpdateCheckError.NETWORK_ERROR, e.message)
                }
                delay(retryDelayMillis * (attempt + 1))
            }
        }
        error("Update retry loop exhausted without a result")
    }
}
