package com.jarves.mh.mcp

import org.json.JSONArray
import org.json.JSONObject

enum class McpTransportKind { STDIO, STREAMABLE_HTTP }
enum class McpApprovalMode { ALWAYS_ALLOW, ASK_BEFORE_WRITE, ALWAYS_ASK }

data class McpConnectionProfile(
    val id: String,
    val name: String,
    val transport: McpTransportKind,
    val endpoint: String? = null,
    val command: List<String> = emptyList(),
    val approvalMode: McpApprovalMode = McpApprovalMode.ASK_BEFORE_WRITE,
    val enabled: Boolean = true,
) {
    init {
        require(id.isNotBlank()) { "MCP connection id is required" }
        require(name.isNotBlank()) { "MCP connection name is required" }
        when (transport) {
            McpTransportKind.STDIO -> require(command.isNotEmpty() && command.none(String::isBlank)) {
                "A stdio MCP connection requires a command"
            }
            McpTransportKind.STREAMABLE_HTTP -> require(
                endpoint?.startsWith("https://") == true || endpoint?.startsWith("http://127.0.0.1") == true,
            ) { "A remote MCP endpoint must use HTTPS; loopback HTTP is allowed" }
        }
    }
}

data class McpToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: JSONObject,
)

data class McpProtocolError(val code: Int, val message: String, val data: Any? = null)
data class McpJsonRpcResponse(val id: String?, val result: JSONObject?, val error: McpProtocolError?)

object McpJsonRpcCodec {
    fun request(id: String, method: String, params: JSONObject = JSONObject()): String {
        require(id.isNotBlank()) { "JSON-RPC id is required" }
        require(method.isNotBlank()) { "JSON-RPC method is required" }
        return JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", method)
            .put("params", params)
            .toString()
    }

    fun notification(method: String, params: JSONObject = JSONObject()): String {
        require(method.isNotBlank()) { "JSON-RPC method is required" }
        return JSONObject()
            .put("jsonrpc", "2.0")
            .put("method", method)
            .put("params", params)
            .toString()
    }

    fun parseResponse(payload: String): McpJsonRpcResponse {
        val root = JSONObject(payload)
        require(root.optString("jsonrpc") == "2.0") { "Unsupported JSON-RPC version" }
        val result = root.optJSONObject("result")
        val errorObject = root.optJSONObject("error")
        require((result == null) xor (errorObject == null)) { "A JSON-RPC response must contain result or error" }
        val error = errorObject?.let {
            McpProtocolError(
                code = it.getInt("code"),
                message = it.getString("message"),
                data = it.opt("data").takeUnless { value -> value == JSONObject.NULL },
            )
        }
        return McpJsonRpcResponse(
            id = root.opt("id").takeUnless { it == null || it == JSONObject.NULL }?.toString(),
            result = result,
            error = error,
        )
    }

    fun parseTools(result: JSONObject): List<McpToolDefinition> {
        val tools = result.optJSONArray("tools") ?: JSONArray()
        return (0 until tools.length()).map { index ->
            val item = tools.getJSONObject(index)
            val name = item.getString("name").trim()
            require(name.isNotBlank()) { "MCP tool name is required" }
            McpToolDefinition(
                name = name,
                description = item.optString("description"),
                inputSchema = item.optJSONObject("inputSchema") ?: JSONObject().put("type", "object"),
            )
        }.distinctBy(McpToolDefinition::name)
    }
}
