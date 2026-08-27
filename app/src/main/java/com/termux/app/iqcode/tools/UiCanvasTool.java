package com.termux.app.iqcode.tools;

import com.iqge.UiCanvasStore;
import com.iqge.UiCanvasController;
import com.termux.app.iqcode.model.SessionConfig;
import com.termux.app.iqcode.model.ToolExecutionResult;
import org.json.JSONArray;
import org.json.JSONObject;

/** Runtime-only, allow-listed UI customization; source editing remains the escape hatch. */
public final class UiCanvasTool implements IQTool {
    private final android.content.Context context;
    public UiCanvasTool(android.content.Context context){this.context=context.getApplicationContext();}
    @Override public String name(){return "ui_canvas";}
    @Override public String description(){return "Get, preview, patch, undo, redo, reset, or export bounded runtime UI canvas changes without rebuilding the APK.";}
    @Override public PermissionKind permissionKind(){return PermissionKind.WRITE;}
    @Override public JSONObject inputSchema(){try{return ToolSchemas.object(new JSONObject().put("operation",ToolSchemas.enumString("Canvas operation","get","set_palette","patch","preview","add","remove","move","reparent","set","undo","redo","reset","export")).put("palette",ToolSchemas.freeObject("Allow-listed ARGB colors")).put("operations",new JSONObject().put("type","array").put("maxItems",80).put("items",ToolSchemas.freeObject("Node patch: id plus visibility, padding, margin, or textSize"))),"operation");}catch(Exception e){throw new IllegalStateException(e);}}
    @Override public ToolExecutionResult execute(SessionConfig config,JSONObject input)throws Exception{
        String op=input.optString("operation","get");JSONObject current=UiCanvasStore.load(context), next=current;
        if("undo".equals(op))next=UiCanvasStore.undo(context); else if("redo".equals(op))next=UiCanvasStore.redo(context); else if("reset".equals(op)) {UiCanvasStore.reset(context);next=UiCanvasStore.load(context);} else if("set_palette".equals(op)||"patch".equals(op)||"add".equals(op)||"remove".equals(op)||"move".equals(op)||"reparent".equals(op)||"set".equals(op)||"preview".equals(op)){next=patched(current,input,op);if(!"preview".equals(op))UiCanvasStore.save(context,next);else UiCanvasStore.publishPreview(next);}
        if("export".equals(op))return result("UI 画布导出成功。",current.put("export",UiCanvasStore.export(current)));
        return result(("preview".equals(op)?"UI 画布预览已验证。":"UI 画布操作已完成。"),next);
    }
    private JSONObject patched(JSONObject current,JSONObject input,String op)throws Exception{JSONObject out=new JSONObject(current.toString());JSONObject palette=input.optJSONObject("palette");if(palette!=null){JSONObject p=out.optJSONObject("palette");if(p==null){p=new JSONObject();out.put("palette",p);}for(String k:new String[]{"background","surface","text","muted","accent","green"})if(palette.has(k))p.put(k,palette.getInt(k));}JSONArray ops=input.optJSONArray("operations");if(ops==null&&("add".equals(op)||"remove".equals(op)||"move".equals(op)||"reparent".equals(op)||"set".equals(op)))ops=new JSONArray().put(input);if(ops!=null)out=UiCanvasController.applyOperations(out,ops);return out;}
    private ToolExecutionResult result(String message,JSONObject document)throws Exception{return ToolExecutionResult.okWithAdditionalContent(message,new JSONArray().put(new JSONObject().put("type","ui_canvas").put("document",document)));}
}
