package com.dsh.mokr.data;

import android.content.Context;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Settings.Global 是唯一一条 MIUI 不拦的跨进程通道：
 *  - 写需要 WRITE_SECURE_SETTINGS（一次 adb 授权）
 *  - 读不需要任何权限（hook 侧直接用）
 *
 * 推三个键：
 *   mokr_config      参数  speed=..;cadence=..;stride=..;anti=..
 *   mokr_origin      起点  "lat,lon"
 *   mokr_route_b64   途经点 base64(JSON 数组，不含起点)
 */
public final class ConfigSync {

    private static final String TAG = "MokrRunCfg";
    public static final String KEY_CFG = "mokr_config";
    public static final String KEY_ORIGIN = "mokr_origin";
    public static final String KEY_ROUTE = "mokr_route_b64";
    public static final String KEY_RUN = "mokr_run";

    private ConfigSync() {}

    /** "speed=2.5;cadence=2.2;stride=1.1;anti=1" —— 刻意不带引号 */
    public static String buildLine(Context ctx) {
        return "speed=" + Store.speed(ctx)
                + ";cadence=" + Store.cadence(ctx)
                + ";stride=" + Store.stride(ctx)
                + ";anti=" + (Store.anti(ctx) ? "1" : "0");
    }

    /** returns null on success, error text otherwise */
    public static String push(Context ctx) {
        try {
            Settings.Global.putString(ctx.getContentResolver(), KEY_CFG, buildLine(ctx));

            double[] o = Store.origin(ctx);
            Settings.Global.putString(ctx.getContentResolver(), KEY_ORIGIN,
                    o == null ? "" : (o[0] + "," + o[1]));

            String route = waypointsJson(ctx);
            Settings.Global.putString(ctx.getContentResolver(), KEY_ROUTE,
                    route.isEmpty() ? ""
                            : Base64.encodeToString(route.getBytes("UTF-8"), Base64.NO_WRAP));

            Log.i(TAG, "pushed origin=" + (o == null ? "(none)" : o[0] + "," + o[1])
                    + " waypoints=" + route.length() + "B");
            return null;
        } catch (Throwable t) {
            Log.e(TAG, "push failed", t);
            return String.valueOf(t);
        }
    }

    /** 只取途经点（起点单独走 mokr_origin） */
    public static String waypointsJson(Context ctx) {
        JSONObject r = Store.activeRoute(ctx);
        if (r == null) return "";
        JSONArray pts = r.optJSONArray("points");
        return pts == null ? "" : pts.toString();
    }

    public static void setMode(Context ctx, int mode) {
        try {
            Settings.Global.putString(ctx.getContentResolver(), KEY_RUN, String.valueOf(mode));
        } catch (Throwable ignored) {
        }
    }

    public static int getMode(Context ctx) {
        try {
            String v = Settings.Global.getString(ctx.getContentResolver(), KEY_RUN);
            if (v == null) return 0;
            String t = v.trim();
            if ("1".equals(t)) return 1;
            if ("2".equals(t)) return 2;
            return 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    public static String modeName(int mode) {
        if (mode == 1) return "运行中";
        if (mode == 2) return "就绪 · 已暂停";
        return "未开始";
    }

    public static String readBack(Context ctx) {
        try {
            String cfg = Settings.Global.getString(ctx.getContentResolver(), KEY_CFG);
            String origin = Settings.Global.getString(ctx.getContentResolver(), KEY_ORIGIN);
            String route = Settings.Global.getString(ctx.getContentResolver(), KEY_ROUTE);
            return "参数: " + (cfg == null ? "(空)" : cfg)
                    + "\n起点: " + (origin == null || origin.isEmpty() ? "(空)" : origin)
                    + "\n途经点: " + (route == null || route.isEmpty() ? "(空)" : route.length() + " chars")
                    + "\n状态: " + modeName(getMode(ctx));
        } catch (Throwable t) {
            return "读取失败: " + t;
        }
    }

    public static boolean canWrite(Context ctx) {
        return ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }
}
