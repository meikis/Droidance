// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.model.llm

import com.droidance.core.model.llm.models.ToolParameter
import com.droidance.core.model.llm.models.toJsonSchema
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolParameterJsonSchemaTest {

    @Test fun minimumBoundsReachNestedItemsAndAdditionalPropertySchemas() {
        val schema = ToolParameter("object", "", properties = mapOf(
            "reviews" to ToolParameter("array", "", minItems = 2, items = ToolParameter("object", "", properties = mapOf(
                "finding" to ToolParameter("string", "", minLength = 2)
            ))),
            "labels" to ToolParameter("object", "", additionalPropertySchema = ToolParameter("string", "", minLength = 1))
        )).toJsonSchema().getValue("properties").jsonObject
        val reviews = schema.getValue("reviews").jsonObject
        assertEquals("2", reviews["minItems"]?.jsonPrimitive?.content)
        val finding = reviews.getValue("items").jsonObject.getValue("properties").jsonObject.getValue("finding").jsonObject
        assertEquals("2", finding["minLength"]?.jsonPrimitive?.content)
        val labels = schema.getValue("labels").jsonObject.getValue("additionalProperties").jsonObject
        assertEquals("1", labels["minLength"]?.jsonPrimitive?.content)
    }

    @Test fun absentMinimumBoundsPreserveSchemaAndExplicitZeroIsRetained() {
        assertEquals(setOf("type"), ToolParameter("string", "").toJsonSchema().keys)
        assertEquals(setOf("type", "items"), ToolParameter("array", "").toJsonSchema().keys)
        assertEquals("0", ToolParameter("string", "", minLength = 0).toJsonSchema()["minLength"]?.jsonPrimitive?.content)
        assertEquals("0", ToolParameter("array", "", minItems = 0).toJsonSchema()["minItems"]?.jsonPrimitive?.content)
    }

    @Test fun conditionalRequirementsReachTheModelWithoutUnsupportedSchemaKeywords() {
        val schema = ToolParameter("boolean", "Edit mode.", false,
            requiredWhen = mapOf("op" to listOf("type_text"))).toJsonSchema()
        assertEquals("Edit mode. Required when op is type_text.", schema.getValue("description").jsonPrimitive.content)
        assertEquals(setOf("type", "description"), schema.keys)
    }

    @Test
    fun arrayParameterWithoutItemsEmitsDefaultStringItemSchema() {
        val param = ToolParameter("array", "Array without explicit items")
        val schema = param.toJsonSchema()

        assertEquals("array", schema["type"]?.jsonPrimitive?.content)
        val items = schema["items"]?.jsonObject
        assertNotNull("Array schema must contain items for OpenAPI 3.0 compliance", items)
        assertEquals("string", items?.get("type")?.jsonPrimitive?.content)
    }

    @Test
    fun arrayParameterWithExplicitItemsPreservesConfiguredSchema() {
        val param = ToolParameter(
            type = "array",
            description = "Array with explicit items",
            items = ToolParameter("integer", "Item index")
        )
        val schema = param.toJsonSchema()

        assertEquals("array", schema["type"]?.jsonPrimitive?.content)
        val items = schema["items"]?.jsonObject
        assertNotNull(items)
        assertEquals("integer", items?.get("type")?.jsonPrimitive?.content)
        assertEquals("Item index", items?.get("description")?.jsonPrimitive?.content)
    }

    @Test
    fun objectParameterRendersPropertiesAndRequired() {
        val param = ToolParameter(
            type = "object",
            description = "Config map",
            properties = linkedMapOf(
                "key" to ToolParameter("string", "Key name", required = true),
                "optional_val" to ToolParameter("boolean", "Flag", required = false)
            )
        )
        val schema = param.toJsonSchema()

        assertEquals("object", schema["type"]?.jsonPrimitive?.content)
        val properties = schema["properties"]?.jsonObject
        assertNotNull(properties)
        assertEquals("string", properties?.get("key")?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals("boolean", properties?.get("optional_val")?.jsonObject?.get("type")?.jsonPrimitive?.content)

        val required = schema["required"]?.jsonArray?.map { it.jsonPrimitive.content }
        assertEquals(listOf("key"), required)
    }
}
