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
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    postToast(activity, "正在下载更新包...");
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
                            installApk(activity, apkFile);
                        }
                    });
                } catch (Throwable e) {
                    postToast(activity, "更新失败: " + e.getMessage());
                }
            }
        }).start();
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PackageManager pm = activity.getPackageManager();
            if (!pm.canRequestPackageInstalls()) {
                Toast.makeText(activity, "请先允许安装未知来源应用", Toast.LENGTH_LONG).show();
                Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(intent);
                return;
            }
        }

        Uri apkUri = FileProvider.getUriForFile(activity,
                BuildConfig.APPLICATION_ID + ".fileprovider", apkFile);
        Intent installIntent = new Intent(Intent.ACTION_VIEW);
        installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            activity.startActivity(installIntent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(activity, "未找到可用安装器", Toast.LENGTH_SHORT).show();
        }
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
