package com.local.runnerhelper;

import java.nio.ByteBuffer;

/**
 * V0.1：不依赖神经网络 / OpenCV。
 *
 * 从 MediaProjection 的 RGBA 帧中只扫描角色前方两条窄区域：
 *  - high band：用于发现悬空、需要下滑的深色大障碍
 *  - low band：用于发现地面、坑洞、木桩、藤蔓等需要跳跃的障碍
 *
 * 所有坐标使用屏幕比例，不绑定 720×1584 或 1080×2376。
 */
public final class ObstacleDetector {

    public enum Action {
        NONE, JUMP, SLIDE
    }

    public static final class Decision {
        public final Action action;
        public final float xNorm;
        public final float confidence;
        public final String debug;

        Decision(Action action, float xNorm, float confidence, String debug) {
            this.action = action;
            this.xNorm = xNorm;
            this.confidence = confidence;
            this.debug = debug;
        }
    }

    private static final float X_START = 0.20f;
    private static final float X_END = 0.88f;

    private static final float HIGH_Y0 = 0.39f;
    private static final float HIGH_Y1 = 0.61f;
    private static final float LOW_Y0 = 0.59f;
    private static final float LOW_Y1 = 0.74f;

    private static final float JUMP_TRIGGER_X = 0.67f;
    private static final float SLIDE_TRIGGER_X = 0.58f;

    private static final int DARK_LUMA = 112;
    private static final float MIN_BLOB_WIDTH = 0.045f;

    public Decision analyze(
            ByteBuffer rgba,
            int width,
            int height,
            int pixelStride,
            int rowStride) {

        if (rgba == null || width <= 0 || height <= 0 || pixelStride < 3) {
            return new Decision(Action.NONE, 1f, 0f, "invalid-frame");
        }

        BandHit high = scanBand(
                rgba, width, height, pixelStride, rowStride,
                HIGH_Y0, HIGH_Y1);

        BandHit low = scanBand(
                rgba, width, height, pixelStride, rowStride,
                LOW_Y0, LOW_Y1);

        if (low.hit && low.leftNorm <= JUMP_TRIGGER_X) {
            return new Decision(
                    Action.JUMP,
                    low.leftNorm,
                    low.confidence,
                    "low x=" + fmt(low.leftNorm) + " w=" + fmt(low.widthNorm));
        }

        if (high.hit
                && high.leftNorm <= SLIDE_TRIGGER_X
                && (!low.hit || Math.abs(high.leftNorm - low.leftNorm) > 0.12f)) {
            return new Decision(
                    Action.SLIDE,
                    high.leftNorm,
                    high.confidence,
                    "high x=" + fmt(high.leftNorm) + " w=" + fmt(high.widthNorm));
        }

        float nearest = 1f;
        if (low.hit) nearest = Math.min(nearest, low.leftNorm);
        if (high.hit) nearest = Math.min(nearest, high.leftNorm);

        return new Decision(
                Action.NONE,
                nearest,
                Math.max(low.confidence, high.confidence),
                "H:" + high.shortText() + " L:" + low.shortText());
    }

    private BandHit scanBand(
            ByteBuffer rgba,
            int width,
            int height,
            int pixelStride,
            int rowStride,
            float y0Norm,
            float y1Norm) {

        int x0 = Math.max(0, Math.round(width * X_START));
        int x1 = Math.min(width - 1, Math.round(width * X_END));
        int y0 = Math.max(0, Math.round(height * y0Norm));
        int y1 = Math.min(height - 1, Math.round(height * y1Norm));

        int stepX = Math.max(3, width / 240);
        int stepY = Math.max(3, height / 360);

        int runStart = -1;
        int runEnd = -1;
        int bestStart = -1;
        int bestEnd = -1;
        float bestDensity = 0f;
        float runDensitySum = 0f;
        int runColumns = 0;

        for (int x = x0; x <= x1; x += stepX) {
            int dark = 0;
            int valid = 0;

            for (int y = y0; y <= y1; y += stepY) {
                int index = y * rowStride + x * pixelStride;
                if (index < 0 || index + 2 >= rgba.limit()) continue;

                int r = rgba.get(index) & 0xFF;
                int g = rgba.get(index + 1) & 0xFF;
                int b = rgba.get(index + 2) & 0xFF;
                valid++;

                int luma = (r * 3 + g * 6 + b) / 10;
                if (luma < DARK_LUMA) {
                    dark++;
                }
            }

            float density = valid == 0 ? 0f : (float) dark / (float) valid;
            boolean columnHit = density >= 0.10f;

            if (columnHit) {
                if (runStart < 0) {
                    runStart = x;
                    runDensitySum = 0f;
                    runColumns = 0;
                }
                runEnd = x;
                runDensitySum += density;
                runColumns++;
            } else if (runStart >= 0) {
                float runWidth = (runEnd - runStart + stepX) / (float) width;
                float avgDensity = runColumns == 0 ? 0f : runDensitySum / runColumns;
                if (runWidth >= MIN_BLOB_WIDTH
                        && (bestStart < 0 || runStart < bestStart)) {
                    bestStart = runStart;
                    bestEnd = runEnd;
                    bestDensity = avgDensity;
                }
                runStart = -1;
                runEnd = -1;
            }
        }

        if (runStart >= 0) {
            float runWidth = (runEnd - runStart + stepX) / (float) width;
            float avgDensity = runColumns == 0 ? 0f : runDensitySum / runColumns;
            if (runWidth >= MIN_BLOB_WIDTH
                    && (bestStart < 0 || runStart < bestStart)) {
                bestStart = runStart;
                bestEnd = runEnd;
                bestDensity = avgDensity;
            }
        }

        if (bestStart < 0) {
            return BandHit.none();
        }

        float left = bestStart / (float) width;
        float widthNorm = (bestEnd - bestStart + stepX) / (float) width;
        float sizeFactor = Math.min(1f, widthNorm / 0.16f);
        float densityFactor = Math.min(1f, bestDensity / 0.35f);
        float confidence = 0.45f * sizeFactor + 0.55f * densityFactor;

        return new BandHit(true, left, widthNorm, confidence);
    }

    private static String fmt(float v) {
        return String.format(java.util.Locale.US, "%.2f", v);
    }

    private static final class BandHit {
        final boolean hit;
        final float leftNorm;
        final float widthNorm;
        final float confidence;

        BandHit(boolean hit, float leftNorm, float widthNorm, float confidence) {
            this.hit = hit;
            this.leftNorm = leftNorm;
            this.widthNorm = widthNorm;
            this.confidence = confidence;
        }

        static BandHit none() {
            return new BandHit(false, 1f, 0f, 0f);
        }

        String shortText() {
            if (!hit) return "-";
            return fmt(leftNorm) + "/" + fmt(widthNorm);
        }
    }
}
