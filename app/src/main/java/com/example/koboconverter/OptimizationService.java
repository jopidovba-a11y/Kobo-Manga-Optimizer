package com.example.koboconverter;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.util.List;

public class OptimizationService extends Service {

    public static final String CHANNEL_ID = "optimization_channel";
    public static final int NOTIFICATION_ID = 1001;

    public static final String EXTRA_URIS = "uris";
    public static final String EXTRA_DEVICE = "device";
    public static final String EXTRA_FORMAT = "format";

    public interface ProgressListener {
        void onProgress(String message);
        void onSuccess(String message);
        void onError(String message);
    }

    private static ProgressListener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static void setListener(ProgressListener l) {
        listener = l;
    }

    private NotificationManager notificationManager;

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = getSystemService(NotificationManager.class);
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        List<Uri> uris = intent.getParcelableArrayListExtra(EXTRA_URIS);
        DeviceProfile profile = DeviceProfile.valueOf(intent.getStringExtra(EXTRA_DEVICE));
        DeviceProfile.OutputFormat format = DeviceProfile.OutputFormat.valueOf(intent.getStringExtra(EXTRA_FORMAT));

        Notification notification = buildNotification("Starting Optimization...");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        CbzOptimizer.optimizeMultiple(this, uris, profile, format, new CbzOptimizer.OptimizationCallback() {
            @Override
            public void onProgress(String message) {
                updateNotification(message);
                mainHandler.post(() -> {
                    if (listener != null) listener.onProgress(message);
                });
            }

            @Override
            public void onSuccess(String message) {
                updateNotification("Completed");
                mainHandler.post(() -> {
                    if (listener != null) listener.onSuccess(message);
                });
                stopForeground(false);
                stopSelf();
            }

            @Override
            public void onError(Exception e) {
                updateNotification("Error: " + e.getMessage());
                mainHandler.post(() -> {
                    if (listener != null) listener.onError(e.getMessage());
                });
                stopForeground(false);
                stopSelf();
            }
        });

        return START_NOT_STICKY;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Manga Optimization", NotificationManager.IMPORTANCE_LOW);
            notificationManager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String content) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Kobo Manga Optimizer")
                .setContentText(content)
                .setSmallIcon(android.R.drawable.stat_sys_download) // reemplaza por un ícono propio si quieres
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String content) {
        notificationManager.notify(NOTIFICATION_ID, buildNotification(content));
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}