package com.tunlian.app;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * APP 自动检测更新
 * 版本信息由服务器 version.json 提供：
 * {"versionCode":2,"versionName":"1.0.1","force":false,
 *  "url":"https://22.heitun.link/tunlian.apk","note":"更新说明"}
 */
public class UpdateManager {

    private static final String TAG = "TunLianUpdate";
    private static final String CHECK_URL = "https://22.heitun.link/version.json";
    private static final String APK_NAME = "tunlian-update.apk";

    private final MainActivity activity;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private long downloadId = -1;
    private File pendingApk;
    private BroadcastReceiver downloadReceiver;
    private boolean dialogShowing = false;

    UpdateManager(MainActivity activity) {
        this.activity = activity;
    }

    void check() {
        executor.execute(this::fetchAndCompare);
    }

    private void fetchAndCompare() {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(CHECK_URL).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "TunLianApp/" + BuildConfig.VERSION_NAME);
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) return;

            InputStream is = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();

            JSONObject j = new JSONObject(bos.toString("UTF-8"));
            int latest = j.optInt("versionCode", -1);
            if (latest <= BuildConfig.VERSION_CODE) return;

            String name = j.optString("versionName", "新版本");
            String note = j.optString("note", "");
            boolean force = j.optBoolean("force", false);
            String url = j.optString("url", "");
            if (url.isEmpty()) return;

            activity.runOnUiThread(() -> showDialog(name, note, force, url));
        } catch (Exception e) {
            Log.w(TAG, "检查更新失败", e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void showDialog(String name, String note, boolean force, String url) {
        if (activity.isFinishing() || activity.isDestroyed() || dialogShowing) return;
        dialogShowing = true;
        String msg = (note == null || note.isEmpty())
                ? "当前版本 v" + BuildConfig.VERSION_NAME + "，建议升级到 " + name
                : note;

        AlertDialog.Builder b = new AlertDialog.Builder(activity)
                .setTitle("发现新版本 " + name)
                .setMessage(msg)
                .setCancelable(!force)
                .setPositiveButton("立即升级", (d, w) -> startDownload(url));

        if (force) {
            b.setNegativeButton("退出", (d, w) -> activity.finish());
        } else {
            b.setNegativeButton("稍后", (d, w) -> dialogShowing = false);
        }
        b.setOnDismissListener(d -> dialogShowing = false);
        b.show();
    }

    private void startDownload(String url) {
        try {
            File dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null || (!dir.exists() && !dir.mkdirs())) dir = activity.getCacheDir();
            File apk = new File(dir, APK_NAME);
            if (apk.exists()) apk.delete();
            pendingApk = apk;

            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.setTitle("豚链更新");
            req.setDescription("正在下载新版本安装包");
            req.setMimeType("application/vnd.android.package-archive");
            req.setDestinationUri(Uri.fromFile(apk));
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);

            DownloadManager dm = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) {
                Toast.makeText(activity, "无法下载更新", Toast.LENGTH_SHORT).show();
                return;
            }
            downloadId = dm.enqueue(req);
            registerReceiver();
            Toast.makeText(activity, "开始下载更新，下载完成后会提示安装", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "下载更新失败", e);
            Toast.makeText(activity, "下载更新失败，请到下载页手动安装", Toast.LENGTH_LONG).show();
        }
    }

    private void registerReceiver() {
        if (downloadReceiver != null) return;
        downloadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id != downloadId) return;
                try {
                    activity.unregisterReceiver(this);
                } catch (Exception ignored) {
                }
                downloadReceiver = null;
                installPending();
            }
        };
        IntentFilter f = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        ContextCompat.registerReceiver(activity, downloadReceiver, f, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    /** 下载完成、或从“允许安装未知应用”设置页返回时调用 */
    void installPending() {
        if (pendingApk == null || !pendingApk.exists()) return;

        if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(activity, "请先允许“安装未知应用”权限", Toast.LENGTH_LONG).show();
            try {
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName())));
            } catch (Exception e) {
                Log.w(TAG, "无法打开安装权限设置页");
            }
            return;
        }

        try {
            Uri uri = FileProvider.getUriForFile(activity,
                    activity.getPackageName() + ".fileprovider", pendingApk);
            Intent it = new Intent(Intent.ACTION_VIEW);
            it.setDataAndType(uri, "application/vnd.android.package-archive");
            it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(it);
            pendingApk = null;
        } catch (Exception e) {
            Log.e(TAG, "打不开安装界面", e);
            Toast.makeText(activity, "无法安装，请到下载页手动安装", Toast.LENGTH_LONG).show();
        }
    }

    void destroy() {
        if (downloadReceiver != null) {
            try {
                activity.unregisterReceiver(downloadReceiver);
            } catch (Exception ignored) {
            }
            downloadReceiver = null;
        }
        executor.shutdownNow();
    }
}
