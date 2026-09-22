package com.jarves.mh.mcp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class McpProtocolTest {
    @Test fun requestUsesJsonRpcEnvelope() {
        val request = JSONObject(McpJsonRpcCodec.request("7", "tools/list"))
        assertEquals("2.0", request.getString("jsonrpc"))
        assertEquals("7", request.getString("id"))
        assertEquals("tools/list", request.getString("method"))
        assertNotNull(request.getJSONObject("params"))
    }

    @Test fun notificationOmitsId() {
        val notification = JSONObject(McpJsonRpcCodec.notification("notifications/initialized"))
        assertFalse(notification.has("id"))
    }

    @Test fun responseParsesProtocolErrors() {
        val response = McpJsonRpcCodec.parseResponse(
            JSONObject().put("jsonrpc", "2.0").put("id", 2)
                .put("error", JSONObject().put("code", -32601).put("message", "Unknown method"))
                .toString(),
        )
        assertEquals("2", response.id)
        assertEquals(-32601, response.error?.code)
        assertEquals(null, response.result)
    }

    @Test fun toolDiscoveryDeduplicatesNames() {
        val tools = JSONArray()
            .put(JSONObject().put("name", "search").put("inputSchema", JSONObject().put("type", "object")))
            .put(JSONObject().put("name", "search"))
            .put(JSONObject().put("name", "write").put("description", "Writes a file"))
        val parsed = McpJsonRpcCodec.parseTools(JSONObject().put("tools", tools))
        assertEquals(listOf("search", "write"), parsed.map { it.name })
        assertEquals("object", parsed.last().inputSchema.getString("type"))
    }

    @Test fun remoteConnectionsRejectInsecureNonLoopbackEndpoints() {
        assertThrows(IllegalArgumentException::class.java) {
            McpConnectionProfile("render", "Render", McpTransportKind.STREAMABLE_HTTP, endpoint = "http://example.com/mcp")
        }
        val loopback = McpConnectionProfile("local", "Local", McpTransportKind.STREAMABLE_HTTP, endpoint = "http://127.0.0.1:3000/mcp")
        assertTrue(loopback.enabled)
    }
}
