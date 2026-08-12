package com.local.runnerhelper;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 1001;

    private MediaProjectionManager projectionManager;
    private Button mainButton;
    private TextView modeButton;
    private TextView status;
    private boolean autoMode = false;
    private boolean pendingStart = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        projectionManager = getSystemService(MediaProjectionManager.class);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(28), dp(62), dp(28), dp(28));
        root.setBackgroundColor(Color.rgb(15, 15, 15));

        TextView title = new TextView(this);
        title.setText("Runner");
        title.setTextColor(Color.WHITE);
        title.setTextSize(30);
        title.setGravity(Gravity.CENTER);
        root.addView(title, fullWidth(dp(58)));

        status = new TextView(this);
        status.setText("待机");
        status.setTextColor(Color.rgb(135, 135, 135));
        status.setTextSize(13);
        status.setGravity(Gravity.CENTER);
        root.addView(status, fullWidth(dp(36)));

        root.addView(space(dp(48)));

        mainButton = new Button(this);
        mainButton.setText("开始");
        mainButton.setAllCaps(false);
        mainButton.setTextSize(22);
        mainButton.setTextColor(Color.BLACK);
        mainButton.setPadding(0, 0, 0, 0);
        GradientDrawable mainBg = new GradientDrawable();
        mainBg.setColor(Color.WHITE);
        mainBg.setCornerRadius(dp(32));
        mainButton.setBackground(mainBg);
        LinearLayout.LayoutParams mainLp = new LinearLayout.LayoutParams(dp(230), dp(64));
        root.addView(mainButton, mainLp);

        root.addView(space(dp(18)));

        modeButton = new TextView(this);
        modeButton.setText("诊断");
        modeButton.setTextColor(Color.rgb(205, 205, 205));
        modeButton.setTextSize(15);
        modeButton.setGravity(Gravity.CENTER);
        GradientDrawable modeBg = new GradientDrawable();
        modeBg.setColor(Color.rgb(30, 30, 30));
        modeBg.setCornerRadius(dp(18));
        modeBg.setStroke(dp(1), Color.rgb(60, 60, 60));
        modeButton.setBackground(modeBg);
        LinearLayout.LayoutParams modeLp = new LinearLayout.LayoutParams(dp(92), dp(38));
        root.addView(modeButton, modeLp);

        mainButton.setOnClickListener(v -> {
            if (CaptureService.isRunning()) {
                stopService(new Intent(this, CaptureService.class));
                mainButton.setText("开始");
                status.setText("保存中");
                getWindow().getDecorView().postDelayed(() -> status.setText("待机"), 900);
                return;
            }
            pendingStart = true;
            continueStartFlow();
        });

        modeButton.setOnClickListener(v -> {
            if (CaptureService.isRunning()) return;
            autoMode = !autoMode;
            modeButton.setText(autoMode ? "自动" : "诊断");
            status.setText("待机");
        });

        setContentView(root);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2001);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (CaptureService.isRunning()) {
            mainButton.setText("停止");
            status.setText(autoMode ? "自动" : "诊断");
            return;
        }
        if (pendingStart) {
            getWindow().getDecorView().postDelayed(this::continueStartFlow, 250);
        } else {
            mainButton.setText("开始");
        }
    }

    private void continueStartFlow() {
        if (!pendingStart || CaptureService.isRunning()) return;

        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            status.setText("悬浮窗");
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
            return;
        }

        if (!isAccessibilityEnabled()) {
            status.setText("无障碍");
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }

        pendingStart = false;
        status.setText("录屏授权");
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    private boolean isAccessibilityEnabled() {
        String enabled = Settings.Secure.getString(
                getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return enabled != null
                && enabled.toLowerCase(java.util.Locale.ROOT)
                .contains(getPackageName().toLowerCase(java.util.Locale.ROOT));
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) return;

        if (resultCode != RESULT_OK || data == null) {
            status.setText("待机");
            mainButton.setText("开始");
            return;
        }

        Intent service = new Intent(this, CaptureService.class);
        service.putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode);
        service.putExtra(CaptureService.EXTRA_RESULT_DATA, data);
        service.putExtra(CaptureService.EXTRA_AUTO_MODE, autoMode);

        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(service);
        } else {
            startService(service);
        }

        mainButton.setText("停止");
        status.setText(autoMode ? "自动" : "诊断");
        moveTaskToBack(true);
    }

    private LinearLayout.LayoutParams fullWidth(int height) {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, height);
    }

    private Space space(int height) {
        Space s = new Space(this);
        s.setLayoutParams(fullWidth(height));
        return s;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
