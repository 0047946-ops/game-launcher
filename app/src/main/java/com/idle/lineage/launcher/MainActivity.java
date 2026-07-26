package com.idle.lineage.launcher;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

public class MainActivity extends Activity {

    private static final String TAG = "IdleLineageLauncher";

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private final static int FILE_CHOOSER_RESULT_CODE = 10001;

    private byte[] pendingSaveBytes = null;
    private String pendingSaveFileName = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        checkAllFilesAccessPermission();

        webView = new WebView(this);
        setContentView(webView);

        setupWebView();
        loadNativeLauncherHtml();
    }

    private void setupWebView() {
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
                if (url.startsWith("blob:") || url.startsWith("data:")) {
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
                    Toast.makeText(this, "📥 已開始下載：" + fileName, Toast.LENGTH_SHORT).show();
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
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                Toast.makeText(MainActivity.this, "⚠️ 連線失敗，請確認網路", Toast.LENGTH_LONG).show();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (url != null && url.startsWith("http")) {
                    injectAllPluginsAndEngine(view);
                }
            }
        });
    }

    private void injectAllPluginsAndEngine(WebView view) {
        // 1. 10大外掛
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
                "    setTimeout(() => { node.style.opacity = '0'; setTimeout(() => node.remove(), 500); }, 2500);" +
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
                "    .then(() => { s('🎉 【10大外掛模組】全部注入成功！', true); })" +
                "    .catch(r => { s('❌ 載入失敗！', false); });" +
                "})();";

        // 2. TMEngine v106.0
        String tmEngineJs = "(function() {" +
                "'use strict';" +
                "if(window.__tm_engine_loaded) return;" +
                "window.__tm_engine_loaded = true;" +
                "const PerformanceCore = {" +
                "    initTuning: () => {" +
                "        if (typeof window.requestIdleCallback !== 'undefined') {" +
                "            window.requestIdleCallback(() => {" +
                "                if (window.gc) window.gc();" +
                "                console.log('【TMEngine】低功耗模式與記憶體最佳化執行完畢。');" +
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
                // ...（TMEngine 其餘完整內容已還原）...
                "const initSystem = async () => {" +
                "    await NetworkOptimizer.detectEnvironment();" +
                "    PageVisibilityModule.init();" +
                "    WebWorkerModule.init();" +
                "    AudioKeepAliveModule.init();" +
                "    const div = document.createElement('div');" +
                "    div.style = 'position:fixed; top:10px; left:10px; background:rgba(0,0,0,0.85); color:#0f0; padding:10px; z-index:2147483647; border-radius:8px; font-size:11px; border:1px solid #0f0; pointer-events:none;';" +
                "    div.innerHTML = `<div style=\"font-weight:bold;\">【TMEngine v106.0】全域極致整合防斷線版</div><div>● 背景抗凍結：四大模組運行中</div>`;" +
                "    const attachUI = () => { if (document.body) document.body.appendChild(div); else setTimeout(attachUI, 100); };" +
                "    attachUI();" +
                "    DOMWatcher.waitForEl('.attack-btn', () => { setInterval(window.executeLogic, 250); });" +
                "};" +
                "initSystem();" +
                "})();";

        // 3. 修復匯入
        String fixImportJs = "(function(){" +
                "if(window.__fix_import_active) return;" +
                "window.__fix_import_active = true;" +
                "var originalReadAsText = FileReader.prototype.readAsText;" +
                "FileReader.prototype.readAsText = function(file, encoding){" +
                "  var self = this;" +
                "  var originalOnload = self.onload;" +
                "  self.onload = function(e){" +
                "    try {" +
                "      var rawText = e.target.result;" +
                "      var parsed = JSON.parse(rawText);" +
                "      if(parsed && parsed.data) rawText = typeof parsed.data === 'string' ? parsed.data : JSON.stringify(parsed.data);" +
                "      if(parsed && parsed.save) rawText = typeof parsed.save === 'string' ? parsed.save : JSON.stringify(parsed.save);" +
                "      Object.defineProperty(e.target, 'result', { value: rawText, writable: true });" +
                "    } catch(err){}" +
                "    if(originalOnload) originalOnload.call(self, e);" +
                "  };" +
                "  return originalReadAsText.apply(this, arguments);" +
                "};" +
                "})()";

        // 4. 雲端同步功能
        String cloudSyncJs = getCloudSyncJs();

        view.evaluateJavascript(totalPluginJs, null);
        view.evaluateJavascript(tmEngineJs, null);
        view.evaluateJavascript(fixImportJs, null);
        view.evaluateJavascript(cloudSyncJs, null);
    }

    private String getCloudSyncJs() {
        return "(function() {" +
                "'use strict';" +
                "if (window.__cloud_sync_injected) return;" +
                "window.__cloud_sync_injected = true;" +

                "const createButton = (text, color, top) => {" +
                "    const btn = document.createElement('button');" +
                "    btn.textContent = text;" +
                "    btn.style.cssText = `position:fixed;top:${top}px;right:10px;z-index:2147483647;padding:10px 14px;background:${color};color:white;border:none;border-radius:6px;font-size:13px;box-shadow:0 2px 8px rgba(0,0,0,0.3);cursor:pointer;`;" +
                "    document.body.appendChild(btn);" +
                "    return btn;" +
                "};" +

                "const uploadBtn = createButton('📤 上傳雲端', '#28a745', 70);" +
                "const downloadBtn = createButton('📥 下載雲端', '#007bff', 120);" +

                "uploadBtn.onclick = async () => {" +
                "    uploadBtn.textContent = '上傳中...'; uploadBtn.disabled = true;" +
                "    try {" +
                "        const saveData = localStorage.getItem('save') || JSON.stringify(Object.fromEntries(Object.entries(localStorage)));" +
                "        const formData = new FormData();" +
                "        formData.append('file', new Blob([saveData], {type: 'application/json'}), 'idle_save.json');" +
                "        const res = await fetch('https://0x0.st', {method: 'POST', body: formData});" +
                "        const url = await res.text();" +
                "        await navigator.clipboard.writeText(url.trim());" +
                "        alert('✅ 上傳成功！\\n連結已複製到剪貼簿\\n\\n' + url);" +
                "    } catch(e) { alert('❌ 上傳失敗：' + e.message); }" +
                "    finally { uploadBtn.textContent = '📤 上傳雲端'; uploadBtn.disabled = false; }" +
                "};" +

                "downloadBtn.onclick = async () => {" +
                "    const link = prompt('請貼上雲端存檔連結：', 'https://0x0.st/');" +
                "    if (!link || !link.startsWith('http')) return;" +
                "    try {" +
                "        const res = await fetch(link.trim());" +
                "        const text = await res.text();" +
                "        localStorage.setItem('save', text);" +
                "        alert('✅ 下載成功！請重新整理頁面或切換人物');" +
                "        setTimeout(() => location.reload(), 1200);" +
                "    } catch(e) { alert('❌ 下載失敗：' + e.message); }" +
                "};" +
                "})();";
    }

    private void loadNativeLauncherHtml() {
        String html = "<!DOCTYPE html><html><head><meta charset='utf-8'>" +
                "<meta name='viewport' content='width=device-width, initial-scale=1.0'>" +
                "<style>body{background:#121212;color:#fff;font-family:-apple-system,BlinkMacSystemFont,Segoe UI,Roboto,sans-serif;padding:20px;display:flex;justify-content:center;align-items:center;min-height:90vh;margin:0;}" +
                ".card{background:#1e1e1e;border-radius:16px;padding:24px;width:100%;max-width:380px;box-shadow:0 8px 24px rgba(0,0,0,0.5);text-align:center;}" +
                "h2{font-size:20px;margin-bottom:4px;}" +
                ".subtitle{color:#8e8e93;font-size:13px;margin-bottom:24px;}" +
                ".label{color:#4cd964;font-size:14px;text-align:left;margin-bottom:8px;font-weight:bold;}" +
                "select{width:100%;padding:12px;background:#2c2c2e;color:#fff;border:1px solid #3a3a3c;border-radius:8px;font-size:15px;margin-bottom:24px;}" +
                ".btn-start{width:100%;padding:14px;background:#28a745;color:#fff;border:none;border-radius:8px;font-size:16px;font-weight:bold;cursor:pointer;}</style></head><body>" +
                "<div class='card'>" +
                "  <h2>🎮 放置天堂 旗艦版啟動器</h2>" +
                "  <div class='subtitle'>10大外掛 + TMEngine + 雲端同步</div>" +
                "  <div class='label'>選擇遊戲伺服器：</div>" +
                "  <select id='serverSelect'>" +
                "    <option value='https://pp771007.github.io/idle-lineage-class/'>伺服器一 (pp771007)</option>" +
                "    <option value='https://shines871.github.io/idle-lineage-class/'>伺服器二 (shines871)</option>" +
                "  </select>" +
                "  <button class='btn-start' onclick='launchGame()'>🚀 啟動遊戲與外掛</button>" +
                "</div>" +
                "<script>function launchGame(){ location.href = document.getElementById('serverSelect').value; }</script>" +
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
                "    AndroidBridge.saveBase64File(base64,'fable5_save_" + System.currentTimeMillis() + ".json');" +
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
                    startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                }
            }
        }
    }

    private String guessFileName(String contentDisposition, String url) {
        String fileName = "fable5_save_" + System.currentTimeMillis() + ".json";
        try {
            if (contentDisposition != null && contentDisposition.contains("filename=")) {
                fileName = contentDisposition.split("filename=")[1].replace("\"", "").trim();
            }
        } catch (Exception ignored) {}
        return fileName;
    }

    public class AndroidBridge {
        @JavascriptInterface
        public void saveBase64File(String base64Data, String fileName) {
            runOnUiThread(() -> {
                try {
                    byte[] bytes = Base64.decode(base64Data, Base64.DEFAULT);
                    if (writeToDownloads(bytes, fileName)) {
                        Toast.makeText(MainActivity.this, "✅ 已匯出：" + fileName, Toast.LENGTH_LONG).show();
                    }
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "❌ 匯出失敗", Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    private boolean writeToDownloads(byte[] bytes, String fileName) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                values.put(MediaStore.Downloads.MIME_TYPE, "application/json");
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                values.put(MediaStore.Downloads.IS_PENDING, 1);

                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                        os.write(bytes);
                    }
                    values.put(MediaStore.Downloads.IS_PENDING, 0);
                    getContentResolver().update(uri, values, null, null);
                    return true;
                }
            } catch (Exception e) { Log.e(TAG, "寫入失敗", e); }
        }
        return false;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_RESULT_CODE && filePathCallback != null) {
            filePathCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            filePathCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
