// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.model.llm

import com.droidance.core.model.llm.models.*
import com.droidance.core.model.llm.session.ModelTransport
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ReasoningCapabilityTest {
    @Test fun explicitReasoningPreferencePersistsAndIsFrozenInTheTurnPlan() {
        val fixed = config().copy(reasoningPreference = ReasoningEffort.HIGH)
        val restored = Json.decodeFromString<ProviderConfig>(Json.encodeToString(fixed))
        assertEquals(fixed, restored)
        val plan = ModelStreamPlan.fromProvider(restored, "model-a", false)
        assertEquals(ReasoningEffort.HIGH, plan.reasoningPreference)
        assertEquals(ReasoningEffort.HIGH, plan.reasoningEffort)
        assertNotEquals(plan.providerSnapshotRef, ModelStreamPlan.fromProvider(config(), "model-a", false).providerSnapshotRef)
        assertNull(ModelStreamPlan.fromProvider(config(), "model-a", false).reasoningPreference)
    }

    private val catalog = ReasoningCatalog("""{"gateways":[{"hosts":["gateway.example"],"parameter":"REASONING_OBJECT"}],"models":[{"ids":["model-a"],"efforts":["low","high"]}]}""")
    private fun config() = ProviderConfig("p", "p", ApiType.OPENAI_COMPATIBLE,
        ModelTransport.GENERIC_CHAT_COMPLETIONS, baseUrl = "https://gateway.example/v1", apiKey = "key", defaultModel = "model-a")

    @Test fun removedAdvancedSettingsAreNeitherRestoredNorSaved() {
        val oldPayload = Json.encodeToJsonElement(config()).jsonObject.toMutableMap().apply {
            put("planningReasoningEffort", JsonPrimitive("HIGH"))
            put("executionReasoningEffort", JsonPrimitive("MAX"))
            put("reasoningEffort", JsonPrimitive("HIGH"))
            put("imageDetailPreference", JsonPrimitive("LOW"))
            put("imageDetailAllowFallback", JsonPrimitive(false))
            put("extraBodyJson", JsonPrimitive("{\"model\":\"injected-model\"}"))
        }
        val json = Json { ignoreUnknownKeys = true }
        val restored = json.decodeFromString<ProviderConfig>(JsonObject(oldPayload).toString())
        assertEquals(config(), restored)
        assertEquals(ReasoningEffort.DEFAULT, ModelStreamPlan.fromProvider(restored, "model-a", false).reasoningEffort)
        val saved = json.encodeToString(restored)
        assertFalse(saved.contains("planningReasoningEffort"))
        assertFalse(saved.contains("executionReasoningEffort"))
        assertFalse(saved.contains("reasoningEffort"))
        assertFalse(saved.contains("imageDetailPreference"))
        assertFalse(saved.contains("imageDetailAllowFallback"))
        assertFalse(saved.contains("extraBodyJson"))
    }

    @Test fun unknownModelsAndCorruptCatalogsUseDefaults() {
        assertEquals(listOf(ReasoningEffort.DEFAULT), catalog.capability(config().baseUrl, "unknown").options)
        assertEquals(ReasoningEffort.DEFAULT, catalog.capability(config().baseUrl, "model-a").resolve(ReasoningEffort.MAX))
        assertEquals(listOf(ReasoningEffort.DEFAULT), ReasoningCatalog("not json").capability("bad url", "model-a").options)
    }

    @Test fun openRouterMetadataFiltersEffortsAndMandatoryThinking() {
        val metadata = """{"data":[{"id":"model-a","reasoning":{"supported_efforts":["high","none","low"],"mandatory":true}}]}"""
        val result = ReasoningMetadata.parse(metadata, "model-a", ReasoningParameter.REASONING_OBJECT)!!
        assertEquals(listOf(ReasoningEffort.DEFAULT, ReasoningEffort.LOW, ReasoningEffort.HIGH), result.options)
        assertEquals(ReasoningParameter.REASONING_OBJECT, result.parameter)
    }

    @Test fun missingNullAndInvalidEffortListsRemainDistinct() {
        fun parse(reasoning: String) = ReasoningMetadata.parse("""{"id":"model-a","reasoning":$reasoning}""", "model-a", ReasoningParameter.REASONING_OBJECT)
        assertEquals(listOf(ReasoningEffort.DEFAULT), parse("{}")!!.options)
        assertEquals(ReasoningEffort.entries.toList(), parse("""{"supported_efforts":null}""")!!.options)
        assertNull(parse("""{"supported_efforts":["future-level"]}"""))
        assertNull(ReasoningMetadata.parse("broken", "model-a", ReasoningParameter.REASONING_OBJECT))
        assertNull(ReasoningMetadata.parse("""{"data":[{"id":"model-b"}]}""", "model-a", ReasoningParameter.REASONING_OBJECT))
    }

    @Test fun explicitCapabilitiesAndUnsupportedParameterMetadataOverrideCatalogs() {
        val native = """{"id":"model-a","capabilities":{"effort":{"supported":true,"high":{"supported":true},"max":{"supported":false}}}}"""
        assertEquals(listOf(ReasoningEffort.DEFAULT, ReasoningEffort.HIGH), ReasoningMetadata.parse(native,"model-a", ReasoningParameter.REASONING_EFFORT)!!.options)
        val plain = """{"id":"model-a","supported_parameters":["temperature"]}"""
        assertEquals(listOf(ReasoningEffort.DEFAULT), ReasoningMetadata.parse(plain,"model-a", ReasoningParameter.REASONING_OBJECT)!!.options)
        val incomplete = """{"id":"model-a","supported_parameters":["reasoning"]}"""
        assertNull(ReasoningMetadata.parse(incomplete,"model-a", ReasoningParameter.REASONING_OBJECT))
    }

    @Test fun rejectedConfigurationStaysDefaultAcrossMetadataRefreshAndRestart() {
        var now = 100_000L
        val cache = ReasoningCapabilityCache(catalog, now = { now })
        val config = config()
        cache.recordRejected(config, "model-a")
        cache.recordMetadata(config, "model-a", ReasoningCapability(listOf(ReasoningEffort.MAX)))
        val encoded = Json.encodeToString(cache.snapshot())
        val restarted = ReasoningCapabilityCache(catalog, Json.decodeFromString(encoded), now = { now })
        assertTrue(restarted.capability(config, "model-a").forceDefault)
        assertEquals(listOf(ReasoningEffort.DEFAULT), restarted.capability(config, "model-a").options)
        assertFalse(restarted.capability(config.copy(apiKey = "another-key"), "model-a").forceDefault)
        assertFalse(restarted.capability(config.copy(baseUrl = "https://other.example/v1"), "model-a").forceDefault)
        assertFalse(restarted.capability(config.copy(models = listOf("new-model")), "model-a").forceDefault)
        assertFalse(restarted.capability(config, "model-b").forceDefault)
        now += 24 * 60 * 60 * 1_000L
        assertFalse(restarted.capability(config, "model-a").forceDefault)
    }

    @Test fun metadataOverridesCatalogAndFailuresDoNotRepeatedlyQuery() {
        var now = 100_000L
        val cache = ReasoningCapabilityCache(catalog, now = { now })
        val config = config()
        cache.recordMetadata(config, "model-a", ReasoningCapability())
        assertEquals(listOf(ReasoningEffort.DEFAULT), cache.capability(config, "model-a").options)
        assertTrue(cache.shouldRefresh(config))
        cache.recordAttempt(config)
        assertFalse(cache.shouldRefresh(config))
        now += 60 * 60 * 1_000L
        assertTrue(cache.shouldRefresh(config))
        now += 24 * 60 * 60 * 1_000L
        assertEquals(listOf(ReasoningEffort.DEFAULT, ReasoningEffort.LOW, ReasoningEffort.HIGH), cache.capability(config,"model-a").options)
    }

    @Test fun requestDefaultsStripSavedEffortOverridesAndKeepUnrelatedFields() {
        val request = Json.parseToJsonElement("""{"model":"m","reasoning_effort":"max","reasoning":{"effort":"high","summary":"auto"},"temperature":0.5}""").jsonObject
        val normal = ReasoningRequestPolicy.apply(request, ReasoningEffort.LOW, ReasoningParameter.REASONING_OBJECT)
        assertNull(normal["reasoning_effort"])
        assertEquals("low", normal["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        val omitted = ReasoningRequestPolicy.apply(request, ReasoningEffort.DEFAULT, ReasoningParameter.REASONING_EFFORT)
        assertNull(omitted["reasoning_effort"])
        assertNull(omitted["reasoning"]!!.jsonObject["effort"])
        val fallback = ReasoningRequestPolicy.apply(request, ReasoningEffort.HIGH, ReasoningParameter.REASONING_OBJECT, true)
        assertNull(fallback["reasoning"])
        assertNull(fallback["reasoning_effort"])
        assertEquals(request["temperature"], fallback["temperature"])
    }

    @Test fun optionalDiscoveryFailureDoesNotThrowOrFollowRedirects() = runBlocking {
        var count = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            count++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(302).message("redirect").header("Location", "https://other.example/models")
                .body("redirect".toResponseBody()).build()
        }.build()
        assertNull(ReasoningModelDiscovery(http).fetch(config()))
        assertEquals(1, count)
        assertNull(ReasoningModelDiscovery(http).fetch(config().copy(baseUrl = "not a url")))
        assertEquals(1, count)
    }

    @Test fun bundledProfilesHaveDifferentModelEffortRanges() {
        var directory = File(System.getProperty("user.dir"))
        while (!File(directory, "app/src/main/assets/model_capabilities/reasoning.json").exists()) directory = directory.parentFile ?: error("Missing capability catalog")
        val bundled = ReasoningCatalog(File(directory, "app/src/main/assets/model_capabilities/reasoning.json").readText())
        val older = bundled.capability("https://api.openai.com/v1", "gpt-5")
        assertTrue(ReasoningEffort.MINIMAL in older.options)
        assertFalse(ReasoningEffort.NONE in older.options)
        assertFalse(ReasoningEffort.XHIGH in older.options)
        val newer = bundled.capability("https://api.openai.com/v1", "gpt-6.1-sol")
        assertTrue(ReasoningEffort.MAX in newer.options)
        assertFalse(ReasoningEffort.NONE in newer.options)
        assertEquals(ReasoningParameter.REASONING_OBJECT, bundled.capability("https://openrouter.ai/api/v1", "openai/gpt-5.6-luna").parameter)
        // Gateway-declared efforts cover aliases such as a coding-plan model name.
        val ark = bundled.capability("https://ark.cn-beijing.volces.com/api/coding/v3", "ark-code-latest")
        assertTrue(ReasoningEffort.LOW in ark.options && ReasoningEffort.HIGH in ark.options)
        assertFalse(ReasoningEffort.XHIGH in ark.options)
    }

    @Test fun modelProfilesMatchPrefixesBeforeGatewayDefaults() {
        val catalog = ReasoningCatalog("""{"gateways":[{"hosts":["gw.example"],"efforts":["low","medium"]}],
            "models":[{"prefixes":["family-"],"efforts":["high"]}]}""")
        assertEquals(listOf(ReasoningEffort.DEFAULT, ReasoningEffort.HIGH), catalog.capability("https://gw.example/v1", "family-2").options)
        assertEquals(listOf(ReasoningEffort.DEFAULT, ReasoningEffort.LOW, ReasoningEffort.MEDIUM), catalog.capability("https://gw.example/v1", "other").options)
        assertEquals(listOf(ReasoningEffort.DEFAULT), catalog.capability("https://unknown.example/v1", "other").options)
    }
}
