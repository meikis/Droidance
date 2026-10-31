// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.update

import com.droidance.update.model.DownloadError
import com.droidance.update.model.DownloadProgressState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.security.MessageDigest
import java.util.Collections

class UpdateDownloaderTest {
    @get:Rule val temporary = TemporaryFolder()
    private val url = "https://github.com/YbbLab/Droidance/releases/download/v0.2.0/update.apk"
    private val bytes = "signed APK fixture".toByteArray()
    private val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun client(
        requests: MutableList<String>,
        response: (Int) -> Pair<Int, ByteArray>
    ) = OkHttpClient.Builder().addInterceptor { chain ->
        requests.add(chain.request().url.toString())
        val (status, body) = response(requests.size)
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            .code(status).message("fixture").body(body.toResponseBody()).build()
    }.build()

    private fun requests() = Collections.synchronizedList(mutableListOf<String>())

    @Test fun direct_success_does_not_contact_a_mirror() = runBlocking {
        val requests = requests()
        val dir = temporary.newFolder()
        var verified = false
        val downloader = UpdateDownloader(client(requests) { 200 to bytes }, dir, {
            verified = it.readBytes().contentEquals(bytes)
            verified
        }, 0, repository = "YbbLab/Droidance")
        val states = downloader.download(url, "update.apk", hash, bytes.size.toLong()).toList()
        assertEquals(listOf(url), requests)
        assertTrue(verified)
        assertTrue(states.contains(DownloadProgressState.Verifying))
        assertArrayEquals(bytes, (states.last() as DownloadProgressState.ReadyToInstall).apkFile.readBytes())
        assertEquals(1, dir.listFiles()!!.size)
    }

    @Test fun transient_direct_failure_recovers_before_fallback() = runBlocking {
        val requests = requests()
        val downloader = UpdateDownloader(client(requests) {
            if (it < 3) throw IOException("Timeout")
            200 to bytes
        }, temporary.newFolder(), { true }, 0, repository = "YbbLab/Droidance")
        val states = downloader.download(url, "update.apk", hash, bytes.size.toLong()).toList()
        assertEquals(List(3) { url }, requests)
        assertTrue(states.last() is DownloadProgressState.ReadyToInstall)
    }

    @Test fun three_direct_failures_are_followed_by_a_verified_mirror_download() = runBlocking {
        val requests = requests()
        val downloader = UpdateDownloader(client(requests) { if (it < 4) 503 to bytes else 200 to bytes },
            temporary.newFolder(), { true }, 0, repository = "YbbLab/Droidance")
        val states = downloader.download(url, "update.apk", hash, bytes.size.toLong()).toList()
        assertEquals(List(3) { url } + "https://ghproxy.net/$url", requests)
        assertTrue(states.last() is DownloadProgressState.ReadyToInstall)
    }

    @Test fun failure_of_all_routes_is_bounded_and_removes_partial_files() = runBlocking {
        val requests = requests()
        val dir = temporary.newFolder()
        val downloader = UpdateDownloader(client(requests) { throw IOException("Disconnected") }, dir, { true }, 0, repository = "YbbLab/Droidance")
        val states = downloader.download(url, "update.apk", hash, bytes.size.toLong()).toList()
        assertEquals(4, requests.size)
        assertEquals(DownloadError.NETWORK_ERROR, (states.last() as DownloadProgressState.Failed).reason)
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test fun missing_release_is_not_retried_or_proxied() = runBlocking {
        val requests = requests()
        val downloader = UpdateDownloader(client(requests) { 404 to bytes }, temporary.newFolder(), { true }, 0, repository = "YbbLab/Droidance")
        val states = downloader.download(url, "update.apk", hash, bytes.size.toLong()).toList()
        assertEquals(1, requests.size)
        assertEquals(DownloadError.NETWORK_ERROR, (states.last() as DownloadProgressState.Failed).reason)
    }

    @Test fun a_tampered_mirror_response_cannot_become_installable() = runBlocking {
        val requests = requests()
        val dir = temporary.newFolder()
        var verifierCalled = false
        val downloader = UpdateDownloader(client(requests) {
            if (it < 4) 503 to bytes else 200 to ByteArray(bytes.size) { 42 }
        }, dir, { verifierCalled = true; true }, 0, repository = "YbbLab/Droidance")
        val states = downloader.download(url, "update.apk", hash, bytes.size.toLong()).toList()
        assertEquals(4, requests.size)
        assertEquals(DownloadError.CHECKSUM_MISMATCH, (states.last() as DownloadProgressState.Failed).reason)
        assertFalse(verifierCalled)
        assertFalse(states.any { it is DownloadProgressState.ReadyToInstall })
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test fun unverified_metadata_never_starts_a_download() = runBlocking {
        val requests = requests()
        val downloader = UpdateDownloader(client(requests) { 200 to bytes }, temporary.newFolder(), { true }, 0, repository = "YbbLab/Droidance")
        for (digest in listOf(null, "", "sha256:$hash", "bad")) {
            val result = downloader.download(url, "update.apk", digest, bytes.size.toLong()).toList().last()
            assertEquals(DownloadError.MISSING_CHECKSUM, (result as DownloadProgressState.Failed).reason)
        }
        assertTrue(requests.isEmpty())
    }

    @Test fun unexpected_size_is_rejected_without_retry() = runBlocking {
        val requests = requests()
        val downloader = UpdateDownloader(client(requests) { 200 to bytes }, temporary.newFolder(), { true }, 0, repository = "YbbLab/Droidance")
        val result = downloader.download(url, "update.apk", hash, bytes.size.toLong() + 1).toList().last()
        assertEquals(DownloadError.CHECKSUM_MISMATCH, (result as DownloadProgressState.Failed).reason)
        assertEquals(1, requests.size)
    }

    @Test fun wrong_apk_identity_is_rejected_after_checksum_verification() = runBlocking {
        val requests = requests()
        val dir = temporary.newFolder()
        val downloader = UpdateDownloader(client(requests) { 200 to bytes }, dir, { false }, 0, repository = "YbbLab/Droidance")
        val states = downloader.download(url, "update.apk", hash, bytes.size.toLong()).toList()
        assertEquals(DownloadError.INVALID_APK, (states.last() as DownloadProgressState.Failed).reason)
        assertEquals(1, requests.size)
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test fun local_storage_failure_does_not_switch_network_routes() = runBlocking {
        val requests = requests()
        val downloader = UpdateDownloader(client(requests) { 200 to bytes }, temporary.newFile(), { true }, 0, repository = "YbbLab/Droidance")
        val result = downloader.download(url, "update.apk", hash, bytes.size.toLong()).toList().last()
        assertEquals(DownloadError.STORAGE_ERROR, (result as DownloadProgressState.Failed).reason)
        assertTrue(requests.isEmpty())
    }

    @Test fun cancellation_during_retry_stops_all_further_requests_and_cleans_up() = runBlocking {
        val requests = requests()
        val firstRequest = CompletableDeferred<Unit>()
        val dir = temporary.newFolder()
        val downloader = UpdateDownloader(client(requests) {
            firstRequest.complete(Unit)
            throw IOException("Disconnected")
        }, dir, { true }, 60_000, repository = "YbbLab/Droidance")
        val states = Collections.synchronizedList(mutableListOf<DownloadProgressState>())
        val job = launch { downloader.download(url, "update.apk", hash, bytes.size.toLong()).collect { states.add(it) } }
        withTimeout(5_000) { firstRequest.await() }
        withTimeout(2_000) { job.cancelAndJoin() }
        assertEquals(1, requests.size)
        assertFalse(states.any { it is DownloadProgressState.Failed || it is DownloadProgressState.ReadyToInstall })
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test fun only_https_assets_in_the_project_release_are_accepted() {
        assertTrue(isReleaseAsset(url, "update.apk", repository = "YbbLab/Droidance"))
        for (untrusted in listOf(
            url.replace("https:", "http:"),
            url.replace("github.com", "github.com.evil.example"),
            url.replace("YbbLab", "other"),
            url.replace("Droidance", "other"),
            url.replace("github.com", "user@github.com"),
            "$url?redirect=evil", "$url#fragment"
        )) assertFalse(untrusted, isReleaseAsset(untrusted, "update.apk", repository = "YbbLab/Droidance"))
        assertFalse(isReleaseAsset(url, "../update.apk", repository = "YbbLab/Droidance"))
    }
}
