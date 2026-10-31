// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.program

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProgramRecordAggregationTest {
    @Test fun paginatedRecordsAreAggregatedInsideOneProgramWithoutRawPageOutput() {
        val offsets = mutableListOf<Int>()
        val outputs = mutableListOf<JsonObject>()
        val pages = listOf(
            """{"offset":0,"count":2,"has_more":true,"truncated":true,"rows":[{"_id":1,"category":"a","value":2},{"_id":2,"category":"b","value":3}]}""",
            """{"offset":2,"count":1,"has_more":false,"truncated":false,"rows":[{"_id":3,"category":"a","value":5}]}"""
        )
        ProgramEngine().execute("""
            var offset=0, count=0, sum=0, groups={};
            while(true) {
                var r=android.call('android_read',{op:'content',uri:'content://generic/records',
                    projection:['_id','category','value'],limit:2,offset:offset});
                if(r.executionStatus!=='SUCCEEDED') throw Error('read failed');
                var page=JSON.parse(r.outputText);
                if(page.offset!==offset || page.count!==page.rows.length) throw Error('inconsistent page');
                for(var i=0;i<page.rows.length;i++) {
                    var row=page.rows[i]; count++; sum+=row.value;
                    groups[row.category]=(groups[row.category]||0)+1;
                }
                offset+=page.count;
                if(!page.has_more) break;
                if(page.count===0) throw Error('pagination made no progress');
            }
            print({count:count,sum:sum,groups:groups,complete:true});
        """.trimIndent(), { operation, arguments ->
            val input = Json.parseToJsonElement(arguments).jsonObject
            when (operation) {
                "android" -> {
                    assertEquals("android_read", input.getValue("tool").jsonPrimitive.content)
                    val args = input.getValue("args").jsonObject
                    assertEquals(3, args.getValue("projection").jsonArray.size)
                    val offset = args.getValue("offset").jsonPrimitive.int
                    offsets += offset
                    val page = pages[if (offset == 0) 0 else 1]
                    buildJsonObject { put("value", buildJsonObject {
                        put("executionStatus", "SUCCEEDED")
                        put("outputText", "[ANDROID_CONTENT_RESULT]\n$page")
                    }) }.toString()
                }
                "stdout" -> {
                    outputs += Json.parseToJsonElement(input.getValue("text").jsonPrimitive.content).jsonObject
                    "{\"value\":null}"
                }
                else -> error("Unexpected capability: $operation")
            }
        }, { false })
        assertEquals(listOf(0, 2), offsets)
        val output = outputs.single()
        assertEquals(3, output.getValue("count").jsonPrimitive.int)
        assertEquals(10, output.getValue("sum").jsonPrimitive.int)
        assertEquals(2, output.getValue("groups").jsonObject.getValue("a").jsonPrimitive.int)
        assertTrue(output.getValue("complete").jsonPrimitive.boolean)
        assertFalse(output.containsKey("rows"))
    }
}
