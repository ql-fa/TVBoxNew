package com.github.tvbox.osc.util;

import android.app.Activity;
import android.content.res.Resources;
import android.os.Build;
import android.util.DisplayMetrics;

import me.jessyan.autosize.AutoSize;
import me.jessyan.autosize.AutoSizeCompat;

public final class AutoSizeHelper {
    public static final float FIXED_DESIGN_WIDTH_DP = 1366f;
    private static final float NEW_ANDROID_MM_XDPI_MULTIPLIER = 2.2f;
    private static final int X_DPI_BOOST_SDK_INT = 34;
    
    // Cache for frozen DisplayMetrics state to ensure consistent sizing across page transitions
    private static DisplayMetricState frozenMetrics = null;

    private AutoSizeHelper() {
    }

    public static void applyFixedWidth(Activity activity) {
        AutoSize.autoConvertDensity(activity, FIXED_DESIGN_WIDTH_DP, true);
        freezeDisplayMetrics(activity.getResources());
    }

    public static void applyFixedWidth(Resources resources) {
        AutoSizeCompat.autoConvertDensity(resources, FIXED_DESIGN_WIDTH_DP, true);
        freezeDisplayMetrics(resources);
    }

    public static float getFixedDesignWidthDp() {
        return FIXED_DESIGN_WIDTH_DP;
    }

    /**
     * Freeze all DisplayMetrics on first call, then restore frozen values on subsequent calls.
     * This ensures consistent sizing across page transitions, source switches, and configuration changes.
     */
    private static void freezeDisplayMetrics(Resources resources) {
        if (Build.VERSION.SDK_INT < X_DPI_BOOST_SDK_INT) {
            return;
        }
        
        DisplayMetrics displayMetrics = resources.getDisplayMetrics();
        
        if (frozenMetrics == null) {
            // First call: save the current metrics state
            frozenMetrics = new DisplayMetricState(displayMetrics);
            // Apply xdpi boost on first call only
            if (displayMetrics.xdpi <= 30) {
                displayMetrics.xdpi = displayMetrics.xdpi * NEW_ANDROID_MM_XDPI_MULTIPLIER;
                frozenMetrics.xdpi = displayMetrics.xdpi;  // Update frozen value after boost
            }
            LOG.i("autosize freeze: xdpi=" + displayMetrics.xdpi 
                    + " density=" + displayMetrics.density 
                    + " densityDpi=" + displayMetrics.densityDpi 
                    + " scaledDensity=" + displayMetrics.scaledDensity 
                    + " on SDK=" + Build.VERSION.SDK_INT);
        } else {
            // Subsequent calls: restore frozen state to prevent any metric drift
            frozenMetrics.applyTo(displayMetrics);
            LOG.i("autosize restore: xdpi=" + displayMetrics.xdpi 
                    + " density=" + displayMetrics.density 
                    + " densityDpi=" + displayMetrics.densityDpi 
                    + " scaledDensity=" + displayMetrics.scaledDensity);
        }
    }
    
    /**
     * Internal class to cache DisplayMetrics state for restoration across lifecycle events.
     */
    private static class DisplayMetricState {
        float density;
        int densityDpi;
        float scaledDensity;
        float xdpi;
        float ydpi;
        
        DisplayMetricState(DisplayMetrics metrics) {
            this.density = metrics.density;
            this.densityDpi = metrics.densityDpi;
            this.scaledDensity = metrics.scaledDensity;
            this.xdpi = metrics.xdpi;
            this.ydpi = metrics.ydpi;
        }
        
        void applyTo(DisplayMetrics metrics) {
            metrics.density = this.density;
            metrics.densityDpi = this.densityDpi;
            metrics.scaledDensity = this.scaledDensity;
            metrics.xdpi = this.xdpi;
            metrics.ydpi = this.ydpi;
        }
    }
}