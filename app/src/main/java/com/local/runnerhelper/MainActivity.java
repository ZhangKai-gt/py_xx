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
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 1001;
    private MediaProjectionManager projectionManager;
    private boolean pendingDryRun = true;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        projectionManager = getSystemService(MediaProjectionManager.class);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(28), dp(54), dp(28), dp(28));
        root.setBackgroundColor(Color.rgb(15, 15, 15));

        TextView title = new TextView(this);
        title.setText("Runner");
        title.setTextColor(Color.WHITE);
        title.setTextSize(30);
        title.setGravity(Gravity.CENTER);
        root.addView(title, fullWidth(dp(56)));

        status = new TextView(this);
        status.setText("待机");
        status.setTextColor(Color.rgb(150, 150, 150));
        status.setTextSize(14);
        status.setGravity(Gravity.CENTER);
        root.addView(status, fullWidth(dp(42)));

        root.addView(space(dp(26)));

        Button permission = makeButton("权限", false);
        permission.setOnClickListener(v -> openPermissions());
        root.addView(permission, buttonLp());

        Button detect = makeButton("识别", true);
        detect.setOnClickListener(v -> startCapture(true));
        root.addView(detect, buttonLp());

        Button auto = makeButton("自动", true);
        auto.setOnClickListener(v -> startCapture(false));
        root.addView(auto, buttonLp());

        Button stop = makeButton("停止", false);
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, CaptureService.class));
            status.setText("待机");
        });
        root.addView(stop, buttonLp());

        setContentView(root);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2001);
        }
    }

    private void openPermissions() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
            Toast.makeText(this, "允许悬浮窗后，再点一次“权限”开启无障碍", Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
    }

    private void startCapture(boolean dryRun) {
        pendingDryRun = dryRun;
        status.setText(dryRun ? "识别" : "自动");
        Intent captureIntent = projectionManager.createScreenCaptureIntent();
        startActivityForResult(captureIntent, REQ_CAPTURE);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) return;

        if (resultCode != RESULT_OK || data == null) {
            status.setText("待机");
            return;
        }

        Intent service = new Intent(this, CaptureService.class);
        service.putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode);
        service.putExtra(CaptureService.EXTRA_RESULT_DATA, data);
        service.putExtra(CaptureService.EXTRA_DRY_RUN, pendingDryRun);

        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(service);
        } else {
            startService(service);
        }

        moveTaskToBack(true);
    }

    private Button makeButton(String text, boolean primary) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(18);
        b.setTextColor(primary ? Color.BLACK : Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, 0, 0, 0);

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(18));
        if (primary) {
            bg.setColor(Color.WHITE);
        } else {
            bg.setColor(Color.rgb(31, 31, 31));
            bg.setStroke(dp(1), Color.rgb(60, 60, 60));
        }
        b.setBackground(bg);
        return b;
    }

    private LinearLayout.LayoutParams buttonLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                dp(220), dp(52));
        lp.setMargins(0, dp(7), 0, dp(7));
        return lp;
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
