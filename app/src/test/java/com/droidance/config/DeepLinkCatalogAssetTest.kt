// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.config

import com.droidance.core.agent.config.*
import com.droidance.core.device.config.*

import com.droidance.config.*

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepLinkCatalogAssetTest {
    private val routeFile = projectFile("app/src/main/assets/deeplinks/routes.jsonl")
    private val routes = routeFile.readLines()
        .filter(String::isNotBlank)
        .map { Json.parseToJsonElement(it).jsonObject }

    @Test
    fun catalogIdsAndRoutesAreUnique() {
        val ids = routes.map { it.getValue("id").jsonPrimitive.content }
        val routeKeys = routes.map {
            listOf(
                it.getValue("packageName").jsonPrimitive.content,
                it.getValue("action").jsonPrimitive.content,
                it.getValue("uriTemplate").jsonPrimitive.content
            )
        }

        assertEquals(ids.size, ids.toSet().size)
        assertEquals(routeKeys.size, routeKeys.toSet().size)
    }

    @Test
    fun officialLabelAlwaysHasAnOfficialSource() {
        routes.filter { it.getValue("sourceType").jsonPrimitive.content == "official" }
            .forEach { route ->
                val id = route.getValue("id").jsonPrimitive.content
                assertEquals(id, "official", route.getValue("sourceType").jsonPrimitive.content)
                assertTrue(id, route.getValue("sourceUrl").jsonPrimitive.content.startsWith("https://"))
            }
    }

    @Test
    fun catalogDoesNotShipKnownDeadOrDeprecatedRoutes() {
        routes.forEach { route ->
            val id = route.getValue("id").jsonPrimitive.content
            val uri = route.getValue("uriTemplate").jsonPrimitive.content
            val failureCount = route.getValue("failureCount").jsonPrimitive.int

            assertFalse(id, uri.startsWith("line://"))
            assertFalse(id, uri == "tg://msg")
            assertFalse(id, uri.startsWith("uber://?action=setPickup"))
            assertFalse(id, uri.startsWith("weixin://dl/"))
            assertFalse(id, uri.startsWith("alipay://"))
            assertFalse(id, uri == "xhsdiscover://publish")
            assertFalse(id, uri == "sinaweibo://")
            assertTrue(id, failureCount < 5)
        }
    }

    @Test
    fun updatedRoutesUseVerifiedSchemes() {
        val routesById = routes.associateBy { it.getValue("id").jsonPrimitive.content }

        assertEquals("weixin://", routesById.getValue("com.tencent.mm:首页:82").getValue("uriTemplate").jsonPrimitive.content)
        assertEquals("sinaweibo://gotohome", routesById.getValue("com.sina.weibo:首页:96").getValue("uriTemplate").jsonPrimitive.content)
        assertEquals("xhsdiscover://post", routesById.getValue("com.xingin.xhs:发布:107").getValue("uriTemplate").jsonPrimitive.content)
        assertEquals("twitter://timeline", routesById.getValue("com.twitter.android:首页:232").getValue("uriTemplate").jsonPrimitive.content)
        assertTrue(
            routes.filter { it.getValue("packageName").jsonPrimitive.content == "com.eg.android.AlipayGphone" }
                .all { it.getValue("uriTemplate").jsonPrimitive.content.startsWith("alipays://") }
        )
    }

    @Test
    fun currentJdTaobaoAndMeituanRoutesUseObservedEntryHandlers() {
        val routesById = routes.associateBy { it.getValue("id").jsonPrimitive.content }

        assertFalse(routesById.containsKey("com.jingdong.app.mall:搜索:3"))
        assertFalse(routesById.containsKey("com.jingdong.app.mall:搜索:4"))
        assertFalse(routesById.containsKey("com.taobao.taobao:搜索:9"))
        assertEquals(
            "openapp.jdmobile://virtual?params=%7B%22category%22%3A%22jump%22%2C%22des%22%3A%22productList%22%2C%22keyWord%22%3A%22{keyword}%22%2C%22from%22%3A%22search%22%7D",
            routesById.getValue("com.jingdong.app.mall:搜索:2-v3")
                .getValue("uriTemplate").jsonPrimitive.content
        )
        assertEquals(
            "ProductListActivity",
            routesById.getValue("com.jingdong.app.mall:搜索:2-v3")
                .getValue("verification").jsonObject.getValue("activityContains").jsonPrimitive.content
        )
        assertTrue(
            routesById.getValue("com.jingdong.app.mall:搜索:2-v3")
                .getValue("appAliases").jsonArray
                .map { it.jsonPrimitive.content }
                .containsAll(listOf("JD", "Jingdong"))
        )
        assertEquals(
            "taobao://s.taobao.com/search?q={keyword}",
            routesById.getValue("com.taobao.taobao:搜索:9-v3")
                .getValue("uriTemplate").jsonPrimitive.content
        )
        mapOf(
            "首页" to "taobao://m.taobao.com/index.htm",
            "购物车" to "taobao://h5.m.taobao.com/awp/base/cart.htm",
            "我的订单" to "taobao://taobao.trade.order/orderlist.htm",
            "扫一扫" to "taobao://m.taobao.com/scancode/scan",
            "消息" to "taobao://m.taobao.com/message/categorylist"
        ).forEach { (action, uri) ->
            val route = routesById.values.first { it.getValue("action").jsonPrimitive.content == action && it.getValue("appName").jsonPrimitive.content == "淘宝" }
            assertEquals(uri, route.getValue("uriTemplate").jsonPrimitive.content)
        }
        val taobaoVerification = routesById
            .filterKeys { it.startsWith("com.taobao.taobao:") }
            .filterValues { it["verification"]?.jsonObject?.isNotEmpty() == true }
        assertEquals(6, taobaoVerification.size)
        assertEquals(
            "SearchActivity",
            routesById.getValue("com.taobao.taobao:搜索:9-v3")
                .getValue("verification").jsonObject.getValue("activityContains").jsonPrimitive.content
        )
        assertTrue(
            taobaoVerification.getValue("com.taobao.taobao:购物车:10-v3")
                .getValue("verification").jsonObject.getValue("activityContains").jsonPrimitive.content
                .contains("CartActivity")
        )
        assertFalse(routes.any { it.getValue("id").jsonPrimitive.content == "com.taobao.taobao:我的:14-v2" })
        assertFalse(routes.any { it.getValue("uriTemplate").jsonPrimitive.content in setOf("taobao://home", "taobao://shop/cart", "taobao://myorder", "taobao://tb/scan", "taobao://message", "taobao://myTaobao") })
        assertTrue(
            routesById.getValue("com.taobao.taobao:搜索:9-v3")
                .getValue("sourceUrl").jsonPrimitive.content
                .startsWith("https://developer.alibaba.com/")
        )
        assertEquals(
            "imeituan://www.meituan.com/food/homepage",
            routesById.getValue("com.sankuai.meituan:外卖:36-v3")
                .getValue("uriTemplate").jsonPrimitive.content
        )
        val meituanFoodAliases = routesById.getValue("com.sankuai.meituan:外卖:36-v3")
            .getValue("actionAliases").jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertTrue(meituanFoodAliases.containsAll(setOf("food", "takeout", "delivery", "waimai")))
        assertEquals(
            "imeituan://www.meituan.com/foodsearch/result?keyword={keyword}",
            routesById.getValue("com.sankuai.meituan:美食搜索:40b-v3")
                .getValue("uriTemplate").jsonPrimitive.content
        )
        assertEquals(
            "FoodSearchResultActivity",
            routesById.getValue("com.sankuai.meituan:美食搜索:40b-v3")
                .getValue("verification").jsonObject.getValue("activityContains").jsonPrimitive.content
        )
        val idlefishSearch = routesById.getValue("com.taobao.idlefish:搜索:21-v2")
        assertEquals(
            "fleamarket://search_items?keyword={keyword}&searchType=0",
            idlefishSearch.getValue("uriTemplate").jsonPrimitive.content
        )
        assertFalse(routes.any { it.getValue("uriTemplate").jsonPrimitive.content == "fleamarket://search?keyword={keyword}" })
        assertEquals(
            "SearchResultActivity",
            idlefishSearch.getValue("verification").jsonObject
                .getValue("activityContains").jsonPrimitive.content
        )
        assertTrue(
            idlefishSearch.getValue("verification").jsonObject
                .getValue("textAny").jsonArray
                .map { it.jsonPrimitive.content }
                .contains("价格")
        )
        assertTrue(
            idlefishSearch.getValue("appAliases").jsonArray
                .map { it.jsonPrimitive.content }
                .containsAll(listOf("Idlefish", "Xianyu", "Goofish"))
        )
    }

    @Test
    fun indexCountMatchesCatalog() {
        val index = projectFile("app/src/main/assets/deeplinks/INDEX.md").readText()

        assertTrue(index.contains("contains ${routes.size} routes"))
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
