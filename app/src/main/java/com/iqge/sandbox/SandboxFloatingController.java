package com.iqge.sandbox;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.content.Intent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.iqge.MainActivity;
import com.iqge.sandbox.SandboxDashboardActivity;

/** Tiny host-owned overlay injected into every resumed virtual Activity. No SYSTEM_ALERT_WINDOW is required. */
public final class SandboxFloatingController {
    public static final Integer OVERLAY_TAG=0x49515342; // "IQSB"
    private SandboxFloatingController(){}

    public static void attach(Activity activity,String pkg){
        if(activity==null||activity.isFinishing())return;
        SandboxDebugLog.event("准备添加日志悬浮窗: "+pkg+" / "+activity.getClass().getName());
        activity.runOnUiThread(()->{
            View decor=activity.getWindow().getDecorView(); if(!(decor instanceof ViewGroup))return;
            ViewGroup root=(ViewGroup)decor; if(root.findViewWithTag(OVERLAY_TAG)!=null)return;
            boolean day="day".equals(activity.getSharedPreferences("iqge_ui_preferences",0).getString("ui_theme","classic"));
            boolean neon="neon-purple".equals(activity.getSharedPreferences("iqge_ui_preferences",0).getString("ui_theme","classic"));
            int panelColor=day?0xF2FFFFFF:neon?0xE51A2237:0xF222201D;
            int buttonText=day?0xFF1D2433:Color.WHITE;
            LinearLayout panel=new LinearLayout(activity); panel.setTag(OVERLAY_TAG); panel.setOrientation(LinearLayout.HORIZONTAL); panel.setGravity(Gravity.CENTER_VERTICAL);
            panel.setPadding(dp(activity,6),dp(activity,4),dp(activity,6),dp(activity,4)); panel.setBackground(bg(panelColor,18)); panel.setElevation(dp(activity,14));
            TextView bubble=button(activity,"IQ",day?0xFF535BD6:neon?0xFF9CB4FF:0xFFD97757); panel.addView(bubble,new LinearLayout.LayoutParams(dp(activity,42),dp(activity,36)));
            TextView log=button(activity,"日志",buttonText); panel.addView(log,new LinearLayout.LayoutParams(dp(activity,48),dp(activity,36)));
            TextView back=button(activity,"返回",buttonText); panel.addView(back,new LinearLayout.LayoutParams(dp(activity,48),dp(activity,36)));
            TextView stop=button(activity,"停止",day?0xFFC2414B:0xFFFF9AA8); panel.addView(stop,new LinearLayout.LayoutParams(dp(activity,48),dp(activity,36)));
            log.setVisibility(View.GONE); back.setVisibility(View.GONE); stop.setVisibility(View.GONE);
            final float[] drag={0,0,0,0,0,0}; final Runnable[] frame={null};
            bubble.setOnTouchListener((v,e)->{switch(e.getActionMasked()){
                case MotionEvent.ACTION_DOWN:
                    drag[0]=e.getRawX();drag[1]=e.getRawY();drag[2]=panel.getTranslationX();drag[3]=panel.getTranslationY();drag[4]=drag[0];drag[5]=drag[1];
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    drag[4]=e.getRawX();drag[5]=e.getRawY();
                    if(frame[0]==null){frame[0]=()->{frame[0]=null;panel.setTranslationX(drag[2]+drag[4]-drag[0]);panel.setTranslationY(drag[3]+drag[5]-drag[1]);};panel.postOnAnimation(frame[0]);}
                    return true;
                case MotionEvent.ACTION_UP:
                    if(frame[0]!=null){panel.removeCallbacks(frame[0]);frame[0]=null;}
                    panel.setTranslationX(drag[2]+drag[4]-drag[0]);panel.setTranslationY(drag[3]+drag[5]-drag[1]);
                    panel.setLayerType(View.LAYER_TYPE_NONE,null);
                    if(Math.abs(drag[4]-drag[0])<10&&Math.abs(drag[5]-drag[1])<10){boolean expand=log.getVisibility()!=View.VISIBLE;log.setVisibility(expand?View.VISIBLE:View.GONE);back.setVisibility(expand?View.VISIBLE:View.GONE);stop.setVisibility(expand?View.VISIBLE:View.GONE);}
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    if(frame[0]!=null){panel.removeCallbacks(frame[0]);frame[0]=null;}
                    panel.setLayerType(View.LAYER_TYPE_NONE,null);
                    return true;
            }return false;});
            log.setOnClickListener(v->showLog(activity)); back.setOnClickListener(v->openIQ(activity,false));
            stop.setOnClickListener(v->{new Thread(()->{try{IQSandboxEngine.stop(pkg);SandboxGuardService.stop(activity);}catch(Throwable ignored){} activity.runOnUiThread(()->openIQ(activity,false));},"iq-sandbox-stop").start();});
            activity.addContentView(panel,new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT));
            panel.setX(dp(activity,10)); panel.setY(dp(activity,36));
        });
    }

    static void detach(Activity activity){
        if(activity==null)return;
        activity.runOnUiThread(()->{
            ViewGroup root=(ViewGroup)activity.getWindow().getDecorView();
            View panel=root.findViewWithTag(OVERLAY_TAG);
            if(panel!=null)root.removeView(panel);
        });
    }

    private static void showLog(Activity activity){
        TextView body=text(activity,"正在读取日志…");
        body.setTypeface(Typeface.MONOSPACE); body.setTextIsSelectable(true); body.setGravity(Gravity.TOP);
        ScrollView scroll=new ScrollView(activity); scroll.setPadding(dp(activity,8),dp(activity,4),dp(activity,8),dp(activity,4)); scroll.addView(body);
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("IQ 沙箱日志")
                .setView(scroll).setNegativeButton("关闭",null).setNeutralButton("复制全部",null)
                .setPositiveButton("刷新",null).create();
        dialog.setOnShowListener(v->{
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(x->{
                ClipboardManager cm=(ClipboardManager)activity.getSystemService(Activity.CLIPBOARD_SERVICE);
                if(cm!=null)cm.setPrimaryClip(ClipData.newPlainText("IQ Sandbox log",body.getText()));
            });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x->loadLog(activity,body));
        });
        dialog.show(); loadLog(activity,body);
    }

    private static void loadLog(Activity activity,TextView body){
        new Thread(()->{String value;try{value=SandboxDebugLog.snapshot(activity.getApplicationContext());}catch(Throwable e){value="读取日志失败: "+e;}String result=value;new Handler(Looper.getMainLooper()).post(()->{if(body.getWindowToken()!=null)body.setText(result);});},"iq-sandbox-log").start();
    }

    private static TextView text(Activity activity,String value){TextView t=new TextView(activity);t.setText(value);t.setTextColor(Color.WHITE);t.setTextSize(10);return t;}

    private static void openIQ(Activity a,boolean debug){
        Intent i=new Intent(a,debug?SandboxDashboardActivity.class:MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT|Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        i.putExtra("iq_sandbox_return",true); a.startActivity(i);
    }
    private static TextView button(Activity a,String s,int color){TextView t=new TextView(a);t.setText(s);t.setTextColor(color);t.setTextSize(11);t.setTypeface(Typeface.DEFAULT_BOLD);t.setGravity(Gravity.CENTER);return t;}
    private static GradientDrawable bg(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(radius);return d;}
    private static int dp(Activity a,float v){return (int)(v*a.getResources().getDisplayMetrics().density+0.5f);}
}
