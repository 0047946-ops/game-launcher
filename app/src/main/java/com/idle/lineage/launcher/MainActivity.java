package com.idle.lineage.launcher;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MainActivity extends Activity {

    private static final String TAG = "IdleLineageApp";
    private static final String PREFS_NAME = "GamePrefs";
    private static final String KEY_CURRENT_VERSION = "current_version";

    // 🌐 雙網址伺服器
    private static final String URL_ORIGINAL_GAME = "https://shines871.github.io/idle-lineage-class/";
    private static final String URL_MODDED_GAME = "https://pp771007.github.io/idle-lineage-class/";

    // 🚀 三大 GitHub 雲端基地網址
    private static final String URL_RELEASE_JSON = "https://raw.githubusercontent.com/0047946-ops/game-launcher/main/release.json";
    private static final String URL_SAVE_HOOK = "https://raw.githubusercontent.com/0047946-ops/game-launcher/main/save_hook.js";
    private static final String URL_MASTER_ENGINE = "https://raw.githubusercontent.com/0047946-ops/game-launcher/main/scripts/main.user.js";
    private static final String GITHUB_RELEASE_API = "https://api.github.com/repos/pp771007/idle-lineage-class/releases/latest";

    private WebView webView;
    private RelativeLayout layoutLoading;
    private ProgressBar progressBar;
    private TextView tvLoadingStatus;

    private File gameDir;
    private SharedPreferences prefs;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ValueCallback<Uri[]> filePathCallback;
    private final static int FILE_CHOOSER_RESULT_CODE = 10001;
    private final static int CREATE_DOCUMENT_RESULT_CODE = 10002;

    private byte[] pendingSaveBytes = null;
    private String pendingSaveFileName = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        checkAllFilesAccessPermission();

        // 建立全螢幕畫面包含 UI 下載進度條與 WebView
        RelativeLayout rootLayout = new RelativeLayout(this);
        webView = new WebView(this);
        rootLayout.addView(webView, new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT));

        // 動態構建 Loading UI
        buildLoadingUI(rootLayout);
        setContentView(rootLayout);

        gameDir = new File(getFilesDir(), "game");
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        setupWebView();

        // 開啟非同步線程處理熱更新與遊戲載入
        executor.execute(() -> {
            loadGameInWebView();
            checkForUpdates();
        });
    }

    private void buildLoadingUI(RelativeLayout rootLayout) {
        layoutLoading = new RelativeLayout(this);
        layoutLoading.setBackgroundColor(0xFF121212);
        layoutLoading.setVisibility(View.GONE);

        tvLoadingStatus = new TextView(this);
        tvLoadingStatus.setTextColor(0xFF00FF00);
        tvLoadingStatus.setTextSize(16);
        tvLoadingStatus.setId(View.generateViewId());

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);

        RelativeLayout.LayoutParams tvParams = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT);
        tvParams.addRule(RelativeLayout.CENTER_IN_PARENT);
        layoutLoading.addView(tvLoadingStatus, tvParams);

        RelativeLayout.LayoutParams pbParams = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.WRAP_CONTENT);
        pbParams.addRule(RelativeLayout.BELOW, tvLoadingStatus.getId());
        pbParams.setMargins(50, 20, 50, 0);
        layoutLoading.addView(progressBar, pbParams);

        rootLayout.addView(layoutLoading, new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT));
    }

    private void setupWebView() {
        mainHandler.post(() -> {
            WebSettings settings = webView.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setDatabaseEnabled(true);
            settings.setAllowFileAccess(true);
            settings.setAllowContentAccess(true);
            settings.setAllowFileAccessFromFileURLs(true);
            settings.setAllowUniversalAccessFromFileURLs(true);
            settings.setJavaScriptCanOpenWindowsAutomatically(true);
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            settings.setCacheMode(WebSettings.LOAD_DEFAULT);
            settings.setMediaPlaybackRequiresUserGesture(false);

            CookieManager cookieManager = CookieManager.getInstance();
            cookieManager.setAcceptCookie(true);
            cookieManager.setAcceptThirdPartyCookies(webView, true);

            webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
                try {
                    if (url.startsWith("blob:")) {
                        triggerBlobDownload(url);
                    } else {
                        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                        request.setMimeType(mimetype);
                        request.addRequestHeader("User-Agent", userAgent);
                        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);

                        String fileName = guessFileName(contentDisposition, url);
                        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);

                        DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                        dm.enqueue(request);
                        Toast.makeText(this, "📥 已開始下載存檔：" + fileName, Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    Toast.makeText(this, "❌ 下載失敗：" + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            });

            webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");

            webView.setWebChromeClient(new WebChromeClient() {
                @Override
                public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
                                                  FileChooserParams fileChooserParams) {
                    if (MainActivity.this.filePathCallback != null) {
                        MainActivity.this.filePathCallback.onReceiveValue(null);
                    }
                    MainActivity.this.filePathCallback = filePathCallback;

                    Intent intent = fileChooserParams.createIntent();
                    try {
                        startActivityForResult(intent, FILE_CHOOSER_RESULT_CODE);
                    } catch (Exception e) {
                        MainActivity.this.filePathCallback = null;
                        return false;
                    }
                    return true;
                }
            });

            webView.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    String url = request.getUrl().toString();
                    if (url.startsWith("blob:") || url.startsWith("data:")) {
                        triggerBlobDownload(url);
                        return true;
                    }
                    return false;
                }

                @Override
                public void onPageFinished(WebView view, String url) {
                    super.onPageFinished(view, url);
                    
                    if (url != null && url.startsWith("http")) {
                        // 🚀 注入雲端基地 SaveHook 與 Master Engine 核心腳本
                        injectRemoteScript(view, URL_SAVE_HOOK);
                        injectRemoteScript(view, URL_MASTER_ENGINE);

                        // 1. 自動注入【10 大外掛模組】
                        String totalPluginJs = "(function () {" +
                                "'use strict';" +
                                "if(window.__all_plugins_loaded) return;" +
                                "window.__all_plugins_loaded = true;" +
                                "var s0 = document.createElement('script');" +
                                "s0.src = 'https://cdn.jsdelivr.net/gh/qcc781192000/idle-lineage-plugin@main/main.user.js?v=' + Date.now();" +
                                "document.body.appendChild(s0);" +
                                "const b = 'https://kid0924.github.io/idle-lineage-class/';" +
                                "const t = window.location.hostname.includes('pp771007');" +
                                "const c = ['klh_initial.js','klh_GMShop.js','klh_mobile-perf.js','klh_perf-monitor.js','klh_Backpack.js','klh_pk.js','klh_Pandora.js'].map(x => b + x);" +
                                "const n = t ? [...[b+'klh_remove-banner.js'],...c] : [...['https://pp771007.github.io/idle-lineage-class/afk-lzcache.js', 'https://pp771007.github.io/idle-lineage-class/afk-offline.js'],...c];" +
                                "function s(e, t) {" +
                                "    const node = document.createElement('div');" +
                                "    node.textContent = e;" +
                                "    node.style.cssText = 'position:fixed;top:20px;right:20px;background:' + (t ? '#2ecc71' : '#e74c3c') + ';color:white;padding:12px 24px;border-radius:8px;z-index:99999;font-family:sans-serif;box-shadow:0 4px 12px rgba(0,0,0,0.15);transition:opacity 0.5s';" +
                                "    document.body.appendChild(node);" +
                                "    setTimeout(() => {" +
                                "        node.style.opacity = '0';" +
                                "        setTimeout(() => node.remove(), 500);" +
                                "    }, 2500);" +
                                "}" +
                                "function l(e) {" +
                                "    return new Promise((resolve, reject) => {" +
                                "        const o = document.createElement('script');" +
                                "        o.src = e + '?v=' + Date.now();" +
                                "        o.onload = (() => { resolve(); });" +
                                "        o.onerror = (() => { reject(e); });" +
                                "        document.body.appendChild(o);" +
                                "    });" +
                                "}" +
                                "n.reduce((e, t) => e.then(() => l(t)), Promise.resolve())" +
                                "    .then(() => { s('🎉 【10大外掛模組 + 雲端基地】全部注入成功！', !0); })" +
                                "    .catch(r => {" +
                                "        const f = (r && typeof r === 'string') ? r.split('/').pop().split('?')[0] : '';" +
                                "        s('❌ 載入失敗' + (f ? '：' + f : '！'), !1);" +
                                "    });" +
                                "})();";
                        view.evaluateJavascript(totalPluginJs, null);

                        // 2. 自動注入【TMEngine v106.0 防斷線引擎】
                        String tmEngineJs = "(function() {" +
                                "'use strict';" +
                                "if(window.__tm_engine_loaded) return;" +
                                "window.__tm_engine_loaded = true;" +
                                "const PerformanceCore = {" +
                                "    initTuning: () => {" +
                                "        if (typeof window.requestIdleCallback !== 'undefined') {" +
                                "            window.requestIdleCallback(() => {" +
                                "                if (window.gc) window.gc();" +
                                "            }, { timeout: 500 });" +
                                "        }" +
                                "    }," +
                                "    getJitter: (base, variance) => base + Math.floor(Math.random() * variance)" +
                                "};" +
                                "PerformanceCore.initTuning();" +
                                "const originalSetInterval = window.setInterval;" +
                                "window.setInterval = function(callback, delay, ...args) {" +
                                "    const optimizedDelay = delay < 150 ? 150 : delay;" +
                                "    return originalSetInterval(callback, optimizedDelay, ...args);" +
                                "};" +
                                "const initSystem = async () => {" +
                                "    const div = document.createElement('div');" +
                                "    div.style = 'position:fixed; top:10px; left:10px; background:rgba(0,0,0,0.85); color:#0f0; padding:10px; z-index:2147483647; border-radius:8px; font-size:11px; border:1px solid #0f0; pointer-events:none;';" +
                                "    div.innerHTML = `<div style=\"font-weight:bold;\">【TMEngine v106.0】全域極致整合防斷線版</div><div>● 雲端基地：連線運行中</div>`;" +
                                "    if (document.body) document.body.appendChild(div);" +
                                "};" +
                                "initSystem();" +
                                "})();";
                        view.evaluateJavascript(tmEngineJs, null);
                    }
                }
            });
        });
    }

    private void injectRemoteScript(WebView view, String scriptUrl) {
        String js = "javascript:(function(){" +
                "var s=document.createElement('script');" +
                "s.src='" + scriptUrl + "?v=" + System.currentTimeMillis() + "';" +
                "document.head.appendChild(s);})();";
        view.evaluateJavascript(js, null);
    }

    private void loadGameInWebView() {
        mainHandler.post(() -> {
            File indexFile = new File(gameDir, "index.html");
            if (indexFile.exists()) {
                Log.d(TAG, "優先載入本地熱更新解壓遊戲包");
                webView.loadUrl("file://" + indexFile.getAbsolutePath());
            } else {
                loadNativeLauncherHtml();
            }
        });
    }

    // 🚀 熱更新機制：檢查 GitHub / 雲端基地 release.json 並下載解壓
    private void checkForUpdates() {
        try {
            Log.d(TAG, "檢查雲端基地 release.json 配置: " + URL_RELEASE_JSON);
            URL releaseUrl = new URL(URL_RELEASE_JSON);
            HttpURLConnection releaseConn = (HttpURLConnection) releaseUrl.openConnection();
            releaseConn.setConnectTimeout(3000);
            if (releaseConn.getResponseCode() == 200) {
                Log.d(TAG, "✅ 雲端基地連線正常");
            }

            Log.d(TAG, "檢查 GitHub 最新熱更新版本...");
            URL url = new URL(GITHUB_RELEASE_API);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "IdleLineageAndroidApp");
            conn.setConnectTimeout(5000);

            if (conn.getResponseCode() == 200) {
                InputStream is = conn.getInputStream();
                java.util.Scanner s = new java.util.Scanner(is).useDelimiter("\\A");
                String response = s.hasNext() ? s.next() : "";
                JSONObject json = new JSONObject(response);

                String latestVersion = json.getString("tag_name");
                String currentVersion = prefs.getString(KEY_CURRENT_VERSION, "");

                if (!latestVersion.equals(currentVersion)) {
                    showLoadingUI("發現熱更新版本 (" + latestVersion + ")，準備下載...");

                    String zipUrl = json.getString("zipball_url");
                    File downloadedZip = new File(getCacheDir(), "update.zip");

                    if (downloadFileWithProgress(zipUrl, downloadedZip)) {
                        updateProgressUI("正在解壓縮遊戲熱更新包...", -1);
                        deleteRecursive(gameDir);
                        gameDir.mkdirs();
                        unzip(downloadedZip, gameDir);
                        downloadedZip.delete();

                        prefs.edit().putString(KEY_CURRENT_VERSION, latestVersion).apply();
                        showToast("🎉 熱更新完成！正在載入最新版遊戲...");
                        loadGameInWebView();
                    } else {
                        hideLoadingUI();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "熱更新檢查失敗", e);
            hideLoadingUI();
        }
    }

    private boolean downloadFileWithProgress(String urlStr, File outputFile) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", "IdleLineageAndroidApp");
            conn.setInstanceFollowRedirects(true);

            int responseCode = conn.getResponseCode();
            while (responseCode == HttpURLConnection.HTTP_MOVED_PERM || responseCode == HttpURLConnection.HTTP_MOVED_TEMP) {
                String redirectUrl = conn.getHeaderField("Location");
                url = new URL(redirectUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestProperty("User-Agent", "IdleLineageAndroidApp");
                responseCode = conn.getResponseCode();
            }

            if (responseCode != HttpURLConnection.HTTP_OK) return false;

            int fileLength = conn.getContentLength();
            try (InputStream is = conn.getInputStream(); FileOutputStream fos = new FileOutputStream(outputFile)) {
                byte[] buffer = new byte[8192];
                long total = 0;
                int read;
                int lastProgress = -1;

                while ((read = is.read(buffer)) != -1) {
                    total += read;
                    fos.write(buffer, 0, read);

                    if (fileLength > 0) {
                        int progress = (int) (total * 100 / fileLength);
                        if (progress != lastProgress) {
                            lastProgress = progress;
                            updateProgressUI("熱更新下載中 (" + progress + "%)...", progress);
                        }
                    }
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void showLoadingUI(String statusText) {
        mainHandler.post(() -> {
            if (layoutLoading != null) layoutLoading.setVisibility(View.VISIBLE);
            if (tvLoadingStatus != null) tvLoadingStatus.setText(statusText);
            if (progressBar != null) progressBar.setIndeterminate(true);
        });
    }

    private void updateProgressUI(String statusText, int progress) {
        mainHandler.post(() -> {
            if (layoutLoading != null) layoutLoading.setVisibility(View.VISIBLE);
            if (tvLoadingStatus != null) tvLoadingStatus.setText(statusText);
            if (progressBar != null) {
                if (progress >= 0) {
                    progressBar.setIndeterminate(false);
                    progressBar.setProgress(progress);
                } else {
                    progressBar.setIndeterminate(true);
                }
            }
        });
    }

    private void hideLoadingUI() {
        mainHandler.post(() -> {
            if (layoutLoading != null) layoutLoading.setVisibility(View.GONE);
        });
    }

    private void loadNativeLauncherHtml() {
        String html = "<!DOCTYPE html><html><head><meta charset='utf-8'>" +
                "<meta name='viewport' content='width=device-width, initial-scale=1.0'>" +
                "<style>" +
                "body{background:#121212;color:#fff;font-family:-apple-system,BlinkMacSystemFont,Segoe UI,Roboto,sans-serif;padding:20px;display:flex;justify-content:center;align-items:center;min-height:90vh;margin:0;}" +
                ".card{background:#1e1e1e;border-radius:16px;padding:24px;width:100%;max-width:380px;box-shadow:0 8px 24px rgba(0,0,0,0.5);text-align:center;box-sizing:border-box;}" +
                "h2{font-size:20px;margin-bottom:4px;display:flex;align-items:center;justify-content:center;gap:8px;}" +
                ".subtitle{color:#8e8e93;font-size:13px;margin-bottom:24px;}" +
                ".label{color:#4cd964;font-size:14px;text-align:left;margin-bottom:8px;font-weight:bold;}" +
                "select{width:100%;padding:12px;background:#2c2c2e;color:#fff;border:1px solid #3a3a3c;border-radius:8px;font-size:15px;margin-bottom:24px;outline:none;}" +
                ".btn-start{width:100%;padding:14px;background:#28a745;color:#fff;border:none;border-radius:8px;font-size:16px;font-weight:bold;cursor:pointer;display:flex;align-items:center;justify-content:center;gap:8px;box-shadow:0 4px 12px rgba(40,167,69,0.3);}" +
                ".btn-start:active{background:#218838;}" +
                "</style></head><body>" +
                "<div class='card'>" +
                "  <h2>🎮 放置天堂 旗艦版啟動器</h2>" +
                "  <div class='subtitle'>10大外掛模組 + 雲端基地熱更新 + TMEngine</div>" +
                "  <div class='label'>選擇遊戲伺服器：</div>" +
                "  <select id='serverSelect'>" +
                "    <option value='" + URL_MODDED_GAME + "'>伺服器一 (pp771007)</option>" +
                "    <option value='" + URL_ORIGINAL_GAME + "'>伺服器二 (shines871)</option>" +
                "  </select>" +
                "  <button class='btn-start' onclick='launchGame()'>🚀 啟動遊戲與防斷線外掛</button>" +
                "</div>" +
                "<script>" +
                "function launchGame(){" +
                "  var url = document.getElementById('serverSelect').value;" +
                "  location.href = url;" +
                "}" +
                "</script>" +
                "</body></html>";

        webView.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null);
    }

    private void triggerBlobDownload(String blobUrl) {
        String js = "javascript:(function(){" +
                "var xhr=new XMLHttpRequest();" +
                "xhr.open('GET','" + blobUrl + "',true);" +
                "xhr.responseType='blob';" +
                "xhr.onload=function(){" +
                "  var reader=new FileReader();" +
                "  reader.onloadend=function(){" +
                "    var base64=reader.result.split(',')[1];" +
                "    AndroidBridge.saveBase64File(base64, 'application/json', 'fable5_save_" + System.currentTimeMillis() + ".json');" +
                "  };" +
                "  reader.readAsDataURL(xhr.response);" +
                "};" +
                "xhr.send();" +
                "})()";
        webView.evaluateJavascript(js, null);
    }

    private void checkAllFilesAccessPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception e) {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    startActivity(intent);
                }
            }
        }
    }

    private String guessFileName(String contentDisposition, String url) {
        String fileName = "fable5_save_" + System.currentTimeMillis() + ".json";
        try {
            if (contentDisposition != null && contentDisposition.contains("filename=")) {
                fileName = contentDisposition.split("filename=")[1].replace("\"", "").trim();
            } else if (url != null && url.contains("/")) {
                String last = url.substring(url.lastIndexOf('/') + 1);
                if (last.length() > 0 && last.length() < 100) fileName = last;
            }
        } catch (Exception ignored) {}
        return fileName;
    }

    private void processAndSaveFile(String dataUrlOrBase64, String mimeType, String fileName) {
        try {
            byte[] bytes;
            if (dataUrlOrBase64.contains(",")) {
                String base64 = dataUrlOrBase64.split(",")[1];
                bytes = Base64.decode(base64, Base64.DEFAULT);
            } else {
                bytes = Base64.decode(dataUrlOrBase64, Base64.DEFAULT);
            }

            pendingSaveBytes = bytes;
            pendingSaveFileName = fileName != null ? fileName : "fable5_save_" + System.currentTimeMillis() + ".json";

            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType(mimeType != null ? mimeType : "application/json");
            intent.putExtra(Intent.EXTRA_TITLE, pendingSaveFileName);

            startActivityForResult(intent, CREATE_DOCUMENT_RESULT_CODE);
        } catch (Exception e) {
            Toast.makeText(this, "❌ 導出失敗：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void saveBytesToUri(Uri uri, byte[] bytes) {
        try (OutputStream os = getContentResolver().openOutputStream(uri)) {
            if (os != null) {
                os.write(bytes);
                os.flush();
                Toast.makeText(this, "✅ 角色存檔已順利導出儲存！", Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "❌ 寫入檔案失敗：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void deleteRecursive(File fileOrDirectory) {
        if (fileOrDirectory.isDirectory()) {
            for (File child : fileOrDirectory.listFiles()) {
                deleteRecursive(child);
            }
        }
        fileOrDirectory.delete();
    }

    private void unzip(File zipFile, File targetDirectory) throws Exception {
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)))) {
            ZipEntry ze;
            byte[] buffer = new byte[8192];
            while ((ze = zis.getNextEntry()) != null) {
                File file = new File(targetDirectory, ze.getName());
                if (ze.isDirectory()) {
                    file.mkdirs();
                } else {
                    File parent = file.getParentFile();
                    if (parent != null) parent.mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(file)) {
                        int count;
                        while ((count = zis.read(buffer)) != -1) {
                            fos.write(buffer, 0, count);
                        }
                    }
                }
            }
        }
    }

    private void showToast(String msg) {
        mainHandler.post(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    public class AndroidBridge {

        @JavascriptInterface
        public void saveBase64File(String base64Data, String fileName) {
            saveBase64File(base64Data, "application/json", fileName);
        }

        @JavascriptInterface
        public void saveBase64File(String dataUrlOrBase64, String mimeType, String fileName) {
            runOnUiThread(() -> processAndSaveFile(dataUrlOrBase64, mimeType, fileName));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_RESULT_CODE) {
            if (filePathCallback == null) return;
            filePathCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            filePathCallback = null;
        } else if (requestCode == CREATE_DOCUMENT_RESULT_CODE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null && pendingSaveBytes != null) {
                saveBytesToUri(data.getData(), pendingSaveBytes);
            }
            pendingSaveBytes = null;
            pendingSaveFileName = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
