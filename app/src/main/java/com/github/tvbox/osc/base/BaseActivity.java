package com.github.tvbox.osc.base;

import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.PermissionChecker;

import com.github.tvbox.osc.callback.EmptyCallback;
import com.github.tvbox.osc.callback.LoadingCallback;
import com.github.tvbox.osc.util.AutoSizeHelper;
import com.github.tvbox.osc.util.AppManager;
import com.github.tvbox.osc.util.LOG;
import com.kingja.loadsir.callback.Callback;
import com.kingja.loadsir.core.LoadService;
import com.kingja.loadsir.core.LoadSir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

import me.jessyan.autosize.internal.CustomAdapt;

/**
 * @author pj567
 * @date :2020/12/17
 * @description:
 */
public abstract class BaseActivity extends AppCompatActivity implements CustomAdapt {
    protected Context mContext;
    private LoadService mLoadService;
    private String lastResourcesMetricsSignature;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        logMetrics("activity.onCreate.before", super.getResources());
        AutoSizeHelper.applyFixedWidth(this);
        logMetrics("activity.onCreate.after", super.getResources());
        setContentView(getLayoutResID());
        mContext = this;
        AppManager.getInstance().addActivity(this);
        init();
    }

    @Override
    protected void onResume() {
        super.onResume();
        logMetrics("activity.onResume.before", super.getResources());
        AutoSizeHelper.applyFixedWidth(this);
        logMetrics("activity.onResume.after", super.getResources());
        hideSysBar();
    }

    public void hideSysBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            int uiOptions = getWindow().getDecorView().getSystemUiVisibility();
            uiOptions |= View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
            uiOptions |= View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            uiOptions |= View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
            uiOptions |= View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
            uiOptions |= View.SYSTEM_UI_FLAG_FULLSCREEN;
            uiOptions |= View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            getWindow().getDecorView().setSystemUiVisibility(uiOptions);
        }
    }

    @Override
    public Resources getResources() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            logMetricsIfChanged("activity.getResources.before", super.getResources(), false);
            AutoSizeHelper.applyFixedWidth(super.getResources());
            logMetricsIfChanged("activity.getResources.after", super.getResources(), true);
        }
        return super.getResources();
    }

    public boolean hasPermission(String permission) {
        boolean has = true;
        try {
            has = PermissionChecker.checkSelfPermission(this, permission) == PermissionChecker.PERMISSION_GRANTED;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return has;
    }

    protected abstract int getLayoutResID();

    protected abstract void init();

    protected void setLoadSir(View view) {
        if (mLoadService == null) {
            mLoadService = LoadSir.getDefault().register(view, new Callback.OnReloadListener() {
                @Override
                public void onReload(View v) {
                }
            });
        }
    }

    protected void showLoading() {
        if (mLoadService != null) {
            mLoadService.showCallback(LoadingCallback.class);
        }
    }

    protected void showEmpty() {
        if (null != mLoadService) {
            mLoadService.showCallback(EmptyCallback.class);
        }
    }

    protected void showSuccess() {
        if (null != mLoadService) {
            mLoadService.showSuccess();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        AppManager.getInstance().finishActivity(this);
    }

    public void jumpActivity(Class<? extends BaseActivity> clazz) {
        Intent intent = new Intent(mContext, clazz);
        startActivity(intent);
    }

    public void jumpActivity(Class<? extends BaseActivity> clazz, Bundle bundle) {
        Intent intent = new Intent(mContext, clazz);
        intent.putExtras(bundle);
        startActivity(intent);
    }

    protected String getAssetText(String fileName) {
        StringBuilder stringBuilder = new StringBuilder();
        try {
            AssetManager assets = getAssets();
            BufferedReader bf = new BufferedReader(new InputStreamReader(assets.open(fileName)));
            String line;
            while ((line = bf.readLine()) != null) {
                stringBuilder.append(line);
            }
            return stringBuilder.toString();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return "";
    }

    @Override
    public float getSizeInDp() {
        return AutoSizeHelper.getFixedDesignWidthDp();
    }

    @Override
    public boolean isBaseOnWidth() {
        return true;
    }

    private void logMetrics(String stage, Resources resources) {
        DisplayMetrics displayMetrics = resources.getDisplayMetrics();
        LOG.i("autosize " + getClass().getSimpleName()
                + " stage=" + stage
            + ", designWidth=" + AutoSizeHelper.getFixedDesignWidthDp()
                + ", width=" + displayMetrics.widthPixels
                + ", height=" + displayMetrics.heightPixels
                + ", density=" + displayMetrics.density
                + ", densityDpi=" + displayMetrics.densityDpi
                + ", scaledDensity=" + displayMetrics.scaledDensity
                + ", xdpi=" + displayMetrics.xdpi
                + ", ydpi=" + displayMetrics.ydpi);
    }

    private void logMetricsIfChanged(String stage, Resources resources, boolean updateSignature) {
        DisplayMetrics displayMetrics = resources.getDisplayMetrics();
        String signature = displayMetrics.widthPixels
                + "|" + displayMetrics.heightPixels
                + "|" + displayMetrics.density
                + "|" + displayMetrics.densityDpi
                + "|" + displayMetrics.scaledDensity
                + "|" + displayMetrics.xdpi
                + "|" + displayMetrics.ydpi;
        if (!signature.equals(lastResourcesMetricsSignature)) {
            logMetrics(stage, resources);
            if (updateSignature) {
                lastResourcesMetricsSignature = signature;
            }
        }
    }

}