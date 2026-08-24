package com.hyper.volumepager;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * 模块内日志查看器:通过 root 执行 logcat 过滤本模块标签,实时流式显示。
 * 设备无 Root 时会提示无法读取。
 */
public class LogActivity extends Activity {

    private static final String TAG = "HyperVolumePager";
    private static final int MAX_CHARS = 400_000;

    private Process proc;
    private volatile boolean reading = false;

    private TextView logView;
    private ScrollView scroller;
    private Button toggle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        float d = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding((int) (8 * d), (int) (6 * d), (int) (8 * d), (int) (6 * d));
        toggle = cell(bar, "▶ 启动", v -> toggleRead());
        cell(bar, "清空缓冲", v -> {
            clearBuffer();
            logView.setText("");
        });
        cell(bar, "复制全部", v -> copyAll());

        TextView hint = new TextView(this);
        hint.setText("标签过滤: " + TAG + " · 需 Root 授权\n复现顺序: 清空缓冲 → 按音量键/展开面板 → 回来看日志");
        hint.setTextSize(11);
        hint.setPadding((int) (10 * d), 0, (int) (10 * d), (int) (4 * d));

        logView = new TextView(this);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(11);
        logView.setTextColor(0xFFEAEAEA);
        logView.setBackgroundColor(0xFF101010);
        logView.setPadding((int) (8 * d), (int) (8 * d), (int) (8 * d), (int) (8 * d));

        scroller = new ScrollView(this);
        scroller.addView(logView);

        root.addView(bar);
        root.addView(hint);
        root.addView(scroller, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    private Button cell(LinearLayout bar, String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(4, 0, 4, 0);
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        bar.addView(b);
        return b;
    }

    private void toggleRead() {
        if (reading) stopRead();
        else startRead();
    }

    private void startRead() {
        if (reading) return;
        try {
            proc = Runtime.getRuntime().exec(
                    new String[]{"su", "-c", "logcat -v time -s " + TAG});
        } catch (Exception e) {
            append("[启动失败] " + e + "\n请确认已在 Root 管理器中授权本应用\n");
            return;
        }
        reading = true;
        toggle.setText("■ 停止");
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(proc.getInputStream()))) {
                String line;
                while (reading && (line = r.readLine()) != null) {
                    final String s = line + "\n";
                    runOnUiThread(() -> append(s));
                }
            } catch (Exception ignored) { }
            runOnUiThread(() -> {
                if (!reading) return;
                reading = false;
                toggle.setText("▶ 启动");
                append("[流结束]\n");
            });
        }, "hvp-logcat");
        t.start();
    }

    private void stopRead() {
        reading = false;
        if (proc != null) proc.destroy();
        toggle.setText("▶ 启动");
    }

    private void clearBuffer() {
        try {
            Runtime.getRuntime().exec(new String[]{"su", "-c", "logcat -c"});
        } catch (Throwable ignored) { }
    }

    private void copyAll() {
        String text = logView.getText().toString();
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText(TAG, text));
        Toast.makeText(this, "已复制 " + text.length() + " 字符", Toast.LENGTH_SHORT).show();
    }

    private void append(String s) {
        CharSequence old = logView.getText();
        StringBuilder sb = new StringBuilder(old.length() + s.length());
        sb.append(old).append(s);
        String out = sb.toString();
        if (out.length() > MAX_CHARS) {
            int cut = out.indexOf('\n', out.length() - MAX_CHARS / 2);
            if (cut > 0) out = out.substring(cut + 1);
        }
        logView.setText(out);
        scroller.post(() -> scroller.fullScroll(View.FOCUS_DOWN));
    }

    @Override
    protected void onDestroy() {
        reading = false;
        if (proc != null) proc.destroy();
        super.onDestroy();
    }
}
