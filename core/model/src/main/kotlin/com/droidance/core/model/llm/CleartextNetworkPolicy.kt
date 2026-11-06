// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0
package com.droidance.core.model.llm

import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy

/** Cleartext is limited to local destinations, including redirects and stored endpoints. */
object CleartextNetworkPolicy {
    fun configure(builder: OkHttpClient.Builder): OkHttpClient.Builder = builder.apply {
        val client = build()
        if (client.interceptors.none { it is DestinationCheck }) addInterceptor(DestinationCheck(client.dns))
        if (client.networkInterceptors.none { it is ConnectionCheck }) addNetworkInterceptor(ConnectionCheck(client.dns))
    }

    internal fun isLocal(address: InetAddress): Boolean {
        val bytes = address.address
        val first = bytes[0].toInt() and 255
        val second = bytes[1].toInt() and 255
        return address.isLoopbackAddress || address.isLinkLocalAddress ||
            if (bytes.size == 4) first == 10 || (first == 172 && second in 16..31) ||
                (first == 192 && second == 168) || (first == 100 && second in 64..127)
            else first and 254 == 252
    }

    private fun requireLocal(host: String, dns: Dns) {
        val addresses = dns.lookup(host)
        if (addresses.isEmpty() || addresses.any { !isLocal(it) }) {
            throw IOException("Cleartext HTTP requires a local network destination")
        }
    }

    private class DestinationCheck(private val dns: Dns) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val url = chain.request().url
            if (!url.isHttps) {
                requireLocal(url.host, dns)
            }
            return chain.proceed(chain.request())
        }
    }

    private class ConnectionCheck(private val dns: Dns) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            if (!chain.request().url.isHttps) {
                val route = chain.connection()?.route()
                // Check the socket actually selected by OkHttp, including redirected requests.
                if (route == null || route.proxy.type() != Proxy.Type.DIRECT ||
                    route.socketAddress.address?.let(::isLocal) != true) {
                    throw IOException("Cleartext HTTP requires a direct local network connection")
                }
            }
            val response = chain.proceed(chain.request())
            try {
                if (response.isRedirect) {
                    response.header("Location")?.let(chain.request().url::resolve)?.let { target ->
                        if (!target.isHttps) requireLocal(target.host, dns)
                    }
                }
                return response
            } catch (error: IOException) {
                response.close()
                throw error
            }
        }
    }
}
