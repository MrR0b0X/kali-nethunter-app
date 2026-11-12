package com.offsec.nethunter.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import com.offsec.nethunter.AppNavHomeActivity;
import com.offsec.nethunter.Executor.ChrootManagerExecutor;
import com.offsec.nethunter.R;
import com.offsec.nethunter.WifiSessionActivity;
import com.offsec.nethunter.wifi.WifiSessionEngine;
import com.offsec.nethunter.wifi.WifiSessionRequest;
import com.offsec.nethunter.wifi.WifiSessionState;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class WifiSessionService extends Service {
    private static final String STOP = "com.offsec.nethunter.wifi.STOP";
    private static final String CHANNEL = "wifi_sessions";
    private static final int NOTIFICATION = 4801;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService canceller = Executors.newSingleThreadExecutor();
    private volatile WifiSessionEngine engine;
    private volatile boolean stopRequested;
    private PowerManager.WakeLock wakeLock;
    private final Handler main = new Handler(Looper.getMainLooper());

    public static void start(Context context, WifiSessionRequest request) {
        if (ChrootManagerExecutor.isMutating()) throw new IllegalStateException(context.getString(R.string.wifi_chroot_busy));
        if (!WifiSessionState.begin(request)) throw new IllegalStateException(context.getString(R.string.wifi_busy));
        try {
            ContextCompat.startForegroundService(context, request.write(new Intent(context, WifiSessionService.class))
                    .putExtra("session_generation", WifiSessionState.current().generation));
        } catch (RuntimeException e) {
            WifiSessionState.finish(e.getMessage(), true);
            throw e;
        }
    }

    public static void stop(Context context) {
        stop(context, WifiSessionState.current().generation);
    }

    public static void stop(Context context, long generation) {
        if (WifiSessionState.requestStop(generation)) {
            context.startService(new Intent(context, WifiSessionService.class).setAction(STOP)
                    .putExtra("session_generation", generation));
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, getString(R.string.wifi_notification_channel), NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(startId); return START_NOT_STICKY; }
        if (STOP.equals(intent.getAction())) {
            WifiSessionState.Snapshot session = WifiSessionState.current();
            if (!session.active || !session.cancellable || session.generation != intent.getLongExtra("session_generation", -1)) {
                if (engine == null) stopSelf(startId);
                return START_NOT_STICKY;
            }
            stopRequested = true;
            WifiSessionState.requestStop();
            WifiSessionState.stage(getString(R.string.wifi_stage_stopping), WifiSessionState.current().elapsed);
            WifiSessionEngine active = engine;
            if (active != null) canceller.execute(active::cancel);
            else {
                WifiSessionState.finish(getString(R.string.wifi_session_stopped), false);
                stopSelf(startId);
            }
            return START_NOT_STICKY;
        }
        if (engine != null) return START_NOT_STICKY;
        WifiSessionState.Snapshot pending = WifiSessionState.current();
        if (!pending.active || pending.generation != intent.getLongExtra("session_generation", -1) || pending.stopRequested) {
            if (pending.stopRequested) WifiSessionState.finish(getString(R.string.wifi_session_stopped), false);
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        final WifiSessionRequest request;
        try {
            request = WifiSessionRequest.read(intent);
            ServiceCompat.startForeground(this, NOTIFICATION, notification(getString(R.string.wifi_stage_preparing)),
                    Build.VERSION.SDK_INT >= 29 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE : 0);
        } catch (RuntimeException e) {
            WifiSessionState.finish(e.getMessage(), true);
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        stopRequested = false;
        WifiSessionEngine task = new WifiSessionEngine(this, (message, seconds) -> {
            WifiSessionState.Snapshot previous = WifiSessionState.current();
            WifiSessionState.stage(message, seconds);
            if (!previous.status.equals(message) || previous.elapsed != seconds) {
                NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                manager.notify(NOTIFICATION, notification(message));
            }
        });
        engine = task;
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NetHunter:WifiSession");
        wakeLock.acquire(12 * 60 * 1000L);
        executor.execute(() -> {
            String result;
            boolean error = false;
            try {
                if (stopRequested || WifiSessionState.current().stopRequested) task.cancel();
                result = task.run(request);
            } catch (Exception e) {
                Log.e("WifiSessionService", "Wi-Fi session failed", e);
                error = true;
                result = e.getMessage() == null ? getString(R.string.wifi_session_failed) : e.getMessage();
                WifiSessionState.log(result);
            }
            final String finalResult = result;
            final boolean failed = error;
            main.post(() -> {
                if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
                engine = null;
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
                WifiSessionState.finish(finalResult, failed);
                stopSelf();
            });
        });
        return START_NOT_STICKY;
    }

    private Notification notification(String message) {
        WifiSessionRequest request = WifiSessionState.current().request;
        Intent open = request != null && request.hasSessionScreen()
                ? WifiSessionActivity.intent(this)
                : new Intent(this, AppNavHomeActivity.class).putExtra("open_wifi", true)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(this, NOTIFICATION, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, NOTIFICATION,
                new Intent(this, WifiSessionService.class).setAction(STOP)
                        .putExtra("session_generation", WifiSessionState.current().generation),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_action_network_wifi)
                .setContentTitle(getString(R.string.wifi_title)).setContentText(message).setContentIntent(content)
                .setOngoing(true).setOnlyAlertOnce(true).addAction(0, getString(R.string.wifi_stop), stop).build();
    }

    @Override public void onDestroy() {
        stopRequested = true;
        WifiSessionEngine active = engine;
        if (active != null) canceller.execute(active::cancel);
        executor.shutdown();
        canceller.shutdown();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
