package com.termux.app.iqcode.tools;

import android.text.Html;

import com.termux.app.iqcode.model.SessionConfig;
import com.termux.app.iqcode.model.ToolExecutionResult;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fetches readable text from a public HTTPS page for IQ Code's web research loop. */
public final class WebFetchTool implements IQTool {
    private static final Pattern TITLE=Pattern.compile("<title[^>]*>(.*?)</title>",Pattern.CASE_INSENSITIVE|Pattern.DOTALL);
    private static final Pattern SCRIPT_STYLE=Pattern.compile("<(script|style|noscript|svg)[^>]*>.*?</\\1>",Pattern.CASE_INSENSITIVE|Pattern.DOTALL);
    private static final Pattern BLOCK_BREAK=Pattern.compile("</?(?:p|div|section|article|main|header|footer|nav|li|ul|ol|h[1-6]|pre|blockquote|br|tr|table)[^>]*>",Pattern.CASE_INSENSITIVE);

    @Override public String name(){return "WebFetch";}
    @Override public String description(){return "Fetch readable text from a public HTTPS URL.";}
    @Override public JSONObject inputSchema(){
        try{return ToolSchemas.object(new JSONObject()
            .put("url",ToolSchemas.string("Public HTTPS URL to fetch"))
            .put("max_chars",ToolSchemas.integer("Maximum extracted characters (1000-100000)",1000)),"url");}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    @Override public PermissionKind permissionKind(){return PermissionKind.NETWORK;}

    @Override public ToolExecutionResult execute(SessionConfig config,JSONObject input)throws Exception{
        if(!config.webSearchEnabled)return ToolExecutionResult.error("联网功能已在设置中关闭。可输入 /web on 开启。");
        String raw=input.optString("url","").trim(); if(raw.isEmpty())return ToolExecutionResult.error("url 不能为空");
        int max=Math.max(1000,Math.min(100000,input.optInt("max_chars",config.webFetchMaxChars)));
        URL url=validate(raw); HttpURLConnection c=null;
        for(int redirects=0;redirects<6;redirects++){
            c=(HttpURLConnection)url.openConnection(); c.setInstanceFollowRedirects(false); c.setConnectTimeout(config.webTimeoutMs); c.setReadTimeout(config.webTimeoutMs);
            c.setRequestProperty("User-Agent","Mozilla/5.0 (Android; IQCode) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36");
            c.setRequestProperty("Accept","text/html,text/plain,application/json,application/xml,text/xml;q=0.9,*/*;q=0.5");
            c.setRequestProperty("Accept-Language","zh-CN,zh;q=0.9,en;q=0.7");
            int code=c.getResponseCode();
            if(code>=300&&code<400){String loc=c.getHeaderField("Location");if(loc==null||loc.trim().isEmpty())return ToolExecutionResult.error("网页重定向缺少 Location");url=validate(new URL(url,loc).toString());c.disconnect();continue;}
            if(code<200||code>=400)return ToolExecutionResult.error("网页请求失败：HTTP "+code+" · "+url);
            String ct=c.getContentType()==null?"":c.getContentType().toLowerCase(Locale.US);
            if(!(ct.contains("text/")||ct.contains("json")||ct.contains("xml")||ct.isEmpty()))return ToolExecutionResult.error("不支持的网页内容类型："+ct);
            byte[] bytes=readLimited(c,Math.min(2_000_000,Math.max(200_000,max*6))); String body=new String(bytes,charset(ct));
            String title="";Matcher tm=TITLE.matcher(body);if(tm.find())title=clean(tm.group(1));
            String text;
            if(ct.contains("html")||body.toLowerCase(Locale.US).contains("<html")){
                String x=SCRIPT_STYLE.matcher(body).replaceAll(" ");x=BLOCK_BREAK.matcher(x).replaceAll("\n");text=Html.fromHtml(x,Html.FROM_HTML_MODE_LEGACY).toString();
            }else text=body;
            text=normalize(text); boolean truncated=text.length()>max;if(truncated)text=text.substring(0,max)+"\n…[内容已截断]";
            StringBuilder out=new StringBuilder();out.append("URL: ").append(url).append('\n');if(!title.isEmpty())out.append("标题: ").append(title).append('\n');out.append("\n").append(text);
            return ToolExecutionResult.ok(out.toString());
        }
        return ToolExecutionResult.error("网页重定向次数过多");
    }

    private static URL validate(String raw)throws Exception{
        URI uri=new URI(raw);String scheme=uri.getScheme();if(scheme==null||!"https".equalsIgnoreCase(scheme))throw new IllegalArgumentException("WebFetch 仅允许 HTTPS URL");
        String host=uri.getHost();if(host==null||host.trim().isEmpty())throw new IllegalArgumentException("URL 缺少有效主机名");
        String h=host.toLowerCase(Locale.US);if("localhost".equals(h)||h.endsWith(".localhost"))throw new IllegalArgumentException("不允许访问 localhost");
        InetAddress[] addrs=InetAddress.getAllByName(host);for(InetAddress a:addrs)if(isPrivate(a))throw new IllegalArgumentException("不允许访问私有/本地网络地址");
        return uri.toURL();
    }
    private static boolean isPrivate(InetAddress a){
        if(a.isAnyLocalAddress()||a.isLoopbackAddress()||a.isLinkLocalAddress()||a.isSiteLocalAddress()||a.isMulticastAddress())return true;
        byte[] b=a.getAddress();
        if(a instanceof Inet4Address&&b.length==4){int x=b[0]&255,y=b[1]&255;if(x==100&&y>=64&&y<=127)return true;if(x==169&&y==254)return true;if(x==0||x>=224)return true;}
        if(a instanceof Inet6Address&&b.length==16){int x=b[0]&255;if((x&0xfe)==0xfc)return true;if(x==0xfe&&((b[1]&0xc0)==0x80))return true;}
        return false;
    }
    private static byte[] readLimited(HttpURLConnection c,int max)throws Exception{try(BufferedInputStream in=new BufferedInputStream(c.getInputStream());ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[]buf=new byte[8192];int n,total=0;while((n=in.read(buf))>0){int take=Math.min(n,max-total);if(take>0)out.write(buf,0,take);total+=take;if(total>=max)break;}return out.toByteArray();}}
    private static Charset charset(String ct){try{Matcher m=Pattern.compile("charset=([^; ]+)",Pattern.CASE_INSENSITIVE).matcher(ct);if(m.find())return Charset.forName(m.group(1).replace("\"",""));}catch(Exception ignored){}return StandardCharsets.UTF_8;}
    private static String clean(String s){return Html.fromHtml(s,Html.FROM_HTML_MODE_LEGACY).toString().replaceAll("\\s+"," ").trim();}
    private static String normalize(String s){String x=s.replace('\u00a0',' ').replace("\r","");x=x.replaceAll("[ \\t]+"," ");x=x.replaceAll("\\n[ \\t]+","\\n");x=x.replaceAll("\\n{3,}","\\n\\n");return x.trim();}
}
