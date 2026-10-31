// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.screens

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderBrandIconCatalogTest {
    private val catalog = ProviderBrandIconCatalog.decode(
        projectFile("app/src/main/assets/provider_icons/catalog.json").readText()
    )

    @Test
    fun apiDomainTakesPriorityOverModelFamilyInDisplayName() {
        val brand = ProviderBrandIconCatalog.match(
            catalog = catalog,
            providerName = "OpenRouter · DeepSeek",
            baseUrl = "https://openrouter.ai/api/v1"
        )

        assertEquals("openrouter", brand?.slug)
    }

    @Test
    fun bundledAndCustomProvidersResolveFromDomainOrAlias() {
        assertEquals(
            "volcengine",
            ProviderBrandIconCatalog.match(
                catalog,
                providerName = "Volces Ark",
                baseUrl = "https://ark.cn-beijing.volces.com/api/coding/v3"
            )?.slug
        )
        assertEquals(
            "deepseek",
            ProviderBrandIconCatalog.match(
                catalog,
                providerName = "DeepSeek 备用",
                baseUrl = "https://relay.example.com/v1"
            )?.slug
        )
        assertNull(
            ProviderBrandIconCatalog.match(
                catalog,
                providerName = "My private endpoint",
                baseUrl = "https://relay.example.com/v1"
            )
        )
    }

    @Test
    fun everyCatalogEntryHasDefaultBundledAsset() {
        catalog.brands.forEach { brand ->
            val resource = ProviderBrandIconCatalog.drawableResource(brand.drawable)
            assertNotNull("Missing resource binding for ${brand.slug}", resource)
            assertTrue("Invalid resource binding for ${brand.slug}", resource != 0)
            assertTrue(
                "Missing default asset for ${brand.slug}",
                projectFile("app/src/main/res/drawable-nodpi/${brand.drawable}.webp").isFile
            )
        }
        assertNull(ProviderBrandIconCatalog.drawableResource("unknown_drawable"))
    }

    @Test
    fun colorBrandAssetsDoNotSwitchBackToMonochromeInDarkTheme() {
        val colorBrands = listOf(
            "alibabacloud",
            "anthropic",
            "azureai",
            "bedrock",
            "cohere",
            "deepseek",
            "gemini",
            "kimi",
            "minimax",
            "mistral",
            "openrouter",
            "qwen",
            "siliconcloud",
            "volcengine",
            "zhipu"
        )

        colorBrands.forEach { slug ->
            val drawable = catalog.brands.first { it.slug == slug }.drawable
            val darkAsset = File(projectFile("app/src/main/res"), "drawable-night-nodpi/$drawable.webp")
            // Android uses the default resource when no night override is bundled.
            if (!darkAsset.isFile) return@forEach
            assertArrayEquals(
                "Light and dark variants should preserve the same brand colors for $slug",
                projectFile("app/src/main/res/drawable-nodpi/$drawable.webp").readBytes(),
                darkAsset.readBytes()
            )
        }
    }

    private fun projectFile(path: String): File {
        val userDir = System.getProperty("user.dir") ?: "."
        var current = File(userDir).absoluteFile
        while (true) {
            val candidate = File(current, path)
            if (candidate.exists()) return candidate
            current = current.parentFile ?: break
        }
        error("Cannot locate $path from $userDir")
    }
}
