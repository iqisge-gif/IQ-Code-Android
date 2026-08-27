package com.iqge.sandbox;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import com.termux.app.iqcode.termux.TermuxShellExecutor;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Human-facing sandbox manager intentionally hosted in IQ Code's stable main process.
 * BlackBox itself lives behind SandboxControlProvider in :iqsandbox. If the backend
 * process dies, this Activity stays alive and shows the startup stage instead of
 * disappearing with the backend.
 */
public final class SandboxDashboardActivity extends Activity {
    private static final int PICK_APK=4811;
    private static final String UI_PREFS="iqge_ui_preferences",UI_THEME_KEY="ui_theme";
    private int BG=0xFF090D18,CARD=0xFF131B2F,TEXT=0xFFEFF3FF,MUTED=0xFF99A6C4,ACCENT=0xFF6C8CFF,RED=0xFFFF8899,GREEN=0xFF73D69C;
    private boolean lightTheme,neonTheme;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private LinearLayout list; private TextView status; private Switch rootHideSwitch, floatingLogSwitch;
    private volatile int startupRetry; private volatile boolean destroyed; private boolean syncingRootSwitch,rootSettingInFlight,syncingFloatingLog,floatingLogInFlight;

    @Override protected void onCreate(Bundle state){super.onCreate(state);applyThemeFromPreferences();applySystemBars();setContentView(build());}
    @Override protected void onResume(){
        super.onResume();
        int oldBg=BG,oldCard=CARD,oldText=TEXT,oldMuted=MUTED,oldAccent=ACCENT,oldRed=RED,oldGreen=GREEN;
        applyThemeFromPreferences();applySystemBars();
        if(list==null||oldBg!=BG||oldCard!=CARD||oldText!=TEXT||oldMuted!=MUTED||oldAccent!=ACCENT||oldRed!=RED||oldGreen!=GREEN)setContentView(build());
        reload();
    }

    private void applyThemeFromPreferences(){
        String theme=getSharedPreferences(UI_PREFS,MODE_PRIVATE).getString(UI_THEME_KEY,"classic");
        lightTheme="day".equals(theme);neonTheme="neon-purple".equals(theme);
        if(lightTheme){BG=0xFFF6F8FC;CARD=0xFFFFFFFF;TEXT=0xFF1D2433;MUTED=0xFF5D687A;ACCENT=0xFF535BD6;RED=0xFFC2414B;GREEN=0xFF1C895B;}
        else if(neonTheme){BG=0xFF090D18;CARD=0xFF131B2F;TEXT=0xFFEFF3FF;MUTED=0xFF99A6C4;ACCENT=0xFF6C8CFF;RED=0xFFFF8899;GREEN=0xFF73D69C;}
        else if("custom".equals(theme)){
            android.content.SharedPreferences p=getSharedPreferences(UI_PREFS,MODE_PRIVATE);
            BG=p.getInt("palette_background",0xFF12110F);CARD=p.getInt("palette_surface",0xFF191816);TEXT=p.getInt("palette_text",0xFFF0ECE5);
            ACCENT=p.getInt("palette_accent",0xFFD97757);GREEN=p.getInt("palette_green",0xFF7EB288);RED=p.getInt("palette_red",0xFFD6786F);MUTED=mix(TEXT,BG,.42f);
        }else{BG=0xFF12110F;CARD=0xFF191816;TEXT=0xFFF0ECE5;MUTED=0xFFA49D93;ACCENT=0xFFD97757;RED=0xFFD6786F;GREEN=0xFF7EB288;}
    }

    private void applySystemBars(){
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        if(android.os.Build.VERSION.SDK_INT>=23){int flags=getWindow().getDecorView().getSystemUiVisibility()&~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;if(android.os.Build.VERSION.SDK_INT>=26)flags&=~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;if(lightTheme)flags|=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;if(lightTheme&&android.os.Build.VERSION.SDK_INT>=26)flags|=View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;getWindow().getDecorView().setSystemUiVisibility(flags);}
    }

    private int mix(int from,int to,float amount){return Color.rgb(Math.round(Color.red(from)+(Color.red(to)-Color.red(from))*amount),Math.round(Color.green(from)+(Color.green(to)-Color.green(from))*amount),Math.round(Color.blue(from)+(Color.blue(to)-Color.blue(from))*amount));}

    @Override protected void onDestroy(){destroyed=true;worker.shutdownNow();super.onDestroy();}

    private View build(){
        LinearLayout root=vbox();root.setPadding(dp(16),dp(14),dp(16),dp(14));root.setBackgroundColor(BG);
        LinearLayout top=hbox();top.setGravity(Gravity.CENTER_VERTICAL);
        TextView back=button("←",TEXT);back.setOnClickListener(v->finish());top.addView(back,lp(dp(42),dp(40)));
        TextView title=text("IQ 沙箱",22,TEXT);title.setTypeface(Typeface.DEFAULT_BOLD);top.addView(title,new LinearLayout.LayoutParams(0,dp(42),1));
        TextView debug=button("诊断",ACCENT);debug.setOnClickListener(v->showDebug());top.addView(debug,lp(dp(72),dp(40)));root.addView(top,lp(-1,dp(48)));
        status=text("正在连接沙箱后端…",11,GREEN);status.setPadding(0,dp(10),0,dp(10));root.addView(status,lp(-1,dp(54)));
        LinearLayout rootSetting=hbox();rootSetting.setGravity(Gravity.CENTER_VERTICAL);TextView rootLabel=text("隐藏 Root",13,TEXT);rootSetting.addView(rootLabel,new LinearLayout.LayoutParams(0,dp(44),1));rootHideSwitch=new Switch(this);rootHideSwitch.setChecked(true);rootHideSwitch.setEnabled(false);rootHideSwitch.setOnCheckedChangeListener((button,hidden)->{if(!syncingRootSwitch)confirmRootVisibilityChange(hidden);});rootSetting.addView(rootHideSwitch,lp(dp(64),dp(44)));root.addView(rootSetting,lp(-1,dp(48)));
        LinearLayout logSetting=hbox();logSetting.setGravity(Gravity.CENTER_VERTICAL);TextView logLabel=text("日志悬浮窗",13,TEXT);logSetting.addView(logLabel,new LinearLayout.LayoutParams(0,dp(44),1));floatingLogSwitch=new Switch(this);floatingLogSwitch.setChecked(true);floatingLogSwitch.setEnabled(false);floatingLogSwitch.setOnCheckedChangeListener((button,enabled)->{if(!syncingFloatingLog)applyFloatingLog(enabled);});logSetting.addView(floatingLogSwitch,lp(dp(64),dp(44)));root.addView(logSetting,lp(-1,dp(48)));
        LinearLayout actions=hbox();Button install=action("导入 APK");install.setOnClickListener(v->pick());actions.addView(install,new LinearLayout.LayoutParams(0,dp(44),1));Button refresh=action("刷新");refresh.setOnClickListener(v->reload());actions.addView(refresh,new LinearLayout.LayoutParams(0,dp(44),1));root.addView(actions,lp(-1,dp(48)));
        ScrollView scroll=new ScrollView(this);list=vbox();scroll.addView(list,new ScrollView.LayoutParams(-1,-2));root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));return root;
    }

    private void reload(){
        if(destroyed)return;
        worker.execute(()->{
            JSONObject st=SandboxHostClient.call(this,"status",new JSONObject());
            if(destroyed)return;
            if(!st.optBoolean("ok")) { retryOrShow(st.optString("error","沙箱后端不可用")); return; }
            JSONObject r=SandboxHostClient.call(this,"list",new JSONObject());
            if(destroyed)return;
            if(!r.optBoolean("ok")) { retryOrShow(r.optString("error","读取沙箱应用失败")); return; }
            startupRetry=0;
            List<String> apps=new ArrayList<>();JSONArray a=r.optJSONArray("apps");if(a!=null)for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o!=null){String p=o.optString("package","");if(!p.isEmpty())apps.add(p);}}
            apps.sort(String.CASE_INSENSITIVE_ORDER);
            String s=st.optString("status","沙箱后端在线");boolean hideRoot=st.optBoolean("hide_root",true);boolean showLog=st.optBoolean("show_floating_log",false);runOnUiThread(()->{if(destroyed)return;status.setText(s+" · "+apps.size()+" 个应用");setRootSwitch(hideRoot,!rootSettingInFlight);setFloatingLog(showLog,!floatingLogInFlight);render(apps);});
        });
    }


    private void retryOrShow(String error){
        if(destroyed)return;
        String e=error==null?"":error;
        if(startupRetry<2 && (e.contains("初始化")||e.contains("timeout")||e.contains("超时")||e.contains("Application.onCreate"))){
            startupRetry++;
            runOnUiThread(()->{if(!destroyed)status.setText("沙箱后端启动中… 自动重试 "+startupRetry+"/2");});
            try{Thread.sleep(450L*startupRetry);}catch(InterruptedException x){Thread.currentThread().interrupt();return;}
            reload();
            return;
        }
        showBackendError(e);
    }

    private void showBackendError(String error){if(destroyed)return;String stage=readStartupStage();runOnUiThread(()->{if(destroyed)return;status.setText("沙箱后端异常");if(rootHideSwitch!=null)rootHideSwitch.setEnabled(false);list.removeAllViews();TextView v=text("BlackBox 后端没有正常响应。\n\n"+error+"\n\n最后启动阶段：\n"+stage+"\n\n点右上角「诊断」可查看完整 IQSandbox 事件。",12,RED);v.setTextIsSelectable(true);v.setPadding(dp(8),dp(16),dp(8),dp(16));list.addView(v,lp(-1,-2));});}

    private void confirmRootVisibilityChange(boolean hidden){
        if(destroyed||rootSettingInFlight)return;
        boolean previous=!hidden;
        setRootSwitch(previous,true);
        String title=hidden?"开启 Root 隐藏？":"关闭 Root 隐藏？";
        String message=(hidden?"Guest 将隐藏常见 Root 路径和管理包。":"Guest 将可以发现并请求设备上的 su，授权仍由设备 Root 管理器决定。")+"\n\n所有正在运行的 Guest 将停止，重新启动后生效。";
        new AlertDialog.Builder(this).setTitle(title).setMessage(message).setNegativeButton("取消",null).setPositiveButton("确定",(d,w)->applyRootVisibility(hidden,previous)).show();
    }

    private void applyRootVisibility(boolean hidden,boolean previous){
        if(destroyed)return;
        rootSettingInFlight=true;
        setRootSwitch(previous,false);
        worker.execute(()->{
            try{
                JSONObject r=SandboxHostClient.call(this,"set_hide_root",new JSONObject().put("hide_root",hidden));
                if(!r.optBoolean("ok"))throw new IllegalStateException(r.optString("error","设置失败"));
                boolean effective=r.optBoolean("hide_root",hidden);
                runOnUiThread(()->{if(destroyed)return;rootSettingInFlight=false;setRootSwitch(effective,true);toast("Root 隐藏已"+(effective?"开启":"关闭"));reload();});
            }catch(Throwable e){
                runOnUiThread(()->{if(destroyed)return;rootSettingInFlight=false;setRootSwitch(previous,true);toast("Root 隐藏设置失败: "+e.getMessage());});
            }
        });
    }

    private void setRootSwitch(boolean hidden,boolean enabled){
        if(rootHideSwitch==null)return;
        syncingRootSwitch=true;
        try{rootHideSwitch.setChecked(hidden);rootHideSwitch.setEnabled(enabled);}finally{syncingRootSwitch=false;}
    }

    private void setFloatingLog(boolean enabled,boolean interactive){
        if(floatingLogSwitch==null)return;
        syncingFloatingLog=true;
        try{floatingLogSwitch.setChecked(enabled);floatingLogSwitch.setEnabled(interactive);}finally{syncingFloatingLog=false;}
    }

    private void applyFloatingLog(boolean enabled){
        if(destroyed||floatingLogInFlight)return;
        floatingLogInFlight=true;setFloatingLog(!enabled,false);
        worker.execute(()->{
            try{
                JSONObject r=SandboxHostClient.call(this,"set_show_floating_log",new JSONObject().put("show_floating_log",enabled));
                if(!r.optBoolean("ok"))throw new IllegalStateException(r.optString("error","设置失败"));
                boolean effective=r.optBoolean("show_floating_log",enabled);
                runOnUiThread(()->{if(destroyed)return;floatingLogInFlight=false;setFloatingLog(effective,true);toast("日志悬浮窗已"+(effective?"开启":"关闭"));reload();});
            }catch(Throwable e){runOnUiThread(()->{if(destroyed)return;floatingLogInFlight=false;setFloatingLog(!enabled,true);toast("日志悬浮窗设置失败: "+e.getMessage());});}
        });
    }

    private void render(List<String> apps){list.removeAllViews();if(apps.isEmpty()){TextView empty=text("还没有沙箱应用。\n导入一个 APK，或让 Agent 调用 Sandbox install。",12,MUTED);empty.setGravity(Gravity.CENTER);list.addView(empty,lp(-1,dp(120)));return;}for(String pkg:apps)list.addView(appRow(pkg),lp(-1,-2));}
    private View appRow(String pkg){
        LinearLayout card=vbox();card.setPadding(dp(12),dp(10),dp(12),dp(10));card.setBackground(round(CARD,16));
        TextView name=text(pkg,14,TEXT);name.setTypeface(Typeface.DEFAULT_BOLD);card.addView(name,lp(-1,dp(34)));
        LinearLayout row=hbox();Button run=action("运行");run.setOnClickListener(v->rpcAction("launch",pkg,"已启动"));row.addView(run,new LinearLayout.LayoutParams(0,dp(40),1));Button stop=action("停止");stop.setOnClickListener(v->rpcAction("stop",pkg,"已停止"));row.addView(stop,new LinearLayout.LayoutParams(0,dp(40),1));Button clear=action("清数据");clear.setOnClickListener(v->confirm("清除沙箱数据？",()->rpcAction("clear_data",pkg,"已清除")));row.addView(clear,new LinearLayout.LayoutParams(0,dp(40),1));Button del=action("卸载");del.setTextColor(RED);del.setOnClickListener(v->confirm("从 IQ 沙箱卸载？",()->rpcAction("uninstall",pkg,"已卸载")));row.addView(del,new LinearLayout.LayoutParams(0,dp(40),1));card.addView(row,lp(-1,dp(44)));
        LinearLayout dbg=hbox();Button proc=action("进程 / SO 基址");proc.setOnClickListener(v->showProcesses(pkg));dbg.addView(proc,new LinearLayout.LayoutParams(0,dp(40),1));Button frida=action("Frida 动态调试");frida.setOnClickListener(v->fridaFor(pkg));dbg.addView(frida,new LinearLayout.LayoutParams(0,dp(40),1));card.addView(dbg,lp(-1,dp(42)));
        LinearLayout.LayoutParams p=lp(-1,-2);p.setMargins(0,0,0,dp(10));card.setLayoutParams(p);return card;
    }

    private void rpcAction(String action,String pkg,String okText){worker.execute(()->{try{JSONObject p=new JSONObject().put("package",pkg);JSONObject r=SandboxHostClient.call(this,action,p);if(!r.optBoolean("ok"))throw new IllegalStateException(r.optString("error","操作失败"));if("launch".equals(action)&&!r.optBoolean("success",true))throw new IllegalStateException("没有可启动 Activity");runOnUiThread(()->{toast(okText);reload();});}catch(Throwable e){runOnUiThread(()->toast(action+": "+e.getMessage()));}});}

    private void pick(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/vnd.android.package-archive");startActivityForResult(i,PICK_APK);}
    @Override protected void onActivityResult(int req,int result,Intent data){super.onActivityResult(req,result,data);if(req!=PICK_APK||result!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();worker.execute(()->installUri(uri));}
    private void installUri(Uri uri){
        try{File dir=new File(getCacheDir(),"sandbox-import");dir.mkdirs();String name=fileName(uri);File target=new File(dir,System.currentTimeMillis()+"-"+name.replaceAll("[^A-Za-z0-9._-]","_"));try(InputStream in=getContentResolver().openInputStream(uri);FileOutputStream out=new FileOutputStream(target)){if(in==null)throw new IllegalArgumentException("无法读取 APK");byte[] buf=new byte[65536];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);}PackageInfo pi=getPackageManager().getPackageArchiveInfo(target.getAbsolutePath(),PackageManager.GET_ACTIVITIES);if(pi==null||pi.packageName==null)throw new IllegalArgumentException("不是有效普通 APK");if(getPackageName().equals(pi.packageName))throw new IllegalArgumentException("不能导入 IQ Code 自身");JSONObject r=SandboxHostClient.call(this,"install",new JSONObject().put("path",target.getAbsolutePath()));if(!r.optBoolean("ok")||!r.optBoolean("success"))throw new IllegalStateException(r.optString("error",r.optString("message","安装失败")));runOnUiThread(()->{toast("已安装到沙箱: "+r.optString("package",pi.packageName));reload();});}catch(Throwable e){runOnUiThread(()->toast("安装失败: "+e.getMessage()));}
    }

    private List<JSONObject> processList(String pkg)throws Exception{JSONObject r=SandboxHostClient.call(this,"process_list",new JSONObject().put("package",pkg));if(!r.optBoolean("ok"))throw new IllegalStateException(r.optString("error","读取进程失败"));List<JSONObject> out=new ArrayList<>();JSONArray a=r.optJSONArray("processes");if(a!=null)for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o!=null)out.add(o);}return out;}
    private void fridaFor(String pkg){worker.execute(()->{try{List<JSONObject> ps=processList(pkg);if(ps.isEmpty()){runOnUiThread(()->toast("先运行 Guest，再加载 Frida"));return;}JSONObject chosen=ps.get(0);for(JSONObject p:ps)if(pkg.equals(p.optString("process"))){chosen=p;break;}int pid=chosen.optInt("pid",-1);if(pid<=0)throw new IllegalStateException("无有效 PID");if(!FridaRuntimeManager.isInstalled(this)){final int fpid=pid;runOnUiThread(()->new AlertDialog.Builder(this).setTitle("安装 Frida Gadget "+FridaRuntimeManager.VERSION).setMessage("首次使用需要从 Frida 官方发布页下载 arm64 Gadget并校验固定 SHA-256。").setNegativeButton("取消",null).setPositiveButton("安装并加载",(d,w)->worker.execute(()->installAndLoadFrida(pkg,fpid))).show());return;}loadFrida(pkg,pid);}catch(Throwable e){runOnUiThread(()->toast("Frida: "+e.getMessage()));}});}
    private void installAndLoadFrida(String pkg,int pid){try{runOnUiThread(()->status.setText("正在安装 Frida Gadget "+FridaRuntimeManager.VERSION+"…"));FridaRuntimeManager.install(this,new TermuxShellExecutor(this),TermuxConstants.TERMUX_HOME_DIR_PATH);loadFrida(pkg,pid);}catch(Throwable e){runOnUiThread(()->toast("Frida 安装失败: "+e.getMessage()));}}
    private void loadFrida(String pkg,int pid){try{JSONObject r=SandboxAgentBridge.request(this,"proc_frida_load",pkg,pid,new JSONObject().put("pid",pid),15000);runOnUiThread(()->showMonoDialog(pkg+" · Frida",r.toString()));}catch(Throwable e){runOnUiThread(()->toast("Frida 加载失败: "+e.getMessage()));}}
    private void showProcesses(String pkg){worker.execute(()->{try{StringBuilder b=new StringBuilder();List<JSONObject> ps=processList(pkg);if(ps.isEmpty())b.append("当前没有运行进程。先启动这个 Guest。\n");for(JSONObject p:ps){int pid=p.optInt("pid",-1);b.append("PID ").append(pid).append(" · ").append(p.optString("process")).append("\n");JSONObject r=SandboxAgentBridge.request(this,"proc_modules",pkg,pid,new JSONObject().put("pid",pid).put("max_modules",80),4000);if(r.optBoolean("ok")){JSONArray mods=r.optJSONArray("modules");if(mods!=null){int shown=0;for(int i=0;i<mods.length()&&shown<24;i++){JSONObject m=mods.optJSONObject(i);if(m==null)continue;String path=m.optString("path","");if(!(path.endsWith(".so")||path.contains(".apk")))continue;b.append("  ").append(m.optString("base","?")).append("  ").append(new File(path).getName()).append("\n");shown++;}}}else b.append("  [调试桥] ").append(r.optString("error","未响应")).append("\n");b.append('\n');}String text=b.toString();runOnUiThread(()->showMonoDialog(pkg+" · 进程 / SO 基址",text));}catch(Throwable e){runOnUiThread(()->toast("读取进程失败: "+e.getMessage()));}});}

    private void showDebug(){
        if(status!=null)status.setText("正在读取诊断信息…");
        worker.execute(()->{
            String s=SandboxDebugLog.snapshot(this)+"\n===== STARTUP STAGE =====\n"+readStartupStage();
            runOnUiThread(()->{if(!destroyed)showMonoDialog("IQ 沙箱诊断",s);});
        });
    }
    private String readStartupStage(){File f=new File(getFilesDir(),"sandbox/startup-stage.txt");if(!f.isFile())return "(尚无后端启动记录)";try(FileInputStream in=new FileInputStream(f)){byte[] b=new byte[(int)Math.min(f.length(),8192)];int n=in.read(b);return n<=0?"(空)":new String(b,0,n,StandardCharsets.UTF_8).trim();}catch(Throwable e){return "读取失败: "+e;}}
    private void showMonoDialog(String title,String s){TextView body=text(s,10,TEXT);body.setTypeface(Typeface.MONOSPACE);body.setTextIsSelectable(true);ScrollView sc=new ScrollView(this);sc.setPadding(dp(12),dp(8),dp(12),dp(8));sc.addView(body);new AlertDialog.Builder(this).setTitle(title).setView(sc).setPositiveButton("关闭",null).show();}
    private void confirm(String title,Runnable yes){new AlertDialog.Builder(this).setTitle(title).setNegativeButton("取消",null).setPositiveButton("确定",(d,w)->yes.run()).show();}
    private String fileName(Uri uri){try(android.database.Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst()){String s=c.getString(0);if(s!=null&&!s.isEmpty())return s;}}catch(Throwable ignored){}return "imported.apk";}
    private void toast(String s){Toast.makeText(this,s==null?"":s,Toast.LENGTH_SHORT).show();}
    private LinearLayout vbox(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}private LinearLayout hbox(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);return l;}
    private TextView text(String s,float size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setGravity(Gravity.CENTER_VERTICAL);return t;}private TextView button(String s,int c){TextView t=text(s,11,c);t.setGravity(Gravity.CENTER);return t;}
    private Button action(String s){Button b=new Button(this);b.setText(s);b.setTextSize(10);b.setAllCaps(false);b.setTextColor(TEXT);b.setBackground(round(lightTheme?0xFFE7EBF3:neonTheme?0xFF1A2340:0xFF2A2723,10));return b;}private GradientDrawable round(int c,int r){GradientDrawable d=new GradientDrawable();d.setColor(c);d.setCornerRadius(dp(r));return d;}private LinearLayout.LayoutParams lp(int w,int h){return new LinearLayout.LayoutParams(w,h);}private int dp(float v){return (int)(v*getResources().getDisplayMetrics().density+0.5f);}
}
