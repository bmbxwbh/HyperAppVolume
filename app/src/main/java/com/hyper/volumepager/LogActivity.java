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
import java.io.OutputStream;

/**
 * 模块内日志查看器(HyperCeiler 同款「常驻 su shell」方案):
 *
 * 关键点:hook 日志打在 SystemUI 进程(UID 属于系统界面),
 * 普通 App 进程直接执行 logcat 会被 UID 过滤而读不到;
 * 因此必须先取得 root shell(exec("su")),再经 stdin 下发命令——
 * 与终端里「先 su 再 logcat」的成功路径一致。
 */
public class LogActivity extends Activity {

    private static final String TAG = "HyperVolumePager";
    private static final int MAX_CHARS = 400_000;
    private static final String READY_MARK = "__HVP_READY__";

    private Process suProc;
    private OutputStream suIn;
    private BufferedReader stdoutReader;
    private Thread readerThread;

    private volatile boolean shellAlive = false;
    private volatile boolean ready = false;      // root 握手完成
    private volatile boolean logcatOn = false;   // 前台 logcat 运行中

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
            suExecOneShot("logcat -c");
            append("[已发送清空命令]\n");
        });
        cell(bar, "复制全部", v -> copyAll());

        TextView hint = new TextView(this);
        hint.setText("标签: " + TAG + " · 需 Root 授权\n用法: ▶启动 → 清空缓冲 → 复现操作 → 回来看实时日志");
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

    // ==================================================================
    // 常驻 su shell
    // ==================================================================

    private synchronized boolean ensureSuShell() {
        if (shellAlive && suProc != null && suIn != null && stdoutReader != null) return true;
        stopShellQuiet();
        try {
            suProc = Runtime.getRuntime().exec("su");
            suIn = suProc.getOutputStream();
            stdoutReader = new BufferedReader(
                    new InputStreamReader(suProc.getInputStream()));
            shellAlive = true;
            ready = false;
        } catch (Throwable t) {
            Logx.e("打开 su shell 失败", t);
            shellAlive = false;
            return false;
        }
        // 读取线程(每个 shell 一条,持续消费 stdout 直到 EOF)
        readerThread = new Thread(() -> {
            try {
                String line;
                while ((line = stdoutReader.readLine()) != null) {
                    final String s = line + "\n";
                    if (line.contains(READY_MARK)) {
                        ready = true;
                        runOnUiThread(() -> append("[Root shell 就绪]\n"));
                        continue;
                    }
                    final boolean isLogLine = logcatOn;
                    runOnUiThread(() -> append(s));
                }
            } catch (Throwable ignored) { }
            // EOF:su shell 已退出
            shellAlive = false;
            ready = false;
            logcatOn = false;
            runOnUiThread(() -> {
                if (toggle != null) toggle.setText("▶ 启动");
            });
        }, "hvp-reader");
        readerThread.setDaemon(true);
        readerThread.start();

        // 握手探测:触发类初始化并验证 uid=0
        suWrite("echo " + READY_MARK + "$(id -u)");
        return true;
    }

    private synchronized void suWrite(String cmd) {
        try {
            suIn.write((cmd + "\n").getBytes());
            suIn.flush();
        } catch (Throwable t) {
            shellAlive = false;
            ready = false;
        }
    }

    private synchronized void suExecOneShot(String cmd) {
        if (ensureSuShell()) suWrite(cmd);
        else Toast.makeText(this, "需要 Root 授权", Toast.LENGTH_SHORT).show();
    }

    private synchronized void stopShellQuiet() {
        shellAlive = false;
        ready = false;
        logcatOn = false;
        try { if (suIn != null) { suIn.write("exit\n"); suIn.flush(); } } catch (Throwable ignored) { }
        try { if (suProc != null) suProc.destroy(); } catch (Throwable ignored) { }
        suProc = null;
        suIn = null;
        stdoutReader = null;
        readerThread = null;
    }

    // ==================================================================
    // 启动 / 停止 / 清空 / 复制
    // ==================================================================

    private void toggleRead() {
        if (logcatOn) {
            stopAll();
            append("[已停止]\n");
        } else {
            startLogcat();
        }
    }

    private synchronized void startLogcat() {
        if (!ensureSuShell()) {
            append("[无法打开 su shell]\n");
            return;
        }
        long deadline = System.currentTimeMillis() + 3000;
        while (!ready && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(50); } catch (InterruptedException ignored) { }
        }
        if (!ready) {
            append("[等待 Root 授权超时]\n");
            return;
        }
        logcatOn = true;
        toggle.setText("■ 停止");
        suWrite("logcat -v time -s " + TAG);
        append("[logcat 已启动]\n");
    }

    private synchronized void stopAll() {
        logcatOn = false;
        suWrite("exit\n");
        stopShellQuiet();
    }

    @Override
    protected void onDestroy() {
        stopShellQuiet();
        super.onDestroy();
    }

    // ==================================================================
    // 显示
    // ==================================================================

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

    private void copyAll() {
        String text = logView.getText().toString();
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText(TAG, text));
        Toast.makeText(this, "已复制 " + text.length() + " 字符", Toast.LENGTH_SHORT).show();
    }
}
