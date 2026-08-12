package com.local.runnerhelper;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.Image;
import android.net.Uri;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * V0.2 “诊断之手”。
 * 不占用第二个 MediaProjection，不需要系统录屏。
 * 直接从辅助自己的抓屏流中保存低频关键帧 + 决策/手势时间线，停止时自动导出 ZIP。
 */
public final class DiagnosticSession {
    private final Context context;
    private final File dir;
    private final File framesDir;
    private final BufferedWriter logWriter;
    private final ExecutorService frameWriter = Executors.newSingleThreadExecutor();
    private final long startedAt = SystemClock.uptimeMillis();
    private final String sessionName;
    private final boolean autoMode;

    private long lastDecisionLogAt = 0L;
    private long lastFrameAt = 0L;
    private int frameIndex = 0;
    private volatile boolean finished = false;

    public DiagnosticSession(Context context, boolean autoMode) throws Exception {
        this.context = context.getApplicationContext();
        this.autoMode = autoMode;
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        sessionName = "RunnerDiag_" + stamp;
        dir = new File(context.getCacheDir(), sessionName);
        framesDir = new File(dir, "frames");
        if (!framesDir.mkdirs() && !framesDir.isDirectory()) {
            throw new IllegalStateException("cannot-create-diag-dir");
        }
        logWriter = new BufferedWriter(new FileWriter(new File(dir, "events.csv"), false));
        logWriter.write("ms,event,action,source,x,confidence,detail\n");
        log("SESSION", "-", "-", 1f, 1f, autoMode ? "auto" : "diagnostic");

        try (BufferedWriter meta = new BufferedWriter(new FileWriter(new File(dir, "meta.txt")))) {
            meta.write("version=0.2.0\n");
            meta.write("mode=" + (autoMode ? "auto" : "diagnostic") + "\n");
            meta.write("format=keyframes+timeline\n");
        }
    }

    public synchronized void recordDecision(ObstacleDetector.Decision d, long now) {
        if (finished) return;
        if (d.action == ObstacleDetector.Action.NONE && now - lastDecisionLogAt < 220L) return;
        lastDecisionLogAt = now;
        log("DECISION", d.action.name(), d.source.name(), d.xNorm, d.confidence, d.debug);
    }

    public synchronized void recordGesture(String event, String action, String detail) {
        if (finished) return;
        log(event, action, "GESTURE", 0f, 0f, detail);
    }

    public void maybeCapture(Image image, ObstacleDetector.Decision d, long now) {
        if (finished || image == null) return;

        long interval = d.action == ObstacleDetector.Action.NONE ? 500L : 150L;
        synchronized (this) {
            if (now - lastFrameAt < interval) return;
            lastFrameAt = now;
        }

        Image.Plane[] planes = image.getPlanes();
        if (planes.length == 0) return;
        Image.Plane plane = planes[0];
        ByteBuffer src = plane.getBuffer();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int width = image.getWidth();
        int height = image.getHeight();

        // 只保存真正有用的游戏区域，并降到约 480px 宽，避免诊断包过大。
        int sx0 = Math.max(0, Math.round(width * 0.05f));
        int sx1 = Math.min(width - 1, Math.round(width * 0.93f));
        int sy0 = Math.max(0, Math.round(height * 0.27f));
        int sy1 = Math.min(height - 1, Math.round(height * 0.80f));

        int srcW = Math.max(1, sx1 - sx0);
        int srcH = Math.max(1, sy1 - sy0);
        int outW = 480;
        int outH = Math.max(200, Math.round(outW * (srcH / (float) srcW)));
        int[] pixels = new int[outW * outH];

        for (int oy = 0; oy < outH; oy++) {
            int sy = sy0 + (int) ((oy / (float) outH) * srcH);
            for (int ox = 0; ox < outW; ox++) {
                int sx = sx0 + (int) ((ox / (float) outW) * srcW);
                int index = sy * rowStride + sx * pixelStride;
                if (index < 0 || index + 2 >= src.limit()) continue;
                int r = src.get(index) & 0xFF;
                int g = src.get(index + 1) & 0xFF;
                int b = src.get(index + 2) & 0xFF;
                pixels[oy * outW + ox] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }

        final Bitmap bitmap = Bitmap.createBitmap(pixels, outW, outH, Bitmap.Config.ARGB_8888);
        final int index;
        synchronized (this) {
            index = frameIndex++;
        }
        final String label = d.action.name() + "_" + d.source.name();
        final long elapsed = now - startedAt;

        frameWriter.submit(() -> {
            File out = new File(framesDir,
                    String.format(Locale.US, "%05d_%07d_%s.jpg", index, elapsed, label));
            try (FileOutputStream fos = new FileOutputStream(out)) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 62, fos);
            } catch (Exception ignored) {
            } finally {
                bitmap.recycle();
            }
        });
    }

    private synchronized void log(String event, String action, String source,
                                  float x, float confidence, String detail) {
        if (finished) return;
        try {
            long ms = SystemClock.uptimeMillis() - startedAt;
            String safe = detail == null ? "" : detail.replace(',', ';').replace('\n', ' ');
            logWriter.write(String.format(Locale.US,
                    "%d,%s,%s,%s,%.3f,%.3f,%s\n",
                    ms, event, action, source, x, confidence, safe));
            logWriter.flush();
        } catch (Exception ignored) {
        }
    }

    public synchronized boolean isFinished() {
        return finished;
    }

    public void finishAsync(FinishListener listener) {
        synchronized (this) {
            if (finished) return;
            finished = true;
            try {
                logWriter.flush();
                logWriter.close();
            } catch (Exception ignored) {
            }
            frameWriter.shutdown();
        }

        new Thread(() -> {
            Uri uri = null;
            try {
                frameWriter.awaitTermination(12, TimeUnit.SECONDS);
                uri = exportZip();
            } catch (Exception ignored) {
            }
            if (listener != null) listener.onFinished(uri, sessionName + ".zip");
            deleteRecursive(dir);
        }, "runner-diag-export").start();
    }

    private Uri exportZip() throws Exception {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, sessionName + ".zip");
        values.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/RunnerDiag");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IllegalStateException("download-insert-failed");

        try (OutputStream os = resolver.openOutputStream(uri);
             ZipOutputStream zos = new ZipOutputStream(os)) {
            zipDirectory(dir, dir, zos);
        }

        ContentValues done = new ContentValues();
        done.put(MediaStore.MediaColumns.IS_PENDING, 0);
        resolver.update(uri, done, null, null);
        return uri;
    }

    private static void zipDirectory(File root, File current, ZipOutputStream zos) throws Exception {
        File[] files = current.listFiles();
        if (files == null) return;
        byte[] buffer = new byte[8192];
        for (File file : files) {
            if (file.isDirectory()) {
                zipDirectory(root, file, zos);
                continue;
            }
            String name = root.toURI().relativize(file.toURI()).getPath();
            zos.putNextEntry(new ZipEntry(name));
            try (FileInputStream fis = new FileInputStream(file)) {
                int n;
                while ((n = fis.read(buffer)) > 0) zos.write(buffer, 0, n);
            }
            zos.closeEntry();
        }
    }

    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursive(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    public interface FinishListener {
        void onFinished(Uri uri, String fileName);
    }
}
