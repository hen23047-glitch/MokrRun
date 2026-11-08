package com.mokrun.app.data;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Looper;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * 拿一次真实位置。模块 App 没被 hook，所以拿到的是系统真实定位。
 * 先用缓存，缓存没有就主动请求一次（Android 11+ 用 getCurrentLocation）。
 */
public final class LocationHelper {

    public interface Callback {
        void onLocation(double lat, double lon);
    }

    private LocationHelper() {}

    public static boolean hasPermission(Context ctx) {
        return ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** 缓存位置，可能为 null */
    public static double[] cached(Context ctx) {
        if (!hasPermission(ctx)) {
            android.util.Log.w("MokrRunLoc", "no permission");
            return null;
        }
        try {
            LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return null;
            Location l = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (l == null) l = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (l == null) l = lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER);
            android.util.Log.i("MokrRunLoc", "cached=" + (l == null ? "null"
                    : l.getProvider() + " " + l.getLatitude() + "," + l.getLongitude()));
            return l == null ? null : new double[]{l.getLatitude(), l.getLongitude()};
        } catch (Throwable t) {
            android.util.Log.e("MokrRunLoc", "cached failed", t);
            return null;
        }
    }

    /** 缓存优先；没有就请求一次，回调在主线程 */
    public static void requestOnce(final Context ctx, final Callback cb) {
        double[] c = cached(ctx);
        if (c != null) {
            cb.onLocation(c[0], c[1]);
            return;
        }
        if (!hasPermission(ctx)) return;
        try {
            final LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return;
            final String provider = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                    ? LocationManager.NETWORK_PROVIDER : LocationManager.GPS_PROVIDER;

            if (Build.VERSION.SDK_INT >= 30) {
                Executor exec = ctx.getMainExecutor();
                lm.getCurrentLocation(provider, (CancellationSignal) null, exec,
                        new Consumer<Location>() {
                            @Override public void accept(Location l) {
                                if (l != null) cb.onLocation(l.getLatitude(), l.getLongitude());
                            }
                        });
            } else {
                lm.requestSingleUpdate(provider, new LocationListener() {
                    @Override public void onLocationChanged(Location l) {
                        if (l != null) cb.onLocation(l.getLatitude(), l.getLongitude());
                    }
                    @Override public void onStatusChanged(String p, int s, Bundle b) {}
                    @Override public void onProviderEnabled(String p) {}
                    @Override public void onProviderDisabled(String p) {}
                }, Looper.getMainLooper());
            }
        } catch (Throwable ignored) {
        }
    }
}
