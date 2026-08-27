package com.iqge;

import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.view.MotionEvent;
import android.view.View;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

/** Displays only the collapsed IQ Code floating ball. */
public final class FloatingOverlayService extends Service {
    private WindowManager windowManager;
    private TextView ball;
    private WindowManager.LayoutParams ballParams;
    private float downX, downY;
    private int startX, startY;
    private boolean dragged;
    private boolean positionUpdatePosted;
    private final Runnable applyBallPosition = () -> {
        positionUpdatePosted = false;
        if (ball != null && ballParams != null) {
            try { windowManager.updateViewLayout(ball, ballParams); } catch (Exception ignored) { }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        if (!Settings.canDrawOverlays(this)) {
            stopSelf();
            return;
        }
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        showBall();
        return START_NOT_STICKY;
    }

    private void showBall() {
        removeBall();
        ball = new TextView(this);
        ball.setText("IQ");
        ball.setTextColor(Color.WHITE);
        ball.setTextSize(12);
        ball.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        ball.setGravity(Gravity.CENTER);
        ball.setPadding(0,0,0,1);
        GradientDrawable background=new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[]{Color.argb(178,118,104,238),Color.argb(158,48,137,207)});
        background.setShape(GradientDrawable.OVAL);
        background.setStroke(dp(1),Color.argb(145,235,240,255));
        ball.setBackground(background);
        ball.setElevation(10f);
        ball.setContentDescription("展开 IQ Code 悬浮工作区；拖动可移动");
        ball.setOnTouchListener((v,event)->handleBallTouch(event));
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            dp(58), dp(58),
            Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        android.content.SharedPreferences prefs=getSharedPreferences("iqge_overlay",MODE_PRIVATE);
        params.alpha=.88f;
        params.x=prefs.getInt("x",getResources().getDisplayMetrics().widthPixels-dp(76));
        params.y=prefs.getInt("y",getResources().getDisplayMetrics().heightPixels/2-dp(29));
        ballParams=params;
        windowManager.addView(ball, params);
    }

    private boolean handleBallTouch(MotionEvent event){
        if(ballParams==null)return false;
        switch(event.getActionMasked()){
            case MotionEvent.ACTION_DOWN:
                downX=event.getRawX();downY=event.getRawY();startX=ballParams.x;startY=ballParams.y;dragged=false;return true;
            case MotionEvent.ACTION_MOVE:
                int x=startX+(int)(event.getRawX()-downX),y=startY+(int)(event.getRawY()-downY);
                if(Math.abs(x-startX)>dp(5)||Math.abs(y-startY)>dp(5))dragged=true;
                if(dragged){
                    ballParams.x=Math.max(0,Math.min(getResources().getDisplayMetrics().widthPixels-dp(58),x));
                    ballParams.y=Math.max(0,Math.min(getResources().getDisplayMetrics().heightPixels-dp(58),y));
                    if (!positionUpdatePosted && ball != null) {
                        positionUpdatePosted = true;
                        ball.postOnAnimation(applyBallPosition);
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (ball != null) ball.removeCallbacks(applyBallPosition);
                positionUpdatePosted = false;
                if (dragged) applyBallPosition.run();
                if(!dragged && event.getActionMasked() == MotionEvent.ACTION_UP){if(MainActivity.reopenWorkspaceOverlay())stopSelf();else startActivity(new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP));}
                else if (dragged) getSharedPreferences("iqge_overlay",MODE_PRIVATE).edit().putInt("x",ballParams.x).putInt("y",ballParams.y).apply();
                return true;
            default:return true;
        }
    }

    private int dp(int value){return (int)(value*getResources().getDisplayMetrics().density+.5f);}

    private void removeBall() {
        if (ball != null) {
            ball.removeCallbacks(applyBallPosition);
            positionUpdatePosted = false;
            if (windowManager != null) {
                try { windowManager.removeView(ball); } catch (Exception ignored) { }
            }
        }
        ball = null;
        ballParams = null;
    }

    @Override public void onDestroy() {
        removeBall();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
