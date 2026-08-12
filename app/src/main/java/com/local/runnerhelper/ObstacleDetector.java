package com.local.runnerhelper;

import java.nio.ByteBuffer;
import java.util.Locale;

/**
 * V0.2 detector.
 *
 * 目标不再只是“看深色像素”：
 * 1) 地面深色大块 -> 地面障碍 -> 跳
 * 2) 中高位深色大块且地面空 -> 悬空障碍 -> 下滑
 * 3) 黄色/橙色收集物 -> 奖励目标；高于自然碰撞线时主动跳去吃
 * 4) 连续多帧确认 + 同目标去重，降低抖动和重复动作
 */
public final class ObstacleDetector {

    public enum Action { NONE, JUMP, SLIDE }
    public enum Source { NONE, GROUND_OBSTACLE, OVERHEAD_OBSTACLE, REWARD }

    public static final class Decision {
        public final Action action;
        public final Source source;
        public final float xNorm;
        public final float confidence;
        public final String debug;

        Decision(Action action, Source source, float xNorm, float confidence, String debug) {
            this.action = action;
            this.source = source;
            this.xNorm = xNorm;
            this.confidence = confidence;
            this.debug = debug;
        }
    }

    private static final float X_START = 0.20f;
    private static final float X_END = 0.90f;

    private static final float HIGH_Y0 = 0.37f;
    private static final float HIGH_Y1 = 0.59f;
    private static final float LOW_Y0 = 0.58f;
    private static final float LOW_Y1 = 0.75f;
    private static final float REWARD_Y0 = 0.40f;
    private static final float REWARD_Y1 = 0.70f;

    private static final float GROUND_TRIGGER_X = 0.70f;
    private static final float OVERHEAD_TRIGGER_X = 0.60f;
    private static final float REWARD_TRIGGER_X = 0.62f;

    private static final int DARK_LUMA = 108;
    private static final float MIN_DARK_WIDTH = 0.055f;
    private static final float MIN_REWARD_WIDTH = 0.025f;

    private Action lastCandidateAction = Action.NONE;
    private Source lastCandidateSource = Source.NONE;
    private float lastCandidateX = 1f;
    private int stableFrames = 0;

    private Action lastEmittedAction = Action.NONE;
    private Source lastEmittedSource = Source.NONE;
    private float lastEmittedX = -1f;

    public synchronized Decision analyze(
            ByteBuffer rgba,
            int width,
            int height,
            int pixelStride,
            int rowStride) {

        if (rgba == null || width <= 0 || height <= 0 || pixelStride < 3) {
            return none("invalid-frame");
        }

        BandHit high = scanDarkBand(rgba, width, height, pixelStride, rowStride, HIGH_Y0, HIGH_Y1);
        BandHit low = scanDarkBand(rgba, width, height, pixelStride, rowStride, LOW_Y0, LOW_Y1);
        ColorHit reward = scanReward(rgba, width, height, pixelStride, rowStride);

        // 向日葵中心本身偏暗。若一个很小的深色块与黄色奖励重合，就不要把它当障碍。
        if (low.hit && reward.hit
                && Math.abs(low.leftNorm - reward.leftNorm) < 0.075f
                && low.widthNorm < 0.105f) {
            low = BandHit.none();
        }
        if (high.hit && reward.hit
                && Math.abs(high.leftNorm - reward.leftNorm) < 0.075f
                && high.widthNorm < 0.105f) {
            high = BandHit.none();
        }

        Action candidateAction = Action.NONE;
        Source candidateSource = Source.NONE;
        float candidateX = 1f;
        float confidence = 0f;

        if (low.hit && low.leftNorm <= GROUND_TRIGGER_X) {
            candidateAction = Action.JUMP;
            candidateSource = Source.GROUND_OBSTACLE;
            candidateX = low.leftNorm;
            confidence = low.confidence;
        } else if (high.hit
                && high.leftNorm <= OVERHEAD_TRIGGER_X
                && (!low.hit || Math.abs(high.leftNorm - low.leftNorm) > 0.11f)) {
            candidateAction = Action.SLIDE;
            candidateSource = Source.OVERHEAD_OBSTACLE;
            candidateX = high.leftNorm;
            confidence = high.confidence;
        } else if (reward.hit
                && reward.leftNorm <= REWARD_TRIGGER_X
                && reward.centerYNorm < 0.625f) {
            // 较高的奖励需要跳；低位奖励保持自然跑动即可吃到。
            candidateAction = Action.JUMP;
            candidateSource = Source.REWARD;
            candidateX = reward.leftNorm;
            confidence = reward.confidence;
        }

        if (candidateAction == Action.NONE) {
            lastCandidateAction = Action.NONE;
            lastCandidateSource = Source.NONE;
            stableFrames = 0;
            // 当最近目标消失后重新武装，允许下一个目标触发。
            lastEmittedAction = Action.NONE;
            lastEmittedSource = Source.NONE;
            lastEmittedX = -1f;
            return none("H=" + high.shortText() + " L=" + low.shortText() + " R=" + reward.shortText());
        }

        boolean sameCandidate = candidateAction == lastCandidateAction
                && candidateSource == lastCandidateSource
                && Math.abs(candidateX - lastCandidateX) < 0.16f;

        if (sameCandidate) {
            stableFrames++;
        } else {
            stableFrames = 1;
            lastCandidateAction = candidateAction;
            lastCandidateSource = candidateSource;
        }
        lastCandidateX = candidateX;

        int requiredFrames = candidateSource == Source.REWARD ? 3 : 2;
        boolean emergency = candidateSource != Source.REWARD && candidateX < 0.44f;
        if (stableFrames < requiredFrames && !emergency) {
            return none("confirm " + candidateSource + " x=" + fmt(candidateX));
        }

        // 同一个目标不要连续触发。新目标从右侧接替时 x 会明显回跳。
        boolean sameAsEmitted = candidateAction == lastEmittedAction
                && candidateSource == lastEmittedSource
                && lastEmittedX >= 0f
                && candidateX <= lastEmittedX + 0.10f;
        if (sameAsEmitted) {
            return none("latched " + candidateSource + " x=" + fmt(candidateX));
        }

        lastEmittedAction = candidateAction;
        lastEmittedSource = candidateSource;
        lastEmittedX = candidateX;

        String debug = candidateSource
                + " x=" + fmt(candidateX)
                + " c=" + fmt(confidence)
                + " R=" + reward.shortText();

        return new Decision(candidateAction, candidateSource, candidateX, confidence, debug);
    }

    private BandHit scanDarkBand(
            ByteBuffer rgba, int width, int height, int pixelStride, int rowStride,
            float y0Norm, float y1Norm) {

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
                if (luma < DARK_LUMA) dark++;
            }

            float density = valid == 0 ? 0f : (float) dark / valid;
            boolean columnHit = density >= 0.115f;

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
                if (runWidth >= MIN_DARK_WIDTH && (bestStart < 0 || runStart < bestStart)) {
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
            if (runWidth >= MIN_DARK_WIDTH && (bestStart < 0 || runStart < bestStart)) {
                bestStart = runStart;
                bestEnd = runEnd;
                bestDensity = avgDensity;
            }
        }

        if (bestStart < 0) return BandHit.none();

        float left = bestStart / (float) width;
        float widthNorm = (bestEnd - bestStart + stepX) / (float) width;
        float sizeFactor = Math.min(1f, widthNorm / 0.17f);
        float densityFactor = Math.min(1f, bestDensity / 0.38f);
        float confidence = 0.45f * sizeFactor + 0.55f * densityFactor;
        return new BandHit(true, left, widthNorm, confidence);
    }

    private ColorHit scanReward(
            ByteBuffer rgba, int width, int height, int pixelStride, int rowStride) {

        int x0 = Math.max(0, Math.round(width * X_START));
        int x1 = Math.min(width - 1, Math.round(width * X_END));
        int y0 = Math.max(0, Math.round(height * REWARD_Y0));
        int y1 = Math.min(height - 1, Math.round(height * REWARD_Y1));

        int stepX = Math.max(3, width / 260);
        int stepY = Math.max(3, height / 390);

        int runStart = -1;
        int runEnd = -1;
        int bestStart = -1;
        int bestEnd = -1;
        long runYSum = 0;
        int runHits = 0;
        long bestYSum = 0;
        int bestHits = 0;

        for (int x = x0; x <= x1; x += stepX) {
            int hits = 0;
            long ySum = 0;
            int valid = 0;
            for (int y = y0; y <= y1; y += stepY) {
                int index = y * rowStride + x * pixelStride;
                if (index < 0 || index + 2 >= rgba.limit()) continue;
                int r = rgba.get(index) & 0xFF;
                int g = rgba.get(index + 1) & 0xFF;
                int b = rgba.get(index + 2) & 0xFF;
                valid++;

                boolean yellowOrange = r >= 165
                        && g >= 100
                        && b <= 125
                        && r - b >= 65
                        && g - b >= 25;
                if (yellowOrange) {
                    hits++;
                    ySum += y;
                }
            }

            float density = valid == 0 ? 0f : (float) hits / valid;
            boolean columnHit = density >= 0.045f;

            if (columnHit) {
                if (runStart < 0) {
                    runStart = x;
                    runYSum = 0;
                    runHits = 0;
                }
                runEnd = x;
                runYSum += ySum;
                runHits += hits;
            } else if (runStart >= 0) {
                float w = (runEnd - runStart + stepX) / (float) width;
                if (w >= MIN_REWARD_WIDTH && (bestStart < 0 || runStart < bestStart)) {
                    bestStart = runStart;
                    bestEnd = runEnd;
                    bestYSum = runYSum;
                    bestHits = runHits;
                }
                runStart = -1;
                runEnd = -1;
            }
        }

        if (runStart >= 0) {
            float w = (runEnd - runStart + stepX) / (float) width;
            if (w >= MIN_REWARD_WIDTH && (bestStart < 0 || runStart < bestStart)) {
                bestStart = runStart;
                bestEnd = runEnd;
                bestYSum = runYSum;
                bestHits = runHits;
            }
        }

        if (bestStart < 0 || bestHits <= 0) return ColorHit.none();

        float left = bestStart / (float) width;
        float widthNorm = (bestEnd - bestStart + stepX) / (float) width;
        float centerY = (bestYSum / (float) bestHits) / height;
        float confidence = Math.min(1f, 0.35f + widthNorm * 4.5f);
        return new ColorHit(true, left, widthNorm, centerY, confidence);
    }

    private static Decision none(String debug) {
        return new Decision(Action.NONE, Source.NONE, 1f, 0f, debug);
    }

    private static String fmt(float v) {
        return String.format(Locale.US, "%.2f", v);
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

        static BandHit none() { return new BandHit(false, 1f, 0f, 0f); }
        String shortText() { return hit ? fmt(leftNorm) + "/" + fmt(widthNorm) : "-"; }
    }

    private static final class ColorHit {
        final boolean hit;
        final float leftNorm;
        final float widthNorm;
        final float centerYNorm;
        final float confidence;

        ColorHit(boolean hit, float leftNorm, float widthNorm, float centerYNorm, float confidence) {
            this.hit = hit;
            this.leftNorm = leftNorm;
            this.widthNorm = widthNorm;
            this.centerYNorm = centerYNorm;
            this.confidence = confidence;
        }

        static ColorHit none() { return new ColorHit(false, 1f, 0f, 1f, 0f); }
        String shortText() {
            return hit ? fmt(leftNorm) + "/y" + fmt(centerYNorm) + "/w" + fmt(widthNorm) : "-";
        }
    }
}
