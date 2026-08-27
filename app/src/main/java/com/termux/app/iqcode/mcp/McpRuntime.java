package com.termux.app.iqcode.mcp;

import com.termux.app.iqcode.model.ToolExecutionResult;
import com.termux.app.iqcode.storage.McpConfigStore;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** MCP stdio and Streamable HTTP runtime used by the Agent's generic MCP tools. */
public final class McpRuntime {
    private static final long TIMEOUT_MS=30_000L;
    private final McpConfigStore store=new McpConfigStore();
    private final AtomicLong ids=new AtomicLong(1L);
    public JSONArray listServers(){
        JSONArray out=new JSONArray();
        for(McpConfigStore.Server s:store.load())if(s.enabled){JSONObject row=new JSONObject();try{row.put("server",s.name).put("type",s.type).put("scope",s.scope);JSONObject result=call(s,"tools/list",new JSONObject());JSONArray tools=result==null?null:result.optJSONArray("tools");row.put("ok",true).put("tools",tools==null?new JSONArray():tools);}catch(Exception e){try{row.put("ok",false).put("error",message(e));}catch(Exception ignored){}}out.put(row);}
        return out;
    }
    public ToolExecutionResult call(String serverName,String toolName,JSONObject arguments){
        McpConfigStore.Server s=store.find(serverName);
        if(s==null||!s.enabled)return ToolExecutionResult.error("MCP server unavailable: "+serverName);
        if(toolName==null||toolName.trim().isEmpty())return ToolExecutionResult.error("MCP tool name is empty");
        try{JSONObject result=call(s,"tools/call",new JSONObject().put("name",toolName).put("arguments",arguments==null?new JSONObject():arguments));if(result!=null&&result.optBoolean("isError",false))return ToolExecutionResult.error(formatContent(result.optJSONArray("content")));return ToolExecutionResult.ok(formatContent(result==null?null:result.optJSONArray("content")));}catch(Exception e){return ToolExecutionResult.error("MCP call failed: "+message(e));}
    }
    private JSONObject call(McpConfigStore.Server s,String method,JSONObject params)throws Exception{
        if("stdio".equalsIgnoreCase(s.type))return callStdio(s,method,params);
        if("http".equalsIgnoreCase(s.type)||"sse".equalsIgnoreCase(s.type))return callHttp(s,method,params);
        throw new IOException("unsupported MCP transport: "+s.type);
    }
    private JSONObject callStdio(McpConfigStore.Server s,String method,JSONObject params)throws Exception{
        if(s.command==null||s.command.trim().isEmpty())throw new IOException("MCP stdio command is empty");
        ProcessBuilder builder=new ProcessBuilder(command(s));Map<String,String> env=builder.environment();if(s.env!=null){JSONArray keys=s.env.names();if(keys!=null)for(int i=0;i<keys.length();i++){String k=keys.optString(i,"");if(!k.isEmpty())env.put(k,s.env.optString(k,""));}}
        Process process=builder.redirectError(ProcessBuilder.Redirect.PIPE).start();try{BufferedInputStream in=new BufferedInputStream(process.getInputStream());BufferedOutputStream out=new BufferedOutputStream(process.getOutputStream());request(out,"initialize",initializeParams());response(in);request(out,"notifications/initialized",new JSONObject(),false);request(out,method,params);return response(in);}finally{process.destroy();if(process.isAlive())process.destroyForcibly();}
    }
    private JSONObject callHttp(McpConfigStore.Server s,String method,JSONObject params)throws Exception{
        if(s.url==null||s.url.trim().isEmpty())throw new IOException("MCP HTTP URL is empty");
        String session=null;JSONObject init=httpRequest(s,requestJson("initialize",initializeParams()),null);if(init!=null)session=init.optString("_session_id",null);httpRequest(s,requestJson("notifications/initialized",new JSONObject()),session);JSONObject result=httpRequest(s,requestJson(method,params),session);if(result!=null)result.remove("_session_id");return result;
    }
    private JSONObject httpRequest(McpConfigStore.Server s,JSONObject body,String session)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(s.url).openConnection();c.setConnectTimeout((int)TIMEOUT_MS);c.setReadTimeout((int)TIMEOUT_MS);c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("Accept","application/json, text/event-stream");if(session!=null&&!session.isEmpty())c.setRequestProperty("Mcp-Session-Id",session);if(s.headers!=null){JSONArray keys=s.headers.names();if(keys!=null)for(int i=0;i<keys.length();i++){String k=keys.optString(i,"");if(!k.isEmpty())c.setRequestProperty(k,s.headers.optString(k,""));}}
        byte[] data=body.toString().getBytes(StandardCharsets.UTF_8);try(BufferedOutputStream out=new BufferedOutputStream(c.getOutputStream())){out.write(data);out.flush();}int code=c.getResponseCode();if(code<200||code>=300)throw new IOException("MCP HTTP "+code+": "+readError(c));if("notifications/initialized".equals(body.optString("method")))return null;String response=readAll(c.getInputStream());JSONObject result=parseResponse(response);String returned=c.getHeaderField("Mcp-Session-Id");if(returned!=null&&result!=null)result.put("_session_id",returned);return result;
    }
    private JSONObject initializeParams()throws Exception{return new JSONObject().put("protocolVersion","2024-11-05").put("capabilities",new JSONObject()).put("clientInfo",new JSONObject().put("name","IQ Code").put("version","0.21.18"));}
    private JSONObject requestJson(String method,JSONObject params)throws Exception{return new JSONObject().put("jsonrpc","2.0").put("id",ids.getAndIncrement()).put("method",method).put("params",params);}
    private void request(BufferedOutputStream out,String method,JSONObject params)throws IOException{request(out,method,params,true);}
    private void request(BufferedOutputStream out,String method,JSONObject params,boolean withId)throws IOException{try{JSONObject m=new JSONObject().put("jsonrpc","2.0").put("method",method).put("params",params);if(withId)m.put("id",ids.getAndIncrement());byte[] b=m.toString().getBytes(StandardCharsets.UTF_8);out.write(("Content-Length: "+b.length+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(b);out.flush();}catch(Exception e){throw new IOException(e);}}
    private JSONObject response(BufferedInputStream in)throws Exception{long deadline=System.currentTimeMillis()+TIMEOUT_MS;int length=-1;String line;do{line=readLine(in,deadline);if(line==null)throw new IOException("MCP closed stdout");if(line.toLowerCase().startsWith("content-length:"))length=Integer.parseInt(line.substring(line.indexOf(':')+1).trim());}while(!line.isEmpty());if(length<0||length>16*1024*1024)throw new IOException("invalid MCP content length");JSONObject value=new JSONObject(new String(readFully(in,length,deadline),StandardCharsets.UTF_8));if(value.has("error"))throw new IOException(value.optJSONObject("error").optString("message","MCP error"));return value.optJSONObject("result")==null?value:value.optJSONObject("result");}
    private static JSONObject parseResponse(String raw)throws Exception{String text=raw==null?"":raw.trim();if(text.isEmpty())return null;if(text.startsWith("data:")){StringBuilder b=new StringBuilder();for(String line:text.split("\\r?\\n"))if(line.startsWith("data:")){String x=line.substring(5).trim();if(!"[DONE]".equals(x)){if(b.length()>0)b.append('\n');b.append(x);}}text=b.toString();}JSONObject value=new JSONObject(text);if(value.has("error"))throw new IOException(value.optJSONObject("error").optString("message","MCP error"));return value.optJSONObject("result")==null?value:value.optJSONObject("result");}
    private static String readAll(java.io.InputStream in)throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1)b.write(buf,0,n);return b.toString(StandardCharsets.UTF_8.name());}
    private static String readError(HttpURLConnection c){try{return readAll(c.getErrorStream());}catch(Exception e){return "";}}
    private static List<String> command(McpConfigStore.Server s){List<String> c=new ArrayList<>();c.add(s.command);c.addAll(s.args);return c;}
    private static String readLine(BufferedInputStream in,long deadline)throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();while(System.currentTimeMillis()<deadline){if(in.available()==0)try{Thread.sleep(5L);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("interrupted",e);}int x=in.read();if(x<0)return null;if(x=='\n')return b.toString(StandardCharsets.US_ASCII.name()).replace("\r","");b.write(x);}throw new IOException("MCP response timeout");}
    private static byte[] readFully(BufferedInputStream in,int length,long deadline)throws IOException{byte[] b=new byte[length];int off=0;while(off<length&&System.currentTimeMillis()<deadline){int n=in.read(b,off,length-off);if(n<0)break;off+=n;}if(off!=length)throw new IOException("MCP response timeout");return b;}
    private static String formatContent(JSONArray content){if(content==null)return "";StringBuilder out=new StringBuilder();for(int i=0;i<content.length();i++){JSONObject item=content.optJSONObject(i);if(item==null)continue;String text=item.optString("text","");if(!text.isEmpty()){if(out.length()>0)out.append('\n');out.append(text);}}return out.toString();}
    private static String message(Exception e){return e.getMessage()==null?e.toString():e.getMessage();}
}
