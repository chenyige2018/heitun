package com.tunlian.app;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
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
    /* 【2026-10-19】更新地址/通知标题/下载文件名改为按品牌取（res/values/strings.xml），
     * 豚链AI 查 version.json，贝贝快聊查 beibei_version.json，互不干扰 */

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
        executor.execute(() -> fetchAndCompare(false));
    }

    /** 网页/菜单里手动点的：不管有没有新版本都给明确反馈 */
    void checkManual() {
        executor.execute(() -> fetchAndCompare(true));
    }

    /** 从后台切回前台时用：2 小时内不重复打扰 */
    void checkIfNeeded() {
        SharedPreferences sp = activity.getSharedPreferences("tunlian_update", Context.MODE_PRIVATE);
        long last = sp.getLong("last_check", 0);
        if (System.currentTimeMillis() - last < 2 * 60 * 60 * 1000L) return;
        check();
    }

    /** 拉版本信息；失败自动重试一次（弱网/运营商缓存经常第一次拿不到） */
    private JSONObject fetchVersion() {
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpURLConnection conn = null;
            try {
                String checkUrl = activity.getString(R.string.update_check_url);
                String url = checkUrl + (checkUrl.contains("?") ? "&" : "?") + "t=" + System.currentTimeMillis();
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setInstanceFollowRedirects(true);
                conn.setUseCaches(false);
                conn.setRequestProperty("Cache-Control", "no-cache, no-store, max-age=0");
                conn.setRequestProperty("Pragma", "no-cache");
                conn.setRequestProperty("User-Agent", activity.getString(R.string.ua_tag) + "/" + BuildConfig.VERSION_NAME);
                if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) continue;

                InputStream is = conn.getInputStream();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
                is.close();

                JSONObject j = new JSONObject(bos.toString("UTF-8"));
                if (j.optInt("versionCode", -1) > 0) return j;
            } catch (Exception e) {
                Log.w(TAG, "检查更新失败(第" + (attempt + 1) + "次)", e);
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        return null;
    }

    private void fetchAndCompare(boolean manual) {
        JSONObject j = fetchVersion();
        activity.runOnUiThread(() -> {
            activity.getSharedPreferences("tunlian_update", Context.MODE_PRIVATE)
                    .edit().putLong("last_check", System.currentTimeMillis()).apply();
        });
        if (j == null) {
            if (manual) {
                activity.runOnUiThread(() -> Toast.makeText(activity,
                        "没查到版本信息，请检查网络后重试", Toast.LENGTH_SHORT).show());
            }
            return;
        }

        int latest = j.optInt("versionCode", -1);
        String name = j.optString("versionName", "新版本");
        String note = j.optString("note", "");
        boolean force = j.optBoolean("force", false);
        String url = j.optString("url", "");

        if (latest <= BuildConfig.VERSION_CODE || url.isEmpty()) {
            if (manual) {
                activity.runOnUiThread(() -> Toast.makeText(activity,
                        "当前已是最新版本 v" + BuildConfig.VERSION_NAME, Toast.LENGTH_SHORT).show());
            }
            return;
        }
        activity.runOnUiThread(() -> showDialog(name, note, force, url));
    }

    private void showDialog(String name, String note, boolean force, String url) {
        if (activity.isFinishing() || activity.isDestroyed() || dialogShowing) return;
        dialogShowing = true;
        String msg = "当前版本 v" + BuildConfig.VERSION_NAME + "（" + BuildConfig.VERSION_CODE + "）"
                + "　→　新版 v" + name;
        if (note != null && !note.isEmpty()) msg += "\n\n" + note;
        msg += "\n\n点「立即升级」自动下载并安装，不用卸载旧版本。";

        AlertDialog.Builder b = new AlertDialog.Builder(activity)
                .setTitle("发现新版本 " + name)
                .setMessage(msg)
                .setCancelable(!force)
                .setPositiveButton("立即升级", (d, w) -> downloadWithProgress(url));   /* 【2026-10-24】改走 APP 内进度条下载，不再用静默的系统下载器 */

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
            File apk = new File(dir, activity.getString(R.string.update_apk_name));
            if (apk.exists()) apk.delete();
            pendingApk = apk;

            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.setTitle(activity.getString(R.string.update_title));
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

    /* ============ 【2026-10-21】APP 内带进度条下载（网页点下载直接弹进度框，不用下拉通知栏） ============ */
    private androidx.appcompat.app.AlertDialog progressDlg;
    private android.widget.TextView progressText;
    private android.widget.ProgressBar progressBar;
    private volatile boolean progressCancelled = false;

    /** 网页调用 / 升级弹窗「立即升级」共用：window.TunLianApp.downloadApk(url) */
    void downloadWithProgress(final String url) {
        if (url == null || url.trim().isEmpty()) return;
        progressCancelled = false;
        activity.runOnUiThread(this::showProgressDialog);
        executor.execute(() -> {
            InputStream is = null;
            java.io.FileOutputStream fos = null;
            File apk = null;
            try {
                File dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (dir == null || (!dir.exists() && !dir.mkdirs())) dir = activity.getCacheDir();
                apk = new File(dir, "update_progress.apk");
                if (apk.exists()) apk.delete();

                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(30000);
                conn.setInstanceFollowRedirects(true);
                conn.setUseCaches(false);
                conn.setRequestProperty("User-Agent", activity.getString(R.string.ua_tag) + "/" + BuildConfig.VERSION_NAME);
                if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) throw new Exception("HTTP " + conn.getResponseCode());
                long total = conn.getContentLength();
                is = conn.getInputStream();
                fos = new java.io.FileOutputStream(apk);
                byte[] buf = new byte[8192];
                long done = 0;
                int n, lastPct = -1;
                while ((n = is.read(buf)) > 0) {
                    if (progressCancelled) break;
                    fos.write(buf, 0, n);
                    done += n;
                    if (total > 0) {
                        int pct = (int) (done * 100 / total);
                        if (pct != lastPct) {
                            lastPct = pct;
                            final int p = pct;
                            final long d = done, t = total;
                            activity.runOnUiThread(() -> updateProgressDialog(p, d, t));
                        }
                    }
                }
                fos.flush();
                final File fin = (progressCancelled || apk.length() <= 0) ? null : apk;
                activity.runOnUiThread(() -> {
                    dismissProgressDialog();
                    if (fin != null) {
                        pendingApk = fin;
                        installPending();   /* 下载完直接弹安装 */
                    } else if (!progressCancelled) {
                        Toast.makeText(activity, "下载未完成，请重试", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "进度下载失败", e);
                if (apk != null && apk.exists() && progressCancelled) apk.delete();
                activity.runOnUiThread(() -> {
                    dismissProgressDialog();
                    Toast.makeText(activity, "下载失败，请检查网络后重试", Toast.LENGTH_SHORT).show();
                });
            } finally {
                try { if (is != null) is.close(); } catch (Exception ignored) {}
                try { if (fos != null) fos.close(); } catch (Exception ignored) {}
            }
        });
    }

    private void showProgressDialog() {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        dismissProgressDialog();
        android.widget.LinearLayout box = new android.widget.LinearLayout(activity);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (20 * activity.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);
        progressText = new android.widget.TextView(activity);
        progressText.setText("正在连接服务器…");
        progressText.setTextSize(15);
        progressBar = new android.widget.ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        box.addView(progressText);
        box.addView(progressBar);
        progressDialog = new androidx.appcompat.app.AlertDialog.Builder(activity)
                .setTitle("正在下载安装包")
                .setView(box)
                .setCancelable(false)
                .setNegativeButton("取消", (d, w) -> progressCancelled = true)
                .create();
        progressDialog.show();
    }

    private void updateProgressDialog(int pct, long done, long total) {
        if (progressBar == null) return;
        progressBar.setProgress(pct);
        long dMb = done / 1048576, tMb = total / 1048576;
        progressText.setText("已下载 " + dMb + " MB / " + tMb + " MB（" + pct + "%）");
    }

    private void dismissProgressDialog() {
        if (progressDlg != null) {
            try { progressDlg.dismiss(); } catch (Exception ignored) {}
            progressDlg = null;
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
