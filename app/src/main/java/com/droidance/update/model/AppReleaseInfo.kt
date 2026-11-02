// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.update.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 远程发布版本信息
 */
data class AppReleaseInfo(
    val tagName: String,
    val versionName: String,
    val releaseTitle: String,
    val releaseNotes: Map<String, String>,
    val publishedAt: String,
    val htmlUrl: String,
    val apkDownloadUrl: String,
    val apkFileName: String,
    val apkSizeBytes: Long,
    val sha256: String? = null
)

@Serializable
internal data class GitHubReleaseDto(
    @SerialName("tag_name") val tagName: String = "",
    @SerialName("name") val name: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
    @SerialName("assets") val assets: List<GitHubAssetDto> = emptyList()
)

@Serializable
internal data class GitHubAssetDto(
    @SerialName("name") val name: String = "",
    @SerialName("browser_download_url") val downloadUrl: String = "",
    @SerialName("size") val size: Long = 0L,
    @SerialName("content_type") val contentType: String? = null,
    @SerialName("digest") val digest: String? = null
)

internal fun GitHubAssetDto.sha256Digest(): String? = digest
    ?.removePrefix("sha256:")
    ?.takeIf { digest?.startsWith("sha256:") == true && it.matches(Regex("[a-fA-F0-9]{64}")) }

@Serializable
internal data class ReleaseMetadataDto(
    val tag: String,
    val apk: String,
    @SerialName("release_notes") val releaseNotes: Map<String, String>
)
