package com.local.runnerhelper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.Toast;

import java.nio.ByteBuffer;

public class CaptureService extends Service {
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";
    public static final String EXTRA_AUTO_MODE = "auto_mode";

    private static final String CHANNEL_ID = "runner_capture";
    private static final int NOTIFICATION_ID = 101;
    private static volatile boolean running = false;

    public static boolean isRunning() {
        return running;
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ObstacleDetector detector = new ObstacleDetector();

    private HandlerThread captureThread;
    private Handler captureHandler;
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private WindowManager windowManager;
    private Button bubble;
    private DiagnosticSession diagnostic;

    private boolean autoMode = false;
    private volatile boolean paused = false;
    private long lastFrameAt = 0L;
    private long lastActionAt = 0L;

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        createChannel();
        captureThread = new HandlerThread("runner-capture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(
                NOTIFICATION_ID,
                notification("启动中"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);

        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        autoMode = intent.getBooleanExtra(EXTRA_AUTO_MODE, false);
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData;
        if (Build.VERSION.SDK_INT >= 33) {
            resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
        } else {
            //noinspection deprecation
            resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        }

        if (resultCode == 0 || resultData == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            diagnostic = new DiagnosticSession(this, autoMode);
        } catch (Exception ignored) {
            diagnostic = null;
        }

        startProjection(resultCode, resultData);
        createBubble();
        getSystemService(NotificationManager.class).notify(
                NOTIFICATION_ID,
                notification(autoMode ? "自动" : "诊断"));
        return START_NOT_STICKY;
    }

    private void startProjection(int resultCode, Intent resultData) {
        WindowManager wm = getSystemService(WindowManager.class);
        android.graphics.Rect bounds = wm.getCurrentWindowMetrics().getBounds();
        int width = bounds.width();
        int height = bounds.height();
        int density = getResources().getDisplayMetrics().densityDpi;

        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);

        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        projection = manager.getMediaProjection(resultCode, resultData);
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                stopSelf();
            }
        }, mainHandler);

        display = projection.createVirtualDisplay(
                "RunnerCapture",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.getSurface(),
                null,
                captureHandler);

        reader.setOnImageAvailableListener(this::onFrame, captureHandler);
    }

    private void onFrame(ImageReader source) {
        Image image = source.acquireLatestImage();
        if (image == null) return;

        try {
            long now = android.os.SystemClock.uptimeMillis();
            if (now - lastFrameAt < 34L) return;
            lastFrameAt = now;

            Image.Plane[] planes = image.getPlanes();
            if (planes.length == 0) return;
            Image.Plane plane = planes[0];
            ByteBuffer buffer = plane.getBuffer();

            ObstacleDetector.Decision d = detector.analyze(
                    buffer,
                    image.getWidth(),
                    image.getHeight(),
                    plane.getPixelStride(),
                    plane.getRowStride());

            if (diagnostic != null) {
                diagnostic.recordDecision(d, now);
                diagnostic.maybeCapture(image, d, now);
            }

            updateBubble(d);

            if (paused || !autoMode || d.action == ObstacleDetector.Action.NONE) return;

            GameAccessibilityService gestures = GameAccessibilityService.getInstance();
            if (gestures == null) {
                setBubble("!");
                if (diagnostic != null) {
                    diagnostic.recordGesture("GESTURE_MISSING", d.action.name(), "accessibility-service-null");
                }
                return;
            }

            long cooldown;
            if (d.source == ObstacleDetector.Source.REWARD) {
                cooldown = 520L;
            } else if (d.action == ObstacleDetector.Action.JUMP) {
                cooldown = 650L;
            } else {
                cooldown = 500L;
            }
            if (now - lastActionAt < cooldown) return;

            GameAccessibilityService.GestureListener listener = new GameAccessibilityService.GestureListener() {
                @Override
                public void onCompleted(String action) {
                    if (diagnostic != null) {
                        diagnostic.recordGesture("GESTURE_OK", action, d.source.name());
                    }
                }

                @Override
                public void onCancelled(String action) {
                    if (diagnostic != null) {
                        diagnostic.recordGesture("GESTURE_CANCEL", action, d.source.name());
                    }
                }
            };

            boolean sent = d.action == ObstacleDetector.Action.JUMP
                    ? gestures.jump(listener)
                    : gestures.slideDown(listener);

            if (diagnostic != null) {
                diagnostic.recordGesture(
                        sent ? "GESTURE_SENT" : "GESTURE_REJECTED",
                        d.action.name(),
                        d.source.name() + ";x=" + d.xNorm);
            }
            if (sent) lastActionAt = now;
        } finally {
            image.close();
        }
    }

    private void createBubble() {
        if (!Settings.canDrawOverlays(this)) return;

        windowManager = getSystemService(WindowManager.class);
        bubble = new Button(this);
        bubble.setAllCaps(false);
        bubble.setTextSize(17);
        bubble.setTextColor(Color.WHITE);
        bubble.setPadding(0, 0, 0, 0);
        bubble.setMinWidth(0);
        bubble.setMinHeight(0);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(210, 17, 17, 17));
        bg.setCornerRadius(dp(22));
        bg.setStroke(dp(1), Color.argb(155, 85, 85, 85));
        bubble.setBackground(bg);
        bubble.setText(autoMode ? "自" : "诊");
        bubble.setOnClickListener(v -> {
            paused = !paused;
            bubble.setText(paused ? "Ⅱ" : (autoMode ? "自" : "诊"));
        });

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                dp(44),
                dp(44),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = dp(8);
        lp.y = dp(145);
        windowManager.addView(bubble, lp);
    }

    private void updateBubble(ObstacleDetector.Decision d) {
        if (bubble == null || paused) return;
        if (d.action == ObstacleDetector.Action.JUMP) {
            setBubble(d.source == ObstacleDetector.Source.REWARD ? "+" : "↑");
        } else if (d.action == ObstacleDetector.Action.SLIDE) {
            setBubble("↓");
        } else {
            setBubble(autoMode ? "自" : "诊");
        }
    }

    private void setBubble(String text) {
        if (bubble == null) return;
        mainHandler.post(() -> {
            if (bubble != null && !paused) bubble.setText(text);
        });
    }

    private Notification notification(String text) {
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Runner")
                .setContentText(text)
                .setOngoing(true)
                .build();
    }

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Runner",
                NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override
    public void onDestroy() {
        running = false;

        if (reader != null) reader.setOnImageAvailableListener(null, null);
        if (display != null) display.release();
        if (projection != null) {
            try {
                projection.stop();
            } catch (Exception ignored) {
            }
        }
        if (reader != null) reader.close();
        if (bubble != null && windowManager != null) {
            try {
                windowManager.removeView(bubble);
            } catch (Exception ignored) {
            }
        }
        if (captureThread != null) captureThread.quitSafely();

        if (diagnostic != null && !diagnostic.isFinished()) {
            diagnostic.finishAsync((uri, fileName) -> mainHandler.post(() -> {
                if (uri != null) {
                    Toast.makeText(
                            getApplicationContext(),
                            "诊断包已保存：Download/RunnerDiag",
                            Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(
                            getApplicationContext(),
                            "诊断包保存失败",
                            Toast.LENGTH_SHORT).show();
                }
            }));
        }

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
