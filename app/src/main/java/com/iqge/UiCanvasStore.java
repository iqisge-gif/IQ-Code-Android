package com.iqge;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.concurrent.CopyOnWriteArrayList;
import org.json.JSONArray;
import org.json.JSONObject;

/** Persistent, bounded runtime scene graph for presentation-only UI changes. */
public final class UiCanvasStore {
    private static final String PREFS="iqge_ui_canvas", DOCUMENT="document", UNDO="undo", REDO="redo";
    // VERSION=2 documents are migrated; v3 adds the full scene graph.
    private static final int VERSION=3, MAX_BYTES=64*1024, MAX_HISTORY=20, MAX_NODES=400;
    public interface ChangeListener { void onCanvasChanged(JSONObject document, boolean preview); }
    private static final CopyOnWriteArrayList<ChangeListener> LISTENERS=new CopyOnWriteArrayList<>();
    private UiCanvasStore(){}
    public static void addChangeListener(ChangeListener l){if(l!=null)LISTENERS.addIfAbsent(l);}
    public static void removeChangeListener(ChangeListener l){if(l!=null)LISTENERS.remove(l);}
    public static void publishPreview(JSONObject d){notifyChanged(validated(d),true);}
    private static void notifyChanged(JSONObject d,boolean preview){for(ChangeListener l:LISTENERS)try{l.onCanvasChanged(new JSONObject(d.toString()),preview);}catch(Exception ignored){}}
    public static JSONObject load(Context c){return read(c,DOCUMENT,defaults());}
    public static JSONObject defaults(){try{return new JSONObject().put("version",VERSION).put("root","workspace.root").put("palette",new JSONObject().put("background",0xff12110f).put("surface",0xff191816).put("text",0xfff0ece5).put("muted",0xffa49d93).put("accent",0xffd97757).put("green",0xff7eb288)).put("nodes",defaultNodes()).put("screens",new JSONObject().put("chat",new JSONObject().put("nodes",new JSONArray())));}catch(Exception e){throw new IllegalStateException(e);}}
    private static JSONArray defaultNodes(){try{return new JSONArray().put(new JSONObject().put("id","workspace.root").put("type","scene")).put(new JSONObject().put("id","workspace.global_bar").put("type","slot")).put(new JSONObject().put("id","workspace.sidebar").put("type","slot")).put(new JSONObject().put("id","workspace.primary").put("type","slot")).put(new JSONObject().put("id","workspace.secondary").put("type","slot")).put(new JSONObject().put("id","workspace.session_area").put("type","slot")).put(new JSONObject().put("id","agent_progress").put("type","slot")).put(new JSONObject().put("id","composer").put("type","slot"));}catch(Exception e){throw new IllegalStateException(e);}}
    public static synchronized void save(Context c,JSONObject d){JSONObject v=validated(d);SharedPreferences p=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);JSONArray u=readArray(p,UNDO);push(u,load(c));p.edit().putString(DOCUMENT,v.toString()).putString(UNDO,u.toString()).putString(REDO,"[]").commit();notifyChanged(v,false);}
    public static synchronized JSONObject undo(Context c){return history(c,UNDO,REDO);}
    public static synchronized JSONObject redo(Context c){return history(c,REDO,UNDO);}
    public static synchronized void reset(Context c){save(c,defaults());}
    public static String export(JSONObject d){return validated(d).toString();}
    public static int maxBytes(){return MAX_BYTES;}
    public static JSONObject migrate(JSONObject old){JSONObject d=defaults();if(old==null)return d;try{if(old.optJSONObject("palette")!=null)d.put("palette",new JSONObject(old.optJSONObject("palette").toString()));JSONArray legacy=old.optJSONObject("screens") == null?null:old.optJSONObject("screens").optJSONObject("chat")==null?null:old.optJSONObject("screens").optJSONObject("chat").optJSONArray("nodes");if(legacy!=null)d.put("nodes",new JSONArray(legacy.toString()));}catch(Exception ignored){}return d;}
    private static JSONObject history(Context c,String from,String to){SharedPreferences p=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);JSONArray a=readArray(p,from);if(a.length()==0)return load(c);JSONObject cur=load(c),next=a.optJSONObject(a.length()-1);a.remove(a.length()-1);if(next==null)return cur;JSONArray other=readArray(p,to);push(other,cur);p.edit().putString(DOCUMENT,next.toString()).putString(from,a.toString()).putString(to,other.toString()).commit();notifyChanged(next,false);return next;}
    private static JSONObject validated(JSONObject input){if(input==null)throw new IllegalArgumentException("document");try{JSONObject d=new JSONObject(input.toString());if(d.toString().getBytes("UTF-8").length>MAX_BYTES)throw new IllegalArgumentException("UI canvas exceeds 64 KiB");d.put("version",VERSION);validatePalette(d.optJSONObject("palette"));JSONArray nodes=d.optJSONArray("nodes");if(nodes!=null){if(nodes.length()>MAX_NODES)throw new IllegalArgumentException("too many canvas nodes");validateNodes(nodes);}return d;}catch(IllegalArgumentException e){throw e;}catch(Exception e){throw new IllegalArgumentException("invalid UI canvas",e);}}
    private static void validatePalette(JSONObject p){if(p==null)return;for(String k:new String[]{"background","surface","text","muted","accent","green"})if(p.has(k)&&p.optInt(k,Integer.MIN_VALUE)==Integer.MIN_VALUE)throw new IllegalArgumentException("invalid color: "+k);}
    private static void validateNodes(JSONArray a){for(int i=0;i<a.length();i++){JSONObject n=a.optJSONObject(i);if(n==null)throw new IllegalArgumentException("node must be object");String id=n.optString("id","");if(id.trim().length()==0||id.length()>64||id.indexOf('/')>=0||id.indexOf('\\')>=0||id.matches(".*[\\p{Cntrl}].*"))throw new IllegalArgumentException("invalid stable node id");String type=n.optString("type","slot");if(!type.matches("scene|container|stack|slot|text|action|spacer|overlay"))throw new IllegalArgumentException("unsupported node type");}}
    private static JSONObject read(Context c,String key,JSONObject fallback){try{String s=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(key,"");return s==null||s.isEmpty()?fallback:validated(migrate(new JSONObject(s)));}catch(Exception e){return fallback;}}
    private static JSONArray readArray(SharedPreferences p,String k){try{return new JSONArray(p.getString(k,"[]"));}catch(Exception e){return new JSONArray();}}
    private static void push(JSONArray a,JSONObject v){if(a.length()>=MAX_HISTORY)a.remove(0);a.put(v);}
}
