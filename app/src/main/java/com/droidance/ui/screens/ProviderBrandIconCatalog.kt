// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.screens

import android.content.Context
import androidx.annotation.DrawableRes
import com.droidance.R
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.util.Locale

@Serializable
internal data class ProviderBrandIconCatalogDocument(
    val source: String,
    @SerialName("source_url") val sourceUrl: String,
    @SerialName("source_version") val sourceVersion: String,
    val license: String,
    val brands: List<ProviderBrandIcon>
)

@Serializable
internal data class ProviderBrandIcon(
    val slug: String,
    val drawable: String,
    val aliases: List<String>,
    val domains: List<String>
)

/** Resolves provider identity from its API endpoint first, then its user-facing name. */
internal object ProviderBrandIconCatalog {
    private val json = Json { ignoreUnknownKeys = true }
    private val drawableResources = mapOf(
        "provider_deepseek" to R.drawable.provider_deepseek,
        "provider_openrouter" to R.drawable.provider_openrouter,
        "provider_volcengine" to R.drawable.provider_volcengine,
        "provider_openai" to R.drawable.provider_openai,
        "provider_anthropic" to R.drawable.provider_anthropic,
        "provider_gemini" to R.drawable.provider_gemini,
        "provider_ollama" to R.drawable.provider_ollama,
        "provider_minimax" to R.drawable.provider_minimax,
        "provider_qwen" to R.drawable.provider_qwen,
        "provider_kimi" to R.drawable.provider_kimi,
        "provider_zhipu" to R.drawable.provider_zhipu,
        "provider_siliconcloud" to R.drawable.provider_siliconcloud,
        "provider_mistral" to R.drawable.provider_mistral,
        "provider_groq" to R.drawable.provider_groq,
        "provider_cohere" to R.drawable.provider_cohere,
        "provider_azureai" to R.drawable.provider_azureai,
        "provider_bedrock" to R.drawable.provider_bedrock,
        "provider_alibabacloud" to R.drawable.provider_alibabacloud,
        "provider_xai" to R.drawable.provider_xai,
        "provider_tencentcloud" to R.drawable.provider_tencentcloud,
        "provider_hunyuan" to R.drawable.provider_hunyuan,
        "provider_baidu" to R.drawable.provider_baidu,
        "provider_stepfun" to R.drawable.provider_stepfun,
        "provider_nvidia" to R.drawable.provider_nvidia,
        "provider_together" to R.drawable.provider_together,
        "provider_fireworks" to R.drawable.provider_fireworks,
        "provider_huggingface" to R.drawable.provider_huggingface,
        "provider_modelscope" to R.drawable.provider_modelscope,
        "provider_novita" to R.drawable.provider_novita
    )

    @Volatile
    private var cached: ProviderBrandIconCatalogDocument? = null

    fun resolveDrawable(context: Context, providerName: String, baseUrl: String): Int? {
        val brand = match(load(context), providerName, baseUrl) ?: return null
        return drawableResource(brand.drawable)
    }

    @DrawableRes
    internal fun drawableResource(name: String): Int? = drawableResources[name]

    internal fun decode(content: String): ProviderBrandIconCatalogDocument =
        json.decodeFromString(content)

    internal fun match(
        catalog: ProviderBrandIconCatalogDocument,
        providerName: String,
        baseUrl: String
    ): ProviderBrandIcon? {
        val host = extractHost(baseUrl)
        if (host != null) {
            catalog.brands
                .flatMap { brand -> brand.domains.map { domain -> brand to domain.lowercase(Locale.ROOT) } }
                .filter { (_, domain) -> host == domain || host.endsWith(".$domain") }
                .maxByOrNull { (_, domain) -> domain.length }
                ?.first
                ?.let { return it }
        }

        val normalizedName = normalize(providerName)
        return catalog.brands
            .flatMap { brand -> brand.aliases.map { alias -> brand to normalize(alias) } }
            .filter { (_, alias) -> alias.isNotBlank() && normalizedName.contains(alias) }
            .maxByOrNull { (_, alias) -> alias.length }
            ?.first
    }

    private fun load(context: Context): ProviderBrandIconCatalogDocument {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: context.assets.open("provider_icons/catalog.json")
                .bufferedReader()
                .use { decode(it.readText()) }
                .also { cached = it }
        }
    }

    private fun extractHost(baseUrl: String): String? = runCatching {
        val candidate = baseUrl.trim().let { url ->
            if ("://" in url) url else "https://$url"
        }
        URI(candidate).host?.lowercase(Locale.ROOT)?.trimEnd('.')
    }.getOrNull()

    private fun normalize(value: String): String = value
        .lowercase(Locale.ROOT)
        .filter { it.isLetterOrDigit() }
}
