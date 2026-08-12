package com.local.runnerhelper;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;

public class GameAccessibilityService extends AccessibilityService {
    private static volatile GameAccessibilityService instance;

    public interface GestureListener {
        void onCompleted(String action);
        void onCancelled(String action);
    }

    public static GameAccessibilityService getInstance() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        if (instance == this) instance = null;
        return super.onUnbind(intent);
    }

    public boolean jump(GestureListener listener) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float x = dm.widthPixels * 0.76f;
        float y = dm.heightPixels * 0.76f;

        Path path = new Path();
        path.moveTo(x, y);

        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, 45);

        return dispatch("JUMP",
                new GestureDescription.Builder().addStroke(stroke).build(),
                listener);
    }

    public boolean slideDown(GestureListener listener) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float x = dm.widthPixels * 0.76f;
        float y1 = dm.heightPixels * 0.62f;
        float y2 = dm.heightPixels * 0.82f;

        Path path = new Path();
        path.moveTo(x, y1);
        path.lineTo(x, y2);

        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, 115);

        return dispatch("SLIDE",
                new GestureDescription.Builder().addStroke(stroke).build(),
                listener);
    }

    private boolean dispatch(String action,
                             GestureDescription gesture,
                             GestureListener listener) {
        return dispatchGesture(
                gesture,
                new GestureResultCallback() {
                    @Override
                    public void onCompleted(GestureDescription gestureDescription) {
                        if (listener != null) listener.onCompleted(action);
                    }

                    @Override
                    public void onCancelled(GestureDescription gestureDescription) {
                        if (listener != null) listener.onCancelled(action);
                    }
                },
                null);
    }
}
