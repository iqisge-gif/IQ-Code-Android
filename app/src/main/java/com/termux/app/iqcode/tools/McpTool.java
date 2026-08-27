package com.termux.app.iqcode.tools;

import com.termux.app.iqcode.mcp.McpRuntime;
import com.termux.app.iqcode.model.SessionConfig;
import com.termux.app.iqcode.model.ToolExecutionResult;

import org.json.JSONObject;

public final class McpTool implements IQTool {
    private final McpRuntime runtime;
    private final boolean list;
    public McpTool(McpRuntime runtime,boolean list){this.runtime=runtime;this.list=list;}
    @Override public String name(){return list?"mcp_list":"mcp_call";}
    @Override public String description(){return list?"List enabled MCP servers and discover their available tools before calling them.":"Call a tool exposed by a configured MCP server. First use mcp_list to get the exact server and tool name.";}
    @Override public JSONObject inputSchema(){try{if(list)return new JSONObject().put("type","object").put("properties",new JSONObject());return new JSONObject().put("type","object").put("properties",new JSONObject().put("server",new JSONObject().put("type","string")).put("tool",new JSONObject().put("type","string")).put("arguments",new JSONObject().put("type","object"))).put("required",new org.json.JSONArray().put("server").put("tool"));}catch(Exception e){throw new IllegalStateException(e);}}
    @Override public PermissionKind permissionKind(){return PermissionKind.NETWORK;}
    @Override public ToolExecutionResult execute(SessionConfig config,JSONObject input){if(list)return ToolExecutionResult.ok(runtime.listServers().toString());return runtime.call(input.optString("server",""),input.optString("tool",""),input.optJSONObject("arguments"));}
}
