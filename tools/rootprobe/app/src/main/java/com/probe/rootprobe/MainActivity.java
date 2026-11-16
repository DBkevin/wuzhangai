package com.probe.rootprobe;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Root 探针：从 App 进程（而不是 adb shell）尝试提权，判断设备是否真的 root。
 *
 * 背景：KernelSU / Magisk 可以把 su 对未授权进程隐藏，
 * 导致 adb shell 看不到 su，但 App 仍能正常提权。
 * 所以必须从 App 上下文测一次。
 *
 * 结果同时输出到：
 *   1) 屏幕（滚动查看）
 *   2) logcat，tag = ROOTPROBE
 *   3) /sdcard/Android/data/com.probe.rootprobe/files/rootprobe.txt
 */
public class MainActivity extends Activity {

    private static final String TAG = "ROOTPROBE";
    private final StringBuilder sb = new StringBuilder();
    private TextView tv;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        tv = new TextView(this);
        tv.setTextSize(11f);
        tv.setPadding(24, 24, 24, 24);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(tv);
        setContentView(scroll);

        tv.setText("检测中…\n\n如果弹出 root 授权弹窗，请点「允许」。");

        new Thread(this::runProbe).start();
    }

    private void line(String s) {
        Log.i(TAG, s);
        sb.append(s).append('\n');
        runOnUiThread(() -> tv.setText(sb.toString()));
    }

    private void runProbe() {
        line("=== ROOT PROBE ===");
        line("app uid       = " + android.os.Process.myUid());
        line("Build.TAGS    = " + Build.TAGS);
        line("Build.TYPE    = " + Build.TYPE);
        line("Build.FINGERPRINT = " + Build.FINGERPRINT);
        line("");

        // 对照组：不经过 su，验证 exec 本身可用
        line("--- 对照组（不经 su）---");
        exec(new String[]{"/system/bin/sh", "-c", "id"}, "sh -c id");
        line("");

        // 各种 su 调用方式
        line("--- su 调用 ---");
        List<String[]> cmds = new ArrayList<>();
        cmds.add(new String[]{"su", "-c", "id"});
        cmds.add(new String[]{"/system/bin/su", "-c", "id"});
        cmds.add(new String[]{"/system/xbin/su", "-c", "id"});
        cmds.add(new String[]{"/sbin/su", "-c", "id"});
        cmds.add(new String[]{"/debug_ramdisk/su", "-c", "id"});
        cmds.add(new String[]{"/product/bin/su", "-c", "id"});
        for (String[] c : cmds) {
            // 用 TextUtils.join 而非 String.join：后者需要 API 24，本项目 minSdk 21
            exec(c, android.text.TextUtils.join(" ", c));
        }
        line("");

        // PATH 里能不能找到 su
        line("--- which su ---");
        exec(new String[]{"/system/bin/sh", "-c", "which su || echo 'not in PATH'"}, "which su");
        exec(new String[]{"/system/bin/sh", "-c", "echo $PATH"}, "PATH");
        line("");

        // 常见路径是否存在
        line("--- 路径探测 ---");
        String[] paths = {
                "/system/bin/su",
                "/system/xbin/su",
                "/sbin/su",
                "/debug_ramdisk/su",
                "/product/bin/su",
                "/data/adb/ksu/bin/su",
                "/data/adb/ap/bin/su",
                "/data/adb/magisk",
                "/data/adb/ksu",
                "/data/adb/ap",
        };
        for (String p : paths) {
            File f = new File(p);
            line("exists(" + p + ") = " + f.exists() + (f.exists() ? "  canRead=" + f.canRead() : ""));
        }
        line("");

        // 用 su 做一次有副作用的验证：读 /data/adb（普通 App 无权限）
        line("--- su 提权实测：ls /data/adb ---");
        exec(new String[]{"su", "-c", "ls /data/adb"}, "su -c ls /data/adb");
        line("");

        line("=== DONE ===");
        line("");
        line("判读方法：");
        line("  任一 su 调用返回 uid=0  → 设备已 root（只是 adb 看不到）");
        line("  全部 su 失败 + sh 正常  → 设备未 root");

        // 落盘一份，便于 adb pull
        try {
            File dir = getExternalFilesDir(null);
            if (dir != null) {
                File out = new File(dir, "rootprobe.txt");
                java.io.FileWriter w = new java.io.FileWriter(out);
                w.write(sb.toString());
                w.close();
                Log.i(TAG, "written to " + out.getAbsolutePath());
            }
        } catch (Exception e) {
            Log.i(TAG, "write failed: " + e);
        }
    }

    private void exec(String[] cmd, String label) {
        try {
            Process p = Runtime.getRuntime().exec(cmd);
            String out = drain(p.getInputStream());
            String err = drain(p.getErrorStream());
            int code = p.waitFor();
            line("[" + label + "] exit=" + code);
            if (!out.isEmpty()) line("    out: " + out);
            if (!err.isEmpty()) line("    err: " + err);
        } catch (Exception e) {
            line("[" + label + "] 异常 " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private String drain(InputStream in) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(in));
        StringBuilder s = new StringBuilder();
        String l;
        while ((l = r.readLine()) != null) {
            s.append(l).append(" | ");
        }
        return s.toString().trim();
    }
}
