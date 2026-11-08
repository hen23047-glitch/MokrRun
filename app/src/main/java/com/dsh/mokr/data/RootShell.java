package com.dsh.mokr.data;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;

/**
 * 极简 root 执行器。
 *
 * 不同 root 方案把 su 放在不同位置（Magisk / KernelSU / SukiSU / 各种 fork），
 * 所以这里逐个尝试，找到第一个能真正拿到 uid=0 的路径。
 * 调用时系统会弹出 root 授权框，用户点一次「允许」即可。
 */
public final class RootShell {

    private static final String TAG = "MokrRunRoot";

    /** 常见 su 位置，按命中概率排序 */
    private static final String[] CANDIDATES = {
            "su",
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/debug_ramdisk/su",
            "/data/adb/ksu/bin/su",
            "/data/adb/magisk/su",
            "/data/adb/ap/bin/su",
            "/data/local/bin/su",
    };

    private static String sWorking = null;
    private static boolean sChecked = false;

    private RootShell() {}

    /** 探测可用的 su 路径；结果会缓存 */
    public static String findSu() {
        if (sChecked) return sWorking;
        sChecked = true;
        for (int i = 0; i < CANDIDATES.length; i++) {
            String out = runRaw(CANDIDATES[i], "id");
            if (out != null && out.contains("uid=0")) {
                sWorking = CANDIDATES[i];
                Log.i(TAG, "usable su: " + sWorking);
                return sWorking;
            }
        }
        Log.w(TAG, "no usable su found");
        return null;
    }

    public static boolean available() {
        return findSu() != null;
    }

    /** 执行一条命令，返回 stdout+stderr；失败返回 null */
    public static String exec(String command) {
        String su = findSu();
        if (su == null) return null;
        return runRaw(su, command);
    }

    private static String runRaw(String suPath, String command) {
        Process p = null;
        try {
            p = new ProcessBuilder(suPath, "-c", command)
                    .redirectErrorStream(true)
                    .start();
            // 有些实现需要显式关闭 stdin 才会执行
            try {
                OutputStream os = p.getOutputStream();
                os.write('\n');
                os.flush();
                os.close();
            } catch (Throwable ignored) {
            }

            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            long deadline = System.currentTimeMillis() + 8000L;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
                if (System.currentTimeMillis() > deadline) break;
            }
            p.waitFor();
            return sb.toString();
        } catch (Throwable t) {
            return null;
        } finally {
            if (p != null) {
                try { p.destroy(); } catch (Throwable ignored) {}
            }
        }
    }

    /** 用 root 授予自己 secure settings 权限（这是最需要 root 帮的一步） */
    public static String grantSelfSecureSettings(String pkg) {
        return exec("pm grant " + pkg + " android.permission.WRITE_SECURE_SETTINGS");
    }

    /** 用 root 授予悬浮窗权限 */
    public static String grantOverlay(String pkg) {
        return exec("appops set " + pkg + " SYSTEM_ALERT_WINDOW allow");
    }

    /** 用 root 授予定位权限 */
    public static String grantLocation(String pkg) {
        return exec("pm grant " + pkg + " android.permission.ACCESS_FINE_LOCATION");
    }
}
