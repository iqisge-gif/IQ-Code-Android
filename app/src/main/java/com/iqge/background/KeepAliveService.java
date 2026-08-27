package com.iqge.background;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import com.iqge.MainActivity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Explicit, user-controlled foreground keep-alive service. */
public final class KeepAliveService extends Service {
    public static final String ACTION_START = "com.iqge.action.KEEP_ALIVE_START";
    public static final String ACTION_STOP = "com.iqge.action.KEEP_ALIVE_STOP";
    private static final String CHANNEL = "iqcode_keep_alive";
    private static final int NOTIFICATION_ID = 19001;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private PowerManager.WakeLock wakeLock;

    public static void start(Context context, boolean rootEnabled) {
        Intent intent = new Intent(context, KeepAliveService.class).setAction(ACTION_START).putExtra("root", rootEnabled);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent); else context.startService(intent);
    }

    public static void stop(Context context) {
        context.startService(new Intent(context, KeepAliveService.class).setAction(ACTION_STOP));
    }

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        if (power != null) {
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IQCode:KeepAlive");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            getSharedPreferences("iq_code_android_settings",MODE_PRIVATE).edit().putBoolean("forced_keep_alive_enabled",false).apply();
            stopForeground(true);
            if(wakeLock!=null&&wakeLock.isHeld())wakeLock.release();
            worker.execute(()->{RootKeepAliveController.revoke(this);stopSelf();});
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, notification("强制后台保活已开启"));
        if (intent != null && intent.getBooleanExtra("root", false)) worker.execute(() -> {
            String status = RootKeepAliveController.apply(this);
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) manager.notify(NOTIFICATION_ID, notification(status));
        }); else worker.execute(() -> RootKeepAliveController.revoke(this));
        return START_STICKY;
    }

    @Override public void onTaskRemoved(Intent rootIntent) { }

    @Override public void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "IQ Code 后台保活", NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }
    }

    private Notification notification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, KeepAliveService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return builder.setSmallIcon(android.R.drawable.stat_sys_warning).setContentTitle("IQ Code 正在后台保活")
            .setContentText(text).setOngoing(true).setContentIntent(content)
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "停止保活", stopIntent).build()).build();
    }
}
