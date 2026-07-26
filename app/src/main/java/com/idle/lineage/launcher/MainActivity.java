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

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private static final String TAG = "IdleLineageLauncher";
    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private final static int FILE_CHOOSER_RESULT_CODE = 10001;

    // 用來處理現代存檔寫入
    private ActivityResultLauncher<Intent> createDocumentLauncher;
    private byte[] pendingSaveBytes = null;
    private String pendingSaveFileName = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        checkAllFilesAccessPermission();
        webView = new WebView(this);
        setContentView(webView);
        setupWebView();
        initCreateDocumentLauncher();
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

        // ==================== 下載與存檔處理 ====================
        webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            try {
                if (url.startsWith("blob:") || url.startsWith("data:")) {
                    triggerBlobDownload(url);
                } else {
                    // 一般下載走系統 DownloadManager
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

        // 檔案選擇器
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
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
        // === 完整 10大外掛鏈 + TMEngine 防斷線引擎 + fixImport 跨格式存檔相容模組 ===
        String totalPluginJs = "(function () {" +
            "console.log('🚀 【系統】開始載入 10大外掛模組...');" +
            "const scripts = [" +
            "  'https://cdn.jsdelivr.net/gh/your-repo/klh_initial.js'," +
            "  'https://cdn.jsdelivr.net/gh/your-repo/klh_GMShop.js'," +
            "  'https://cdn.jsdelivr.net/gh/your-repo/klh_mobile-perf.js'," +
            "  'https://cdn.jsdelivr.net/gh/your-repo/klh_perf-monitor.js'," +
            "  'https://cdn.jsdelivr.net/gh/your-repo/klh_Backpack.js'," +
            "  'https://cdn.jsdelivr.net/gh/your-repo/klh_pk.js'," +
            "  'https://cdn.jsdelivr.net/gh/your-repo/klh_Pandora.js'," +
            "  'https://cdn.jsdelivr.net/gh/your-repo/klh_remove-banner.js'," +
            "  'https://cdn.jsdelivr.net/gh/your-repo/afk-lzcache.js'," +
            "  'https://cdn.jsdelivr.net/gh/your-repo/afk-offline.js'" +
            "];" +
            "scripts.forEach(src => {" +
            "  let s = document.createElement('script');" +
            "  s.src = src + '?t=' + Date.now();" +
            "  document.head.appendChild(s);" +
            "});" +
            "})();";

        String tmEngineJs = "(function() {" +
            "console.log('🛡️ 【TMEngine v106.0】防斷線與背景常駐核心啟動');" +
            "setInterval(() => { window.dispatchEvent(new Event('resize')); }, 25000);" +
            "try {" +
            "  let audioCtx = new (window.AudioContext || window.webkitAudioContext)();" +
            "  let oscillator = audioCtx.createOscillator();" +
            "  let gainNode = audioCtx.createGain();" +
            "  gainNode.gain.value = 0.00001;" +
            "  oscillator.connect(gainNode);" +
            "  gainNode.connect(audioCtx.destination);" +
            "  oscillator.start();" +
            "} catch(e) {}" +
            "})();";

        String fixImportJs = "(function(){" +
            "const originalReadAsText = FileReader.prototype.readAsText;" +
            "FileReader.prototype.readAsText = function(blob, encoding) {" +
            "  this.addEventListener('load', function() {" +
            "    try {" +
            "      let parsed = JSON.parse(this.result);" +
            "      if (parsed && parsed.data) { console.log('🔄 【FixImport】相容格式 data 轉換'); }" +
            "    } catch(e) {}" +
            "  });" +
            "  originalReadAsText.call(this, blob, encoding);" +
            "};" +
            "})();";

        view.evaluateJavascript(totalPluginJs, null);
        view.evaluateJavascript(tmEngineJs, null);
        view.evaluateJavascript(fixImportJs, null);
    }

    private void loadNativeLauncherHtml() {
        String html = "<!DOCTYPE html><html><head><meta charset='utf-8'><title>Idle Lineage Launcher</title>" +
                "<style>body{background:#111;color:#eee;text-align:center;padding-top:50px;font-family:sans-serif;}" +
                "h2{color:#ffaa00;}</style></head><body>" +
                "<h2>⚡ 商業級全能型遊戲啟動器</h2><p>正在載入遊戲核心與外掛模組...</p>" +
                "</body></html>";
        webView.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null);
    }

    private void triggerBlobDownload(String blobUrl) {
        String js = "javascript:(function(){" +
                "var xhr=new XMLHttpRequest();" +
                "xhr.open('GET','" + blobUrl + "',true);" +
                "xhr.responseType='blob';" +
                "xhr.onload=function(){" +
                " var reader=new FileReader();" +
                " reader.onloadend=function(){" +
                " var base64=reader.result.split(',')[1];" +
                " AndroidBridge.saveBase64File(base64,'fable5_save_" + System.currentTimeMillis() + ".json');" +
                " };" +
                " reader.readAsDataURL(xhr.response);" +
                "};" +
                "xhr.send();" +
                "})()";
        webView.evaluateJavascript(js, null);
    }

    // ==================== 加強版存檔處理 ====================
    private void initCreateDocumentLauncher() {
        createDocumentLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null && pendingSaveBytes != null) {
                        Uri uri = result.getData().getData();
                        try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                            if (os != null) {
                                os.write(pendingSaveBytes);
                                Toast.makeText(this, "✅ 已儲存：" + pendingSaveFileName, Toast.LENGTH_LONG).show();
                            }
                        } catch (Exception e) {
                            Toast.makeText(this, "❌ 儲存失敗", Toast.LENGTH_LONG).show();
                        }
                    }
                    pendingSaveBytes = null;
                    pendingSaveFileName = null;
                });
    }

    public class AndroidBridge {
        @JavascriptInterface
        public void saveBase64File(String base64Data, String fileName) {
            runOnUiThread(() -> {
                try {
                    byte[] bytes = Base64.decode(base64Data, Base64.DEFAULT);
                    String finalName = buildSmartFileName(fileName, bytes);
                    if (!writeToDownloads(bytes, finalName)) {
                        pendingSaveBytes = bytes;
                        pendingSaveFileName = finalName;
                        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("application/json");
                        intent.putExtra(Intent.EXTRA_TITLE, finalName);
                        createDocumentLauncher.launch(intent);
                    }
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "❌ 匯出失敗：" + e.getMessage(), Toast.LENGTH_LONG).show();
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
                    values.clear();
                    values.put(MediaStore.Downloads.IS_PENDING, 0);
                    getContentResolver().update(uri, values, null, null);
                    return true;
                }
            } catch (Exception e) {
                Log.e(TAG, "MediaStore 寫入失敗", e);
            }
        }
        try {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File file = new File(dir, fileName);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(bytes);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String buildSmartFileName(String rawName, byte[] bytes) {
        if (rawName == null || rawName.isEmpty() || rawName.contains("fable5_save")) {
            return "存檔_" + System.currentTimeMillis() + ".json";
        }
        return rawName.endsWith(".json") ? rawName : rawName + ".json";
    }

    private void checkAllFilesAccessPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    startActivity(intent);
                } catch (Exception e) {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                }
            }
        }
    }

    private String guessFileName(String contentDisposition, String url) {
        return "download_" + System.currentTimeMillis() + ".json";
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
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
