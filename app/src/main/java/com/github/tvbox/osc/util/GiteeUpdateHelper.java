package com.github.tvbox.osc.util;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Toast;
import android.app.UiModeManager;
import android.content.Context;
import android.content.res.Configuration;

import androidx.core.content.FileProvider;
import androidx.appcompat.app.AlertDialog;

import com.github.tvbox.osc.BuildConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class GiteeUpdateHelper {
    private static final String OWNER = "ltrader";
    private static final String REPO = "testv";
    private static final String LATEST_RELEASE_API = "https://gitee.com/api/v5/repos/" + OWNER + "/" + REPO + "/releases/latest";
    private static final Pattern VERSION_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)+)");
    private static final Pattern SHA256_PATTERN = Pattern.compile("([a-fA-F0-9]{64})");
    private static final OkHttpClient CLIENT = new OkHttpClient();
    private static AlertDialog downloadingDialog;

    private GiteeUpdateHelper() {
    }

    public static void checkUpdate(final Activity activity, final Runnable onNoUpdate) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    ReleaseAssetPair pair = fetchLatestReleaseAssets();
                    if (pair == null) {
                        postToast(activity, "最新发布中未找到可用更新包");
                        postNoUpdate(onNoUpdate, activity);
                        return;
                    }

                    String localVersion = BuildConfig.VERSION_NAME;
                    boolean localNightly = localVersion != null
                            && localVersion.toLowerCase(Locale.ROOT).contains("nightly");
                    String remoteVersion = extractVersion(pair.apkName);
                    if (remoteVersion == null || remoteVersion.isEmpty()) {
                        remoteVersion = extractVersion(pair.tagName);
                    }
                    if (!localNightly && (remoteVersion == null || remoteVersion.isEmpty())) {
                        postToast(activity, "无法解析远端版本号");
                        postNoUpdate(onNoUpdate, activity);
                        return;
                    }

                    if (!localNightly && compareVersion(remoteVersion, localVersion) <= 0) {
                        postToast(activity, "已经是最新版本");
                        postNoUpdate(onNoUpdate, activity);
                        return;
                    }

                    final String finalRemoteVersion = (remoteVersion == null || remoteVersion.isEmpty())
                            ? "unknown"
                            : remoteVersion;
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            String message = "当前版本: " + BuildConfig.VERSION_NAME + "\n最新版本: " + finalRemoteVersion + "\n\n文件: " + pair.apkName;
                            new AlertDialog.Builder(activity)
                                    .setTitle("发现新版本")
                                    .setMessage(message)
                                    .setNegativeButton("稍后", null)
                                    .setPositiveButton("下载更新", (dialog, which) -> downloadAndInstall(activity, pair))
                                    .show();
                        }
                    });
                } catch (Throwable e) {
                    postToast(activity, "检查更新失败: " + e.getMessage());
                    postNoUpdate(onNoUpdate, activity);
                }
            }
        }).start();
    }

    private static void postNoUpdate(final Runnable onNoUpdate, Activity activity) {
        if (onNoUpdate == null) {
            return;
        }
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                onNoUpdate.run();
            }
        });
    }

    private static void postToast(final Activity activity, final String msg) {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private static ReleaseAssetPair fetchLatestReleaseAssets() throws Exception {
        Request request = new Request.Builder().url(LATEST_RELEASE_API).build();
        try (Response response = CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("HTTP " + response.code());
            }
            JSONObject json = new JSONObject(response.body().string());
            String tagName = json.optString("tag_name", "");
            JSONArray assets = json.optJSONArray("assets");
            if (assets == null || assets.length() == 0) {
                return null;
            }

            String bestApkName = null;
            String bestApkUrl = null;
            String bestApkVersion = null;
            String bestShaUrl = null;

            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.optJSONObject(i);
                if (asset == null) {
                    continue;
                }
                String name = asset.optString("name", "");
                String url = asset.optString("browser_download_url", "");
                if (url.isEmpty()) {
                    url = asset.optString("url", "");
                }
                if (!name.toLowerCase(Locale.ROOT).endsWith(".apk") || url.isEmpty()) {
                    continue;
                }
                String version = extractVersion(name);
                if (version == null) {
                    continue;
                }
                if (bestApkVersion == null || compareVersion(version, bestApkVersion) > 0) {
                    bestApkVersion = version;
                    bestApkName = name;
                    bestApkUrl = url;
                }
            }

            if (bestApkName == null || bestApkUrl == null) {
                return null;
            }

            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.optJSONObject(i);
                if (asset == null) {
                    continue;
                }
                String name = asset.optString("name", "");
                if (!name.toLowerCase(Locale.ROOT).endsWith(".sha256")) {
                    continue;
                }
                String version = extractVersion(name);
                if (version != null && version.equals(bestApkVersion)) {
                    bestShaUrl = asset.optString("browser_download_url", "");
                    if (bestShaUrl.isEmpty()) {
                        bestShaUrl = asset.optString("url", "");
                    }
                    break;
                }
            }

            return new ReleaseAssetPair(bestApkName, bestApkUrl, bestShaUrl, tagName);
        }
    }

    private static void downloadAndInstall(final Activity activity, final ReleaseAssetPair pair) {
        showDownloadingDialog(activity);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    File dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    if (dir == null) {
                        throw new IOException("下载目录不可用");
                    }
                    if (!dir.exists() && !dir.mkdirs()) {
                        throw new IOException("无法创建下载目录");
                    }

                    File apkFile = new File(dir, pair.apkName);
                    downloadFile(pair.apkUrl, apkFile);

                    if (pair.sha256Url != null && !pair.sha256Url.isEmpty()) {
                        String remoteSha = downloadSha256(pair.sha256Url);
                        String localSha = calcSha256(apkFile);
                        if (!localSha.equalsIgnoreCase(remoteSha)) {
                            if (apkFile.exists()) {
                                //noinspection ResultOfMethodCallIgnored
                                apkFile.delete();
                            }
                            throw new IllegalStateException("SHA256 校验失败");
                        }
                    }

                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            dismissDownloadingDialog();
                            Toast.makeText(activity, "下载完成，准备安装", Toast.LENGTH_SHORT).show();
                            installApk(activity, apkFile);
                        }
                    });
                } catch (Throwable e) {
                    dismissDownloadingDialog();
                    postToast(activity, "更新失败: " + e.getMessage());
                }
            }
        }).start();
    }

    private static void showDownloadingDialog(final Activity activity) {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                dismissDownloadingDialog();
                downloadingDialog = new AlertDialog.Builder(activity)
                        .setTitle("正在下载")
                        .setMessage("正在下载更新包，请稍候...")
                        .setCancelable(false)
                        .create();
                downloadingDialog.show();
            }
        });
    }

    private static void dismissDownloadingDialog() {
        if (downloadingDialog != null && downloadingDialog.isShowing()) {
            downloadingDialog.dismiss();
        }
        downloadingDialog = null;
    }

    private static void downloadFile(String url, File output) throws Exception {
        Request request = new Request.Builder().url(url).build();
        try (Response response = CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("HTTP " + response.code());
            }
            ResponseBody body = response.body();
            try (InputStream in = body.byteStream(); FileOutputStream out = new FileOutputStream(output)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                out.flush();
            }
        }
    }

    private static String downloadSha256(String url) throws Exception {
        Request request = new Request.Builder().url(url).build();
        try (Response response = CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("HTTP " + response.code());
            }
            String text = response.body().string();
            Matcher matcher = SHA256_PATTERN.matcher(text);
            if (!matcher.find()) {
                throw new IllegalStateException("无法解析 SHA256 文件");
            }
            return matcher.group(1);
        }
    }

    private static String calcSha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = fis.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        byte[] bytes = digest.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format(Locale.US, "%02x", b));
        }
        return sb.toString();
    }

    private static void installApk(Activity activity, File apkFile) {
        // Log device information for debugging
        logDeviceInfo(activity);
        
        // Detect TV device: on TV, Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES doesn't exist
        // and canRequestPackageInstalls() may always return false. Just try to install directly.
        boolean isTv = isTvDevice(activity);
        android.util.Log.i("GiteeUpdateHelper", "Device detected as TV: " + isTv);

        if (!isTv && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PackageManager pm = activity.getPackageManager();
            if (!pm.canRequestPackageInstalls()) {
                Toast.makeText(activity, "请先允许安装未知来源应用", Toast.LENGTH_LONG).show();
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + activity.getPackageName()));
                    activity.startActivity(intent);
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(activity, "请在系统设置中允许安装未知来源应用", Toast.LENGTH_LONG).show();
                }
                return;
            }
        }

        // Try multiple installation methods to support various Android versions and devices
        boolean installSuccess = false;

        // Method 1: Try FileProvider URI (Android 7.0+, recommended)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            android.util.Log.i("GiteeUpdateHelper", "Attempting Method 1: FileProvider URI");
            installSuccess = tryInstallWithFileProvider(activity, apkFile);
            if (installSuccess) return;
        }

        // Method 2: Try file:// URI (Android 6.0 and below)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            android.util.Log.i("GiteeUpdateHelper", "Attempting Method 2: file:// URI");
            installSuccess = tryInstallWithFileUri(activity, apkFile);
            if (installSuccess) return;
        }

        // Method 3: Try alternative package installers
        if (!installSuccess) {
            android.util.Log.i("GiteeUpdateHelper", "Attempting Method 3: Alternative installers");
            installSuccess = tryInstallWithAlternativeInstallers(activity, apkFile);
            if (installSuccess) return;
        }

        // Method 4: Try pm install command (for devices with root or system app)
        if (!installSuccess) {
            android.util.Log.i("GiteeUpdateHelper", "Attempting Method 4: PM command");
            installSuccess = tryInstallWithPmCommand(activity, apkFile);
            if (installSuccess) return;
        }

        // If all methods fail, show error
        android.util.Log.e("GiteeUpdateHelper", "All installation methods failed!");
        Toast.makeText(activity, "未找到可用安装器，请尝试以下方式：\n" +
                "1.检查文件完整性\n2.手动安装APK文件\n3.检查系统权限设置", 
            Toast.LENGTH_LONG).show();
    }

    /**
     * Log device information for debugging installation issues
     */
    private static void logDeviceInfo(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            
            StringBuilder deviceInfo = new StringBuilder();
            deviceInfo.append("Device Info - ");
            deviceInfo.append("SDK:").append(Build.VERSION.SDK_INT).append(", ");
            deviceInfo.append("Release:").append(Build.VERSION.RELEASE).append(", ");
            deviceInfo.append("Device:").append(Build.DEVICE).append(", ");
            deviceInfo.append("Manufacturer:").append(Build.MANUFACTURER).append(", ");
            deviceInfo.append("Model:").append(Build.MODEL);
            
            // Check for TV features
            boolean hasTV = pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION);
            boolean hasLeanback = pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK);
            deviceInfo.append(", TV:").append(hasTV).append(", Leanback:").append(hasLeanback);
            
            android.util.Log.i("GiteeUpdateHelper", deviceInfo.toString());
        } catch (Exception e) {
            android.util.Log.w("GiteeUpdateHelper", "Failed to log device info: " + e.getMessage());
        }
    }

    /**
     * Try to install APK using FileProvider URI (for Android 7.0+)
     */
    private static boolean tryInstallWithFileProvider(Activity activity, File apkFile) {
        try {
            Uri apkUri = FileProvider.getUriForFile(activity,
                    BuildConfig.APPLICATION_ID + ".fileprovider", apkFile);
            Intent installIntent = new Intent(Intent.ACTION_VIEW);
            installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(installIntent);
            return true;
        } catch (Exception e) {
            android.util.Log.w("GiteeUpdateHelper", "FileProvider install failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Try to install APK using file:// URI (for Android 6.0 and below)
     */
    private static boolean tryInstallWithFileUri(Activity activity, File apkFile) {
        try {
            Uri apkUri = Uri.fromFile(apkFile);
            Intent installIntent = new Intent(Intent.ACTION_VIEW);
            installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(installIntent);
            return true;
        } catch (Exception e) {
            android.util.Log.w("GiteeUpdateHelper", "File URI install failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Try to install using alternative package installers
     * Supports: com.android.packageinstaller, com.google.android.packageinstaller, etc.
     * Special support for Xiaomi TV GITV and MIUI TV systems
     */
    private static boolean tryInstallWithAlternativeInstallers(Activity activity, File apkFile) {
        // Extended list including Xiaomi TV/GITV installers
        String[] installerPackages = {
            // Standard Android installers
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.vending",
            
            // Xiaomi MIUI TV and GITV installers (priority for Xiaomi boxes)
            "com.miui.packageinstaller",
            "com.miui.tv.packageinstaller",
            "com.mitv.packageinstaller",
            "com.xiaomi.tv.installer",
            "com.gitv.installer",
            "com.gitv.packageinstaller",
            
            // Other TV box installers
            "com.sec.android.app.samsungapps",
            "com.oppo.market",
            "com.huawei.android.packageinstaller",
            
            // Generic fallback installers
            "com.android.installer",
            "android.app.packageinstaller"
        };

        PackageManager pm = activity.getPackageManager();
        StringBuilder availableInstallers = new StringBuilder("Available installers: ");
        
        // Log which installers are available on this device
        for (String installerPackage : installerPackages) {
            try {
                pm.getPackageInfo(installerPackage, 0);
                availableInstallers.append("[").append(installerPackage).append("] ");
            } catch (Exception e) {
                // Package not installed, skip
            }
        }
        android.util.Log.i("GiteeUpdateHelper", availableInstallers.toString());

        for (String installerPackage : installerPackages) {
            try {
                Uri apkUri;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    apkUri = FileProvider.getUriForFile(activity,
                            BuildConfig.APPLICATION_ID + ".fileprovider", apkFile);
                } else {
                    apkUri = Uri.fromFile(apkFile);
                }

                Intent installIntent = new Intent(Intent.ACTION_VIEW);
                installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
                installIntent.setPackage(installerPackage);
                installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                }

                // Check if this installer can handle the install intent
                if (pm.resolveActivity(installIntent, 0) != null) {
                    android.util.Log.i("GiteeUpdateHelper", "Attempting to launch installer: " + installerPackage);
                    activity.startActivity(installIntent);
                    android.util.Log.i("GiteeUpdateHelper", "Install with " + installerPackage + " succeeded");
                    return true;
                } else {
                    android.util.Log.w("GiteeUpdateHelper", "Installer not available: " + installerPackage);
                }
            } catch (Exception e) {
                android.util.Log.w("GiteeUpdateHelper", "Install with " + installerPackage + " failed: " + e.getMessage());
            }
        }
        return false;
    }

    /**
     * Try to install using pm command (requires device with su or running as system app)
     * This method works particularly well for Xiaomi TV boxes and other TV devices
     */
    private static boolean tryInstallWithPmCommand(Activity activity, File apkFile) {
        if (!apkFile.exists() || !apkFile.canRead()) {
            android.util.Log.w("GiteeUpdateHelper", "APK file doesn't exist or can't be read: " + apkFile.getAbsolutePath());
            return false;
        }

        try {
            String apkPath = apkFile.getAbsolutePath();
            android.util.Log.i("GiteeUpdateHelper", "Trying pm install with path: " + apkPath);
            
            // Try multiple pm install variations
            String[] commands = {
                "pm install -r " + apkPath,           // Normal install with replace
                "pm install -r -d " + apkPath,        // Allow downgrade
                "pm install -r --user 0 " + apkPath,  // Specify user
                "pm install -r -s " + apkPath         // Install on SD card if possible
            };
            
            for (String command : commands) {
                try {
                    android.util.Log.i("GiteeUpdateHelper", "Executing: " + command);
                    Process process = Runtime.getRuntime().exec(command);
                    int exitCode = process.waitFor();
                    
                    // Read output for debugging
                    java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(process.getInputStream()));
                    StringBuilder output = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append("\n");
                    }
                    reader.close();
                    
                    // Check error stream
                    java.io.BufferedReader errorReader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(process.getErrorStream()));
                    StringBuilder errorOutput = new StringBuilder();
                    while ((line = errorReader.readLine()) != null) {
                        errorOutput.append(line).append("\n");
                    }
                    errorReader.close();
                    
                    if (exitCode == 0) {
                        android.util.Log.i("GiteeUpdateHelper", "PM install succeeded with: " + command);
                        android.util.Log.i("GiteeUpdateHelper", "Output: " + output.toString());
                        Toast.makeText(activity, "安装中，请稍候...", Toast.LENGTH_SHORT).show();
                        return true;
                    } else {
                        android.util.Log.w("GiteeUpdateHelper", "PM install failed with exit code: " + exitCode);
                        android.util.Log.w("GiteeUpdateHelper", "Command: " + command);
                        android.util.Log.w("GiteeUpdateHelper", "Output: " + output.toString());
                        android.util.Log.w("GiteeUpdateHelper", "Error: " + errorOutput.toString());
                    }
                } catch (Exception e) {
                    android.util.Log.w("GiteeUpdateHelper", "Exception with command '" + command + "': " + e.getMessage());
                }
            }
        } catch (Exception e) {
            android.util.Log.w("GiteeUpdateHelper", "PM command install failed: " + e.getMessage());
        }
        return false;
    }

    private static boolean isTvDevice(Context context) {
        UiModeManager uiModeManager = (UiModeManager) context.getSystemService(Context.UI_MODE_SERVICE);
        if (uiModeManager != null
                && uiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION) {
            return true;
        }
        PackageManager pm = context.getPackageManager();
        return pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION)
                || pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK);
    }

    private static String extractVersion(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = VERSION_PATTERN.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1);
    }

    private static int compareVersion(String v1, String v2) {
        String[] a1 = v1.split("\\.");
        String[] a2 = v2.split("\\.");
        int len = Math.max(a1.length, a2.length);
        for (int i = 0; i < len; i++) {
            int n1 = i < a1.length ? parseVersionNum(a1[i]) : 0;
            int n2 = i < a2.length ? parseVersionNum(a2[i]) : 0;
            if (n1 != n2) {
                return n1 - n2;
            }
        }
        return 0;
    }

    private static int parseVersionNum(String value) {
        try {
            return Integer.parseInt(value.replaceAll("[^0-9]", ""));
        } catch (Throwable e) {
            return 0;
        }
    }

    private static final class ReleaseAssetPair {
        final String apkName;
        final String apkUrl;
        final String sha256Url;
        final String tagName;

        ReleaseAssetPair(String apkName, String apkUrl, String sha256Url, String tagName) {
            this.apkName = apkName;
            this.apkUrl = apkUrl;
            this.sha256Url = sha256Url;
            this.tagName = tagName;
        }
    }
}
