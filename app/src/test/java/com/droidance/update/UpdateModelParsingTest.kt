// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.update

import com.droidance.update.model.GitHubReleaseDto
import com.droidance.update.model.GitHubAssetDto
import com.droidance.update.model.sha256Digest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateModelParsingTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    @Test
    fun github_release_dto_deserialization() {
        val sampleJson = """
            {
              "tag_name": "v0.2.0",
              "name": "Droidance 0.2.0",
              "body": "### Changes\n- Add GitHub updates\n\nSHA-256: e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
              "published_at": "2026-10-04T18:00:00Z",
              "html_url": "https://github.com/YbbLab/Droidance/releases/tag/v0.2.0",
              "assets": [
                {
                  "name": "droidance-v0.2.0.apk",
                  "browser_download_url": "https://github.com/YbbLab/Droidance/releases/download/v0.2.0/droidance-v0.2.0.apk",
                  "size": 15728640,
                  "content_type": "application/vnd.android.package-archive",
                  "digest": "sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
                }
              ]
            }
        """.trimIndent()

        val releaseDto = json.decodeFromString<GitHubReleaseDto>(sampleJson)
        assertEquals("v0.2.0", releaseDto.tagName)
        assertEquals("Droidance 0.2.0", releaseDto.name)
        assertEquals(1, releaseDto.assets.size)

        val apkAsset = releaseDto.assets.firstOrNull { it.name.endsWith(".apk") }
        assertNotNull(apkAsset)
        assertEquals("droidance-v0.2.0.apk", apkAsset?.name)
        assertEquals(15728640L, apkAsset?.size)

        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", apkAsset?.sha256Digest())
    }

    @Test
    fun missing_or_malformed_asset_digests_are_not_accepted() {
        assertNull(GitHubAssetDto().sha256Digest())
        assertNull(GitHubAssetDto(digest = "a".repeat(64)).sha256Digest())
        assertNull(GitHubAssetDto(digest = "sha256:bad").sha256Digest())
        assertNull(GitHubAssetDto(digest = "sha512:" + "a".repeat(64)).sha256Digest())
        assertEquals("a".repeat(64), GitHubAssetDto(digest = "sha256:" + "a".repeat(64)).sha256Digest())
    }
}
