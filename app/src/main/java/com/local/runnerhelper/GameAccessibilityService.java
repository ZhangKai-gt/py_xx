package com.local.runnerhelper;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;

public class GameAccessibilityService extends AccessibilityService {
    private static volatile GameAccessibilityService instance;

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
        // V0.1 不读取其它应用的界面内容，只使用 dispatchGesture() 发送手势。
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        if (instance == this) instance = null;
        return super.onUnbind(intent);
    }

    public boolean jump() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float x = dm.widthPixels * 0.76f;
        float y = dm.heightPixels * 0.76f;

        Path path = new Path();
        path.moveTo(x, y);

        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, 45);

        return dispatchGesture(
                new GestureDescription.Builder().addStroke(stroke).build(),
                null,
                null);
    }

    public boolean slideDown() {
        DisplayMetrics dm = getResources().getDisplayMetrics();

        float x = dm.widthPixels * 0.76f;
        float y1 = dm.heightPixels * 0.62f;
        float y2 = dm.heightPixels * 0.82f;

        Path path = new Path();
        path.moveTo(x, y1);
        path.lineTo(x, y2);

        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, 115);

        return dispatchGesture(
                new GestureDescription.Builder().addStroke(stroke).build(),
                null,
                null);
    }
}
