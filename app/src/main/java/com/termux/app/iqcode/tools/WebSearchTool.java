package com.termux.app.iqcode.tools;

import android.text.Html;

import com.termux.app.iqcode.model.SessionConfig;
import com.termux.app.iqcode.model.ToolExecutionResult;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Native network web search for IQ Code. No external search API key is required. */
public final class WebSearchTool implements IQTool {
    private static final Pattern ANCHOR = Pattern.compile("<a\\b([^>]*)>(.*?)</a>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CLASS_ATTR = Pattern.compile("\\bclass\\s*=\\s*[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern HREF_ATTR = Pattern.compile("\\bhref\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern SNIPPET = Pattern.compile("<(?:a|div|span)[^>]*class=[\"'][^\"']*result__snippet[^\"']*[\"'][^>]*>(.*?)</(?:a|div|span)>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern RSS_ITEM = Pattern.compile("<item>(.*?)</item>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern RSS_TITLE = Pattern.compile("<title>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern RSS_LINK = Pattern.compile("<link>(.*?)</link>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern RSS_DESC = Pattern.compile("<description>(.*?)</description>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final class Result {
        final String title, url, snippet;
        Result(String title, String url, String snippet) { this.title=title; this.url=url; this.snippet=snippet; }
    }

    @Override public String name() { return "WebSearch"; }
    @Override public String description() {
        return "Search the web; returns titles, URLs, and snippets.";
    }
    @Override public JSONObject inputSchema() {
        try {
            JSONObject props = new JSONObject()
                .put("query", ToolSchemas.string("Search query"))
                .put("max_results", ToolSchemas.integer("Maximum number of results (1-10)", 1))
                .put("allowed_domains", ToolSchemas.stringArray("Optional domains to restrict results to, such as example.com"))
                .put("blocked_domains", ToolSchemas.stringArray("Optional domains to exclude from results"));
            return ToolSchemas.object(props, "query");
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    @Override public PermissionKind permissionKind() { return PermissionKind.NETWORK; }

    @Override public ToolExecutionResult execute(SessionConfig config, JSONObject input) throws Exception {
        if (!config.webSearchEnabled) return ToolExecutionResult.error("联网搜索已在设置中关闭。可输入 /web on 开启。 ");
        String query = input.optString("query", "").trim();
        if (query.isEmpty()) return ToolExecutionResult.error("query 不能为空");
        int max = input.optInt("max_results", config.webSearchMaxResults);
        max = Math.max(1, Math.min(10, max));
        JSONArray allow = input.optJSONArray("allowed_domains");
        JSONArray block = input.optJSONArray("blocked_domains");
        String provider = config.webSearchProvider == null ? "auto" : config.webSearchProvider.trim().toLowerCase(Locale.US);
        List<Result> results = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        if ("auto".equals(provider) || "duckduckgo".equals(provider)) {
            try { results = duckDuckGo(query, max, config.webTimeoutMs); }
            catch (Exception e) { errors.add("DuckDuckGo: " + safeMessage(e)); }
        }
        if (results.isEmpty() && ("auto".equals(provider) || "bing".equals(provider))) {
            try { results = bingRss(query, max, config.webTimeoutMs); }
            catch (Exception e) { errors.add("Bing: " + safeMessage(e)); }
        }

        List<Result> filtered = new ArrayList<>();
        for (Result r : results) {
            if (!allowed(r.url, allow) || blocked(r.url, block)) continue;
            filtered.add(r); if (filtered.size() >= max) break;
        }
        if (filtered.isEmpty()) {
            String suffix = errors.isEmpty() ? "没有搜索到结果。" : "搜索后端失败：" + join(errors, "；");
            return ToolExecutionResult.error(suffix);
        }

        StringBuilder out = new StringBuilder();
        out.append("联网搜索：").append(query).append('\n');
        out.append("结果数：").append(filtered.size()).append("\n\n");
        for (int i=0;i<filtered.size();i++) {
            Result r = filtered.get(i);
            out.append(i+1).append(". ").append(r.title).append('\n');
            out.append("   URL: ").append(r.url).append('\n');
            if (!r.snippet.isEmpty()) out.append("   摘要: ").append(r.snippet).append('\n');
            out.append('\n');
        }
        out.append("如需阅读网页正文，请对目标 URL 调用 WebFetch。");
        return ToolExecutionResult.ok(out.toString());
    }

    private static List<Result> duckDuckGo(String query, int max, int timeout) throws Exception {
        URL url = new URL("https://html.duckduckgo.com/html/");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(timeout); c.setReadTimeout(timeout);
        c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; IQCode) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36");
        c.setRequestProperty("Accept", "text/html,application/xhtml+xml");
        c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.7");
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        byte[] body = ("q=" + URLEncoder.encode(query, "UTF-8")).getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(body.length);
        try (OutputStream os = c.getOutputStream()) { os.write(body); }
        int code = c.getResponseCode();
        if (code < 200 || code >= 400) throw new Exception("HTTP " + code);
        String html = readLimited(c, 2_000_000);
        List<String> snippets = new ArrayList<>();
        Matcher sm = SNIPPET.matcher(html); while (sm.find()) snippets.add(cleanHtml(sm.group(1)));
        List<Result> out = new ArrayList<>();
        Matcher am = ANCHOR.matcher(html); int snippetIndex=0;
        while (am.find() && out.size()<Math.max(max*2, max)) {
            String attrs=am.group(1), inner=am.group(2);
            Matcher cm=CLASS_ATTR.matcher(attrs); if(!cm.find() || !cm.group(1).contains("result__a")) continue;
            Matcher hm=HREF_ATTR.matcher(attrs); if(!hm.find()) continue;
            String href=decodeDuckUrl(htmlEntity(hm.group(1)));
            String title=cleanHtml(inner);
            if(title.isEmpty()||href.isEmpty()||!isHttpUrl(href)) continue;
            String snippet=snippetIndex<snippets.size()?snippets.get(snippetIndex++):"";
            out.add(new Result(title,href,snippet));
        }
        return out;
    }

    private static List<Result> bingRss(String query, int max, int timeout) throws Exception {
        URL url = new URL("https://www.bing.com/search?format=rss&q=" + URLEncoder.encode(query, "UTF-8"));
        HttpURLConnection c=(HttpURLConnection)url.openConnection();
        c.setInstanceFollowRedirects(true); c.setConnectTimeout(timeout); c.setReadTimeout(timeout);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; IQCode) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36");
        c.setRequestProperty("Accept", "application/rss+xml,application/xml,text/xml,*/*");
        int code=c.getResponseCode(); if(code<200||code>=400)throw new Exception("HTTP "+code);
        String xml=readLimited(c,2_000_000);
        List<Result> out=new ArrayList<>(); Matcher im=RSS_ITEM.matcher(xml);
        while(im.find()&&out.size()<Math.max(max*2,max)){
            String item=im.group(1); String title=matchOne(RSS_TITLE,item); String link=matchOne(RSS_LINK,item); String desc=matchOne(RSS_DESC,item);
            title=cleanHtml(title); link=htmlEntity(link).trim(); desc=cleanHtml(desc);
            if(!title.isEmpty()&&isHttpUrl(link))out.add(new Result(title,link,desc));
        }
        return out;
    }

    private static String readLimited(HttpURLConnection c, int maxBytes) throws Exception {
        try (BufferedInputStream in = new BufferedInputStream(c.getInputStream()); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf=new byte[8192]; int n,total=0;
            while((n=in.read(buf))>0){int take=Math.min(n,maxBytes-total);if(take>0)out.write(buf,0,take);total+=take;if(total>=maxBytes)break;}
            return out.toString("UTF-8");
        }
    }

    private static String cleanHtml(String s) {
        if(s==null||s.isEmpty())return "";
        String t=Html.fromHtml(s,Html.FROM_HTML_MODE_LEGACY).toString();
        return t.replace('\u00a0',' ').replaceAll("\\s+"," ").trim();
    }
    private static String htmlEntity(String s){return s==null?"":s.replace("&amp;","&").replace("&quot;","\"").replace("&#39;","'");}
    private static String decodeDuckUrl(String href){
        try {
            if(href.startsWith("//"))href="https:"+href;
            URL u=new URL(href); if(u.getHost().contains("duckduckgo.com")&&u.getPath().startsWith("/l/")){
                String q=u.getQuery(); if(q!=null)for(String p:q.split("&")){int x=p.indexOf('=');if(x>0&&"uddg".equals(p.substring(0,x)))return URLDecoder.decode(p.substring(x+1),"UTF-8");}
            }
        }catch(Exception ignored){}
        return href;
    }
    private static String matchOne(Pattern p,String s){Matcher m=p.matcher(s);return m.find()?m.group(1):"";}
    private static boolean isHttpUrl(String s){String l=s.toLowerCase(Locale.US);return l.startsWith("https://")||l.startsWith("http://");}
    private static boolean allowed(String url,JSONArray domains){if(domains==null||domains.length()==0)return true;String host=host(url);for(int i=0;i<domains.length();i++){String d=domains.optString(i,"").trim().toLowerCase(Locale.US);if(!d.isEmpty()&&(host.equals(d)||host.endsWith("."+d)))return true;}return false;}
    private static boolean blocked(String url,JSONArray domains){if(domains==null)return false;String host=host(url);for(int i=0;i<domains.length();i++){String d=domains.optString(i,"").trim().toLowerCase(Locale.US);if(!d.isEmpty()&&(host.equals(d)||host.endsWith("."+d)))return true;}return false;}
    private static String host(String url){try{return new URL(url).getHost().toLowerCase(Locale.US);}catch(Exception e){return "";}}
    private static String join(List<String> xs,String sep){StringBuilder b=new StringBuilder();for(String x:xs){if(b.length()>0)b.append(sep);b.append(x);}return b.toString();}
    private static String safeMessage(Throwable e){return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
}
