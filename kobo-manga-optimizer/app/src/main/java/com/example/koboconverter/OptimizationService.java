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
    public static final String EXTRA_WIDTH = "width";
    public static final String EXTRA_HEIGHT = "height";
    public static final String EXTRA_FORMAT = "format";
    public static final String EXTRA_QUALITY = "quality";
    public static final String EXTRA_ROTATION = "rotation";
    public static final String EXTRA_BORDER_COLOR = "border_color";
    public static final String EXTRA_COLOR_MODE = "color_mode";

    public interface ProgressListener {
        void onProgress(String message);
        void onSuccess(String message);
        void onError(String message);
    }

    public enum State { IDLE, RUNNING, SUCCESS, ERROR }

    private static volatile State state = State.IDLE;
    private static volatile String lastMessage = "No file selected";

    private static ProgressListener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static void setListener(ProgressListener l) {
        listener = l;
        if (l == null) return;

        switch (state) {
            case RUNNING:
                l.onProgress(lastMessage);
                break;
            case SUCCESS:
                l.onSuccess(lastMessage);
                break;
            case ERROR:
                l.onError(lastMessage);
                break;
            case IDLE:
            default:
                break;
        }
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
        int targetWidth = intent.getIntExtra(EXTRA_WIDTH, 1072);
        int targetHeight = intent.getIntExtra(EXTRA_HEIGHT, 1448);
        DeviceProfile.OutputFormat format = DeviceProfile.OutputFormat.valueOf(intent.getStringExtra(EXTRA_FORMAT));
        int quality = intent.getIntExtra(EXTRA_QUALITY, 85);
        CbzOptimizer.RotationMode rotationMode =
                CbzOptimizer.RotationMode.valueOf(intent.getStringExtra(EXTRA_ROTATION));
        CbzOptimizer.BorderColor borderColor =
                CbzOptimizer.BorderColor.valueOf(intent.getStringExtra(EXTRA_BORDER_COLOR));
        boolean colorMangaMode = intent.getBooleanExtra(EXTRA_COLOR_MODE, false);

        state = State.RUNNING;
        lastMessage = "Starting optimization...";

        Notification notification = buildNotification(lastMessage);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        CbzOptimizer.optimizeMultiple(this, uris, targetWidth, targetHeight, format, quality, rotationMode, borderColor,
                colorMangaMode, new CbzOptimizer.OptimizationCallback() {
                    @Override
                    public void onProgress(String message) {
                        state = State.RUNNING;
                        lastMessage = message;
                        updateNotification(message);
                        mainHandler.post(() -> {
                            if (listener != null) listener.onProgress(message);
                        });
                    }

                    @Override
                    public void onSuccess(String message) {
                        state = State.SUCCESS;
                        lastMessage = message;
                        updateNotification("Completed");
                        mainHandler.post(() -> {
                            if (listener != null) listener.onSuccess(message);
                        });
                        stopForeground(false);
                        stopSelf();
                    }

                    @Override
                    public void onError(Exception e) {
                        state = State.ERROR;
                        lastMessage = e.getMessage();
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
                    CHANNEL_ID, "Manga optimization", NotificationManager.IMPORTANCE_LOW);
            notificationManager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String content) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Kobo Manga Optimizer")
                .setContentText(content)
                .setSmallIcon(android.R.drawable.stat_sys_download)
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