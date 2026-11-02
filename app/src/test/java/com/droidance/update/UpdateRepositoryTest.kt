// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.update

import com.droidance.update.model.UpdateCheckError
import com.droidance.update.model.UpdateCheckState
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class UpdateRepositoryTest {
    @Test fun disabledDistributionMakesNoNetworkRequest() = runBlocking {
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor {
            requests++
            error("Disabled updater must not make a request")
        }.build()
        assertEquals(UpdateCheckState.Disabled,
            UpdateRepository(client, {}, "test", repository = "").checkLatestRelease())
        assertEquals(0, requests)
    }

    @Test fun releaseAssetsBelongToTheConfiguredDistribution() {
        val own = "https://github.com/example/Fork/releases/download/v1/update.apk"
        val upstream = "https://github.com/YbbLab/Droidance/releases/download/v1/update.apk"
        assertTrue(isReleaseAsset(own, "update.apk", "example/Fork"))
        assertFalse(isReleaseAsset(upstream, "update.apk", "example/Fork"))
        assertFalse(isReleaseAsset(upstream, "update.apk", ""))
    }

    private val digest = "a".repeat(64)
    private fun release(tag: String = "v0.2.0", assetDigest: String = "\"sha256:$digest\"") = """
        {"tag_name":"$tag", "body":"Unrelated checksum: ${"b".repeat(64)}",
         "assets":[{"name":"update.apk", "size":123,
         "browser_download_url":"https://github.com/YbbLab/Droidance/releases/download/$tag/update.apk",
         "digest":$assetDigest}, {"name":"release.json",
         "browser_download_url":"https://github.com/YbbLab/Droidance/releases/download/$tag/release.json"}]}
    """.trimIndent()

    private val metadataUrl = "https://github.com/YbbLab/Droidance/releases/download/v0.2.0/release.json"
    private val metadata = """{"tag":"v0.2.0","apk":"update.apk","sha256":"${"b".repeat(64)}",
        "release_notes":{"en":"- English notes.","zh":"- 中文说明。"}}"""

    private fun repository(requests: MutableList<String>, metadataResponse: Pair<Int, String> = 200 to metadata,
                           response: (Int) -> Pair<Int, String>): UpdateRepository {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests.add(chain.request().url.toString())
            val (status, body) = if (chain.request().url.toString() == metadataUrl) metadataResponse else response(requests.size)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(status).message("fixture").body(body.toResponseBody()).build()
        }.build()
        return UpdateRepository(client, {}, "0.1.0", 0, repository = "YbbLab/Droidance")
    }

    @Test fun digest_is_bound_to_the_selected_asset_and_never_taken_from_notes() = runBlocking {
        val requests = mutableListOf<String>()
        val state = repository(requests) { 200 to release() }.checkLatestRelease(force = true)
        assertEquals(digest, (state as UpdateCheckState.UpdateAvailable).release.sha256)
        assertEquals(listOf("https://api.github.com/repos/YbbLab/Droidance/releases/latest", metadataUrl), requests)
    }

    @Test fun release_notes_do_not_supply_a_missing_official_digest() = runBlocking {
        val state = repository(mutableListOf()) { 200 to release(assetDigest = "null") }.checkLatestRelease()
        assertNull((state as UpdateCheckState.UpdateAvailable).release.sha256)
    }

    @Test fun current_and_older_public_releases_do_not_trigger_an_update() = runBlocking {
        for (tag in listOf("v0.1.0", "v0.0.9", "v0.1.0-beta.1")) {
            assertTrue(repository(mutableListOf()) { 200 to release(tag) }.checkLatestRelease() is UpdateCheckState.UpToDate)
        }
    }

    @Test fun transient_metadata_failure_is_retried_only_on_the_official_api() = runBlocking {
        val requests = mutableListOf<String>()
        val state = repository(requests) {
            if (it < 3) throw IOException("Timeout")
            200 to release()
        }.checkLatestRelease()
        assertTrue(state is UpdateCheckState.UpdateAvailable)
        assertEquals(List(3) { "https://api.github.com/repos/YbbLab/Droidance/releases/latest" } + metadataUrl, requests)
    }

    @Test fun metadata_network_failure_is_bounded() = runBlocking {
        val requests = mutableListOf<String>()
        val state = repository(requests) { 503 to "Unavailable" }.checkLatestRelease()
        assertEquals(3, requests.size)
        assertEquals(UpdateCheckError.NETWORK_ERROR, (state as UpdateCheckState.CheckFailed).reason)
    }

    @Test fun missing_release_and_rate_limits_do_not_contact_mirrors() = runBlocking {
        for ((status, expected) in listOf(404 to UpdateCheckError.NO_RELEASE_FOUND,
            403 to UpdateCheckError.RATE_LIMITED, 429 to UpdateCheckError.RATE_LIMITED)) {
            val requests = mutableListOf<String>()
            val state = repository(requests) { status to "Unavailable" }.checkLatestRelease()
            assertEquals(expected, (state as UpdateCheckState.CheckFailed).reason)
            assertEquals(1, requests.size)
        }
    }

    @Test fun invalid_release_versions_are_parse_failures() = runBlocking {
        val state = repository(mutableListOf()) { 200 to release("20261006-V13") }.checkLatestRelease()
        assertEquals(UpdateCheckError.PARSE_ERROR, (state as UpdateCheckState.CheckFailed).reason)
    }

    @Test fun localizedNotesAreIndependentOfThePublicEnglishReleaseBody() = runBlocking {
        val state = repository(mutableListOf()) { 200 to release() }.checkLatestRelease()
        val notes = (state as UpdateCheckState.UpdateAvailable).release.releaseNotes
        assertEquals("- 中文说明。", ReleaseNotes.forLanguage(notes, "zh"))
        assertEquals("- English notes.", ReleaseNotes.forLanguage(notes, "en"))
    }

    @Test fun notesMustMatchTheSelectedVersionAndApkAndContainBothLanguages() = runBlocking {
        for (bad in listOf(metadata.replace("v0.2.0", "v0.1.0"),
            metadata.replace("update.apk", "other.apk"),
            metadata.replace("- 中文说明。", ""),
            metadata.replace("\"zh\"", "\"fr\""))) {
            val state = repository(mutableListOf(), 200 to bad) { 200 to release() }.checkLatestRelease()
            assertEquals(UpdateCheckError.PARSE_ERROR, (state as UpdateCheckState.CheckFailed).reason)
        }
    }

    @Test fun releaseBodyCannotReplaceMissingOrForeignLocalizedMetadata() = runBlocking {
        for (bad in listOf(release().replace("release.json", "other.json"),
            release().replace("releases/download/v0.2.0/release.json", "releases/download/v0.1.0/release.json"),
            release().replace("https://github.com/YbbLab/Droidance/releases/download/v0.2.0/release.json", "https://example.com/release.json"))) {
            val requests = mutableListOf<String>()
            val state = repository(requests) { 200 to bad }.checkLatestRelease()
            assertEquals(UpdateCheckError.PARSE_ERROR, (state as UpdateCheckState.CheckFailed).reason)
            assertEquals(1, requests.size)
        }
    }

    @Test fun checkingAnInstalledVersionDoesNotNeedLocalizedMetadata() = runBlocking {
        val requests = mutableListOf<String>()
        val state = repository(requests) { 200 to release("v0.1.0") }.checkLatestRelease()
        assertTrue(state is UpdateCheckState.UpToDate)
        assertEquals(1, requests.size)
    }

    @Test fun malformed_metadata_is_not_retried_or_accepted() = runBlocking {
        val requests = mutableListOf<String>()
        val state = repository(requests) { 200 to "<html>error</html>" }.checkLatestRelease()
        assertEquals(UpdateCheckError.PARSE_ERROR, (state as UpdateCheckState.CheckFailed).reason)
        assertEquals(1, requests.size)
    }
}
