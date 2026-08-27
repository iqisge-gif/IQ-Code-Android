package com.termux.app.iqcode.storage;

import com.termux.shared.termux.TermuxConstants;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Persistent MCP server configuration for IQ Code Android. */
public final class McpConfigStore {
    public static final class Server {
        public String name = "";
        public String type = "stdio"; // stdio, http, sse
        public String command = "";
        public String url = "";
        public final List<String> args = new ArrayList<>();
        public JSONObject env = new JSONObject();
        public JSONObject headers = new JSONObject();
        public String scope = "user"; // user or project
        public boolean enabled = true;

        public Server copy() {
            Server s = new Server();
            s.name = name; s.type = type; s.command = command; s.url = url;
            s.args.addAll(args); s.env = cloneObject(env); s.headers = cloneObject(headers);
            s.scope = scope; s.enabled = enabled; return s;
        }

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("name", name); o.put("type", type); o.put("command", command); o.put("url", url);
            JSONArray a = new JSONArray(); for (String x : args) a.put(x); o.put("args", a);
            o.put("env", env == null ? new JSONObject() : env);
            o.put("headers", headers == null ? new JSONObject() : headers);
            o.put("scope", scope); o.put("enabled", enabled); return o;
        }

        static Server fromJson(JSONObject o) {
            Server s = new Server();
            s.name = o.optString("name", ""); s.type = o.optString("type", "stdio");
            s.command = o.optString("command", ""); s.url = o.optString("url", "");
            JSONArray a = o.optJSONArray("args"); if (a != null) for (int i=0;i<a.length();i++) s.args.add(a.optString(i, ""));
            JSONObject e = o.optJSONObject("env"); if (e != null) s.env = cloneObject(e);
            JSONObject h = o.optJSONObject("headers"); if (h != null) s.headers = cloneObject(h);
            s.scope = o.optString("scope", "user"); s.enabled = o.optBoolean("enabled", true); return s;
        }
    }

    private final File file;
    public McpConfigStore() {
        File dir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".iq");
        dir.mkdirs(); file = new File(dir, "mcp.json");
    }
    public File getFile() { return file; }

    public synchronized List<Server> load() {
        List<Server> out = new ArrayList<>();
        if (!file.isFile()) return out;
        try {
            byte[] data = new byte[(int)file.length()];
            FileInputStream in = new FileInputStream(file); int off=0,n; while(off<data.length && (n=in.read(data,off,data.length-off))>0) off+=n; in.close();
            JSONObject root = new JSONObject(new String(data,0,off, StandardCharsets.UTF_8));
            JSONArray servers = root.optJSONArray("servers");
            if (servers != null) for(int i=0;i<servers.length();i++){ JSONObject o=servers.optJSONObject(i); if(o!=null) out.add(Server.fromJson(o)); }
        } catch (Throwable ignored) {}
        return out;
    }

    public synchronized void save(List<Server> servers) throws Exception {
        JSONObject root = new JSONObject(); root.put("version",1);
        JSONArray a = new JSONArray(); for(Server s:servers) a.put(s.toJson()); root.put("servers",a);
        File tmp = new File(file.getParentFile(), file.getName()+".tmp");
        FileOutputStream out = new FileOutputStream(tmp); out.write(root.toString(2).getBytes(StandardCharsets.UTF_8)); out.flush(); out.close();
        if (file.exists() && !file.delete()) throw new Exception("无法替换旧 MCP 配置文件");
        if (!tmp.renameTo(file)) throw new Exception("无法保存 MCP 配置文件");
    }

    public synchronized Server find(String name) {
        for(Server s:load()) if(s.name.equalsIgnoreCase(name)) return s; return null;
    }

    private static JSONObject cloneObject(JSONObject in) {
        try { return in == null ? new JSONObject() : new JSONObject(in.toString()); }
        catch (Throwable e) { return new JSONObject(); }
    }
}
