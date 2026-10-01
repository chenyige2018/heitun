package com.tunlian.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaScannerConnection;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.SslErrorHandler;
import android.net.http.SslError;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * 豚链 APP —— WebView 壳
 * 站点地址在 res/values/strings.xml 的 site_url 修改
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "TunLianApp";
    private static final int REQ_NOTIFICATION = 1001;
    private static final int REQ_CAMERA = 1002;
    private static final int REQ_WRITE = 1003;

    private WebView webView;
    private ProgressBar progressBar;
    private View errorView;
    private UpdateManager updateManager;

    private ValueCallback<Uri[]> filePathCallback;
    private Uri cameraUri;
    private long lastBackTime = 0;

    private final ActivityResultLauncher<Intent> fileChooserLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (filePathCallback == null) return;
                Uri[] results = null;
                if (result.getResultCode() == Activity.RESULT_OK) {
                    Intent data = result.getData();
                    if (data != null) {
                        ClipData clip = data.getClipData();
                        if (clip != null && clip.getItemCount() > 0) {
                            results = new Uri[clip.getItemCount()];
                            for (int i = 0; i < clip.getItemCount(); i++) {
                                results[i] = clip.getItemAt(i).getUri();
                            }
                        } else if (data.getDataString() != null) {
                            results = new Uri[]{Uri.parse(data.getDataString())};
                        }
                    }
                    if (results == null && cameraUri != null) {
                        results = new Uri[]{cameraUri};
                    }
                }
                filePathCallback.onReceiveValue(results);
                filePathCallback = null;
                cameraUri = null;
            });

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);
        progressBar = findViewById(R.id.progressBar);
        errorView = findViewById(R.id.errorView);
        Button btnRetry = findViewById(R.id.btnRetry);

        btnRetry.setOnClickListener(v -> loadSite());
        askNotificationPermission();
        askCameraPermission();
        // Android 9 及以下保存图片到公共相册需要存储权限
        if (Build.VERSION.SDK_INT < 29 &&
                checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_WRITE);
        }
        setupWebView();
        loadSite();
        setupBackKey();

        // 每次启动检测新版本，有新包就弹窗让用户一键升级
        updateManager = new UpdateManager(this);
        updateManager.check();
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATION);
        }
    }

    private void askCameraPermission() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, REQ_CAMERA);
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        settings.setUserAgentString(settings.getUserAgentString() + " TunLianApp/" + BuildConfig.VERSION_NAME);

        // 网页通过 window.TunLianApp 调用：转发分享到微信/QQ/系统面板、保存图片到相册
        webView.addJavascriptInterface(new JsBridge(), "TunLianApp");

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                if (scheme == null) return false;

                // 非 http/https（微信、支付宝、电话、邮件、应用市场等）交给系统
                if (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")) {
                    return openExternal(uri);
                }
                // 站外 http/https 用系统浏览器打开，避免被第三方页面劫持壳
                if (!isMyHost(uri.getHost())) {
                    return openInBrowser(uri);
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                errorView.setVisibility(View.GONE);
                webView.setVisibility(View.VISIBLE);
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                if (!isNetworkAvailable()) showErrorPage();
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                // 证书异常一律拒绝，避免中间人风险
                handler.cancel();
                showErrorPage();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setVisibility(newProgress < 100 ? View.VISIBLE : View.GONE);
                progressBar.setProgress(newProgress);
            }

            // 网页扫码 / 语音 需要摄像头与麦克风，这里放行
            @Override
            public void onPermissionRequest(final android.webkit.PermissionRequest request) {
                runOnUiThread(() -> {
                    String[] res = request.getResources();
                    boolean granted = true;
                    for (String r : res) {
                        if (android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)
                                && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                            granted = false;
                        }
                        if (android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)
                                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                            granted = false;
                        }
                    }
                    if (granted) {
                        request.grant(res);
                    } else {
                        askCameraPermission();
                        request.deny();
                        Toast.makeText(MainActivity.this, "请允许摄像头权限后再扫码", Toast.LENGTH_SHORT).show();
                    }
                });
            }

            // 支持网页里的图片/文件上传（相册、文件、拍照）
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams fileChooserParams) {
                filePathCallback = callback;
                try {
                    Intent gallery = new Intent(Intent.ACTION_GET_CONTENT);
                    gallery.addCategory(Intent.CATEGORY_OPENABLE);
                    gallery.setType("*/*");
                    String[] accept = fileChooserParams == null ? null : fileChooserParams.getAcceptTypes();
                    if (accept != null && accept.length > 0 && accept[0] != null && !accept[0].trim().isEmpty()) {
                        gallery.putExtra(Intent.EXTRA_MIME_TYPES, accept);
                    }
                    if (fileChooserParams != null &&
                            fileChooserParams.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                        gallery.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    }

                    Intent chooser = new Intent(Intent.ACTION_CHOOSER);
                    chooser.putExtra(Intent.EXTRA_INTENT, gallery);
                    chooser.putExtra(Intent.EXTRA_TITLE, "选择文件");

                    // 追加“拍照”入口
                    Intent camera = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                    if (camera.resolveActivity(getPackageManager()) != null) {
                        File photo = new File(getCacheDir(), "photo_" + System.currentTimeMillis() + ".jpg");
                        cameraUri = FileProvider.getUriForFile(MainActivity.this,
                                getPackageName() + ".fileprovider", photo);
                        camera.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
                        camera.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        camera.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                        chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{camera});
                    }

                    fileChooserLauncher.launch(chooser);
                    return true;
                } catch (Exception e) {
                    Log.e(TAG, "file chooser error", e);
                    filePathCallback = null;
                    Toast.makeText(MainActivity.this, "无法打开文件选择器", Toast.LENGTH_SHORT).show();
                    return false;
                }
            }
        });

        // 网页下载（APK、图片、文件等）交给系统下载管理器
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            if (url == null) return;
            if (url.startsWith("blob:") || url.startsWith("data:")) {
                Toast.makeText(this, "该下载请在浏览器中打开", Toast.LENGTH_LONG).show();
                openInBrowser(Uri.parse(getString(R.string.site_url)));
                return;
            }
            try {
                String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
                DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
                req.setMimeType(mimeType);
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
                req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                req.allowScanningByMediaScanner();
                String cookie = CookieManager.getInstance().getCookie(url);
                if (cookie != null) req.addRequestHeader("Cookie", cookie);
                req.addRequestHeader("User-Agent", webView.getSettings().getUserAgentString());
                DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                if (dm != null) {
                    dm.enqueue(req);
                    Toast.makeText(this, "开始下载：" + fileName, Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Log.e(TAG, "download error", e);
                Toast.makeText(this, "下载失败，请在浏览器中打开", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void loadSite() {
        errorView.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        webView.loadUrl(getString(R.string.site_url));
    }

    private void showErrorPage() {
        webView.setVisibility(View.GONE);
        errorView.setVisibility(View.VISIBLE);
    }

    private boolean isMyHost(String host) {
        if (host == null) return false;
        return host.equalsIgnoreCase("22.heitun.link") || host.endsWith(".heitun.link");
    }

    private boolean openExternal(Uri uri) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            startActivity(intent);
        } catch (Exception e) {
            Log.w(TAG, "cannot open external: " + uri);
        }
        return true;
    }

    private boolean openInBrowser(Uri uri) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开链接", Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        NetworkInfo info = cm.getActiveNetworkInfo();
        return info != null && info.isConnected();
    }

    private void setupBackKey() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack();
                    return;
                }
                long now = System.currentTimeMillis();
                if (now - lastBackTime > 2000) {
                    lastBackTime = now;
                    Toast.makeText(MainActivity.this, "再按一次退出", Toast.LENGTH_SHORT).show();
                } else {
                    finish();
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从“允许安装未知应用”设置页返回后，继续完成安装
        if (updateManager != null) {
            updateManager.installPending();
        }
    }

    /** 网页 JS 桥：转发分享、保存图片 */
    private class JsBridge {

        /** 分享文本/链接，弹出系统分享面板（微信、QQ、本 APP 均可选择） */
        @JavascriptInterface
        public void shareText(final String text) {
            runOnUiThread(() -> {
                try {
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("text/plain");
                    send.putExtra(Intent.EXTRA_TEXT, text == null ? "" : text);
                    startActivity(Intent.createChooser(send, "分享到"));
                } catch (Exception e) {
                    Log.e(TAG, "shareText error", e);
                }
            });
        }

        /* ===== 【2026-10-01】定向唤起：点「微信」「QQ」直接拉起对方选好友界面，不再弹系统面板 ===== */

        /** 直接唤起微信发给好友 */
        @JavascriptInterface
        public void shareToWeChat(final String text) {
            shareToPackage("com.tencent.mm", "微信", text);
        }

        /** 直接唤起QQ发给好友 */
        @JavascriptInterface
        public void shareToQQ(final String text) {
            shareToPackage("com.tencent.mobileqq", "QQ", text);
        }

        /**
         * 朋友圈：微信未接入开放平台SDK前，系统无法自动往朋友圈填内容（微信官方限制）。
         * 做法是：文案已由网页复制好，这里直接打开微信，用户手动进朋友圈粘贴发布。
         * 接入微信开放SDK（需要开放平台 AppID）后可升级为全自动。
         */
        @JavascriptInterface
        public void shareToMoments(final String text) {
            runOnUiThread(() -> {
                try {
                    Intent i = getPackageManager().getLaunchIntentForPackage("com.tencent.mm");
                    if (i == null) throw new IllegalStateException("wechat not installed");
                    startActivity(i);
                    Toast.makeText(MainActivity.this, "邀请文案已复制：进微信→发现→朋友圈→粘贴发布", Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "未检测到微信，请先安装", Toast.LENGTH_SHORT).show();
                }
            });
        }

        /** 定向分享文本到指定应用；没装则退回系统分享面板 */
        private void shareToPackage(final String pkg, final String appName, final String text) {
            runOnUiThread(() -> {
                try {
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("text/plain");
                    send.putExtra(Intent.EXTRA_TEXT, text == null ? "" : text);
                    send.setPackage(pkg);
                    startActivity(send);
                } catch (Exception e) {
                    Log.w(TAG, "share to " + pkg + " failed", e);
                    Toast.makeText(MainActivity.this, "未检测到" + appName + "，改用系统分享", Toast.LENGTH_SHORT).show();
                    try {
                        Intent send2 = new Intent(Intent.ACTION_SEND);
                        send2.setType("text/plain");
                        send2.putExtra(Intent.EXTRA_TEXT, text == null ? "" : text);
                        startActivity(Intent.createChooser(send2, "分享到"));
                    } catch (Exception e2) {
                        Log.e(TAG, "fallback share error", e2);
                    }
                }
            });
        }

        /** 分享图片（data:image/png;base64,...），可附带文字 */
        @JavascriptInterface
        public void shareImage(final String dataUrl, final String text) {
            runOnUiThread(() -> {
                Uri uri = dataUrlToCacheFile(dataUrl);
                if (uri == null) {
                    Toast.makeText(MainActivity.this, "图片数据无效，无法转发", Toast.LENGTH_SHORT).show();
                    return;
                }
                try {
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("image/png");
                    if (text != null && !text.isEmpty()) {
                        send.putExtra(Intent.EXTRA_TEXT, text);
                    }
                    send.putExtra(Intent.EXTRA_STREAM, uri);
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                    Intent chooser = Intent.createChooser(send, "分享到");
                    chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    chooser.setClipData(ClipData.newRawUri("share", uri));
                    startActivity(chooser);
                } catch (Exception e) {
                    Log.e(TAG, "shareImage error", e);
                    Toast.makeText(MainActivity.this, "转发失败", Toast.LENGTH_SHORT).show();
                }
            });
        }

        /** 保存图片到相册 Pictures/TunLian 目录 */
        @JavascriptInterface
        public void saveImage(final String dataUrl) {
            runOnUiThread(() -> {
                byte[] bytes = decodeDataUrl(dataUrl);
                if (bytes == null) {
                    Toast.makeText(MainActivity.this, "图片数据无效", Toast.LENGTH_SHORT).show();
                    return;
                }
                try {
                    String name = "TunLian_" + System.currentTimeMillis() + ".png";
                    if (Build.VERSION.SDK_INT >= 29) {
                        ContentValues v = new ContentValues();
                        v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                        v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                        v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/TunLian");
                        Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                        try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                            os.write(bytes);
                        }
                        Toast.makeText(MainActivity.this, "已保存到相册 TunLian 目录", Toast.LENGTH_LONG).show();
                    } else {
                        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                            Toast.makeText(MainActivity.this, "请授权存储权限后再保存", Toast.LENGTH_SHORT).show();
                            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_WRITE);
                            return;
                        }
                        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "TunLian");
                        if (!dir.exists()) dir.mkdirs();
                        File f = new File(dir, name);
                        try (OutputStream os = new FileOutputStream(f)) {
                            os.write(bytes);
                        }
                        MediaScannerConnection.scanFile(MainActivity.this,
                                new String[]{f.getAbsolutePath()}, new String[]{"image/png"}, null);
                        Toast.makeText(MainActivity.this, "已保存到相册 Pictures/TunLian", Toast.LENGTH_LONG).show();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "saveImage error", e);
                    Toast.makeText(MainActivity.this, "保存失败", Toast.LENGTH_SHORT).show();
                }
            });
        }

        private byte[] decodeDataUrl(String dataUrl) {
            try {
                if (dataUrl == null) return null;
                int idx = dataUrl.indexOf("base64,");
                if (idx < 0) return null;
                return Base64.decode(dataUrl.substring(idx + 7), Base64.DEFAULT);
            } catch (Exception e) {
                return null;
            }
        }

        private Uri dataUrlToCacheFile(String dataUrl) {
            try {
                byte[] bytes = decodeDataUrl(dataUrl);
                if (bytes == null) return null;
                File f = new File(getCacheDir(), "share_" + System.currentTimeMillis() + ".png");
                try (OutputStream os = new FileOutputStream(f)) {
                    os.write(bytes);
                }
                return FileProvider.getUriForFile(MainActivity.this,
                        getPackageName() + ".fileprovider", f);
            } catch (Exception e) {
                return null;
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (updateManager != null) {
            updateManager.destroy();
        }
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
