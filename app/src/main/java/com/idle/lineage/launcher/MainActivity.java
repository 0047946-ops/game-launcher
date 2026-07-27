package com.idle.lineage.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.URLUtil;
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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final String TAG = "IdleLineageApp";
    private static final String PREFS_NAME = "GamePrefs";

    private WebView webView;
    private RelativeLayout layoutLoading;
    private ProgressBar progressBar;
    private TextView tvLoadingStatus;

    private SharedPreferences prefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ValueCallback<Uri[]> filePathCallback;
    private byte[] pendingSaveBytes = null;
    private String pendingSaveFileName = null;
    private String saveHookJs = null;
    private final static int FILE_CHOOSER_RESULT_CODE = 10001;

    public class WebAppInterface {
        @JavascriptInterface
        public void saveBase64File(String dataUrlOrBase64, String mimeType, String fileName) {
            Log.d(TAG, "🎯 [JS 觸發導出] 檔名: " + fileName);
            runOnUiThread(() -> processAndSaveFile(dataUrlOrBase64, mimeType, fileName));
        }

        @JavascriptInterface
        public void pickSaveSlot(String slotsJson) {
            runOnUiThread(() -> showSlotChooser(slotsJson));
        }

        @JavascriptInterface
        public void log(String message) {
            Log.d(TAG, "🌐 [SaveHook] " + message);
        }

        @JavascriptInterface
        public void toast(String message) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show());
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        checkAllFilesAccessPermission();
        
        // 若找不到 layout 資源時使用動態建立介面保護，避免當機
        try {
            setContentView(R.layout.activity_main);
            webView = findViewById(R.id.webView);
            layoutLoading = findViewById(R.id.layoutLoading);
            progressBar = findViewById(R.id.progressBar);
            tvLoadingStatus = findViewById(R.id.tvLoadingStatus);
        } catch (Exception e) {
            setupFallbackLayout();
        }

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        setupWebView();
        loadNativeLauncherHtml();
    }

    private void setupFallbackLayout() {
        RelativeLayout root = new RelativeLayout(this);
        webView = new WebView(this);
        root.addView(webView, new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT, 
                RelativeLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    private void hideLoadingUI() {
        mainHandler.post(() -> {
            if (layoutLoading != null) {
                layoutLoading.setVisibility(View.GONE);
            }
        });
    }

    private void setupWebView() {
        WebView.setWebContentsDebuggingEnabled(true);
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
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(new WebAppInterface(), "AndroidDownloader");
        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                Log.w(TAG, "🌐 [JS Console] " + consoleMessage.message());
                return true;
            }

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
                    Toast.makeText(getApplicationContext(), "無法開啟檔案選擇器", Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            }

            @Override
            public boolean onJsPrompt(WebView view, String url, String message, String defaultValue, JsPromptResult result) {
                String contentToCheck = (defaultValue != null && !defaultValue.isEmpty()) ? defaultValue : message;
                if (contentToCheck != null && contentToCheck.contains("SIG1:")) {
                    processAndSaveFile(contentToCheck, "application/json", null);
                    result.confirm();
                    return true;
                }
                return super.onJsPrompt(view, url, message, defaultValue, result);
            }

            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                if (message != null && message.contains("SIG1:")) {
                    processAndSaveFile(message, "application/json", null);
                    result.confirm();
                    return true;
                }
                return super.onJsAlert(view, url, message, result);
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            private void injectDownloadHook(WebView view) {
                String js = loadSaveHookJs();
                if (js != null && !js.isEmpty()) {
                    view.evaluateJavascript(js, null);
                }
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                injectDownloadHook(view);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                injectDownloadHook(view);
                hideLoadingUI();

                if (url != null && url.startsWith("http")) {
                    String totalPluginJs = "(function () {" +
                            "'use strict';" +
                            "if(window.__all_plugins_loaded) return;" +
                            "window.__all_plugins_loaded = true;" +
                            "var s0 = document.createElement('script');" +
                            "s0.src = 'https://cdn.jsdelivr.net/gh/qcc781192000/idle-lineage-plugin@main/main.user.js?v=' + Date.now();" +
                            "document.body.appendChild(s0);" +
                            "})();";
                    view.evaluateJavascript(totalPluginJs, null);
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.startsWith("data:")) {
                    processAndSaveFile(url, "application/json", null);
                    return true;
                }
                if (url.startsWith("blob:")) {
                    triggerBlobDownload(url);
                    return true;
                }
                return super.shouldOverrideUrlLoading(view, request);
            }
        });

        webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            if (url != null && url.startsWith("blob:")) {
                triggerBlobDownload(url);
            } else {
                String suggested = null;
                try {
                    suggested = URLUtil.guessFileName(url, contentDisposition, mimetype);
                } catch (Exception ignored) {
                }
                final String hint = suggested;
                runOnUiThread(() -> processAndSaveFile(url, "application/json", hint));
            }
        });
    }

    private void processAndSaveFile(String dataUrlOrBase64, String mimeType, String fileName) {
        if (dataUrlOrBase64 == null || dataUrlOrBase64.isEmpty()) return;

        if (dataUrlOrBase64.startsWith("blob:")) {
            triggerBlobDownload(dataUrlOrBase64);
            return;
        }

        try {
            byte[] bytes;
            if (dataUrlOrBase64.contains("SIG1:")) {
                String sigData = dataUrlOrBase64.substring(dataUrlOrBase64.indexOf("SIG1:")).trim();
                bytes = sigData.getBytes(StandardCharsets.UTF_8);
            } else if (dataUrlOrBase64.trim().startsWith("{") || dataUrlOrBase64.trim().startsWith("[")) {
                bytes = dataUrlOrBase64.trim().getBytes(StandardCharsets.UTF_8);
            } else if (dataUrlOrBase64.startsWith("data:")) {
                int commaIndex = dataUrlOrBase64.indexOf(",");
                if (commaIndex != -1) {
                    String header = dataUrlOrBase64.substring(0, commaIndex);
                    String content = dataUrlOrBase64.substring(commaIndex + 1);
                    if (header.contains(";base64")) {
                        bytes = Base64.decode(content, Base64.DEFAULT);
                    } else {
                        String decodedText = URLDecoder.decode(content, "UTF-8");
                        bytes = decodedText.getBytes(StandardCharsets.UTF_8);
                    }
                } else {
                    bytes = dataUrlOrBase64.getBytes(StandardCharsets.UTF_8);
                }
            } else {
                try {
                    bytes = Base64.decode(dataUrlOrBase64, Base64.DEFAULT);
                } catch (Exception e) {
                    bytes = dataUrlOrBase64.getBytes(StandardCharsets.UTF_8);
                }
            }

            fileName = buildSaveFileName(fileName, bytes);
            if (writeToDownloads(bytes, fileName, "application/json")) {
                Toast.makeText(MainActivity.this, "✅ 已匯出：" + fileName, Toast.LENGTH_LONG).show();
                notifyJsExported();
            } else {
                shareSaveFile(bytes, fileName);
            }
        } catch (Exception e) {
            showDebugDialog("❌ 資料解析異常", e.toString());
        }
    }

    private boolean writeToDownloads(byte[] bytes, String fileName, String mimeType) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                values.put(MediaStore.Downloads.IS_PENDING, 1);

                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    boolean ok = false;
                    try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                        if (os != null) {
                            os.write(bytes);
                            os.flush();
                            ok = true;
                        }
                    }
                    values.clear();
                    values.put(MediaStore.Downloads.IS_PENDING, 0);
                    getContentResolver().update(uri, values, null, null);
                    return ok;
                }
            } catch (Exception e) {
                Log.e(TAG, "MediaStore 寫入失敗: " + e.getMessage());
            }
            return false;
        }

        try {
            File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File file = new File(downloadsDir, fileName);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(bytes);
                fos.flush();
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void notifyJsExported() {
        mainHandler.post(() -> {
            try {
                webView.evaluateJavascript("window.__markExported && window.__markExported();", null);
            } catch (Exception ignored) {
            }
        });
    }

    private void dumpStorageDiagnostics() {
        webView.evaluateJavascript("window.__dumpStorage ? window.__dumpStorage() : 'no hook'", value -> {
            String text = value;
            String name = "存檔診斷_" + timestamp() + ".txt";
            boolean ok = writeToDownloads(text.getBytes(StandardCharsets.UTF_8), name, "text/plain");
            showDebugDialog(ok ? "診斷已存成 " + name : "診斷存檔失敗", text);
        });
    }

    private void shareSaveFile(byte[] data, String fileName) {
        try {
            File cacheFile = new File(getCacheDir(), fileName);
            try (FileOutputStream fos = new FileOutputStream(cacheFile)) {
                fos.write(data);
                fos.flush();
            }

            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("application/json");
            shareIntent.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(cacheFile));
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            startActivity(Intent.createChooser(shareIntent, "儲存遊戲存檔: " + fileName));
        } catch (Exception e) {
            showDebugDialog("❌ 分享失敗", e.getMessage());
        }
    }

    private String buildSaveFileName(String rawName, byte[] bytes) {
        String base = rawName == null ? "" : rawName.trim();
        base = base.replaceAll("(?i)\\.(json|txt|sav|dat|bin)$", "").trim();
        if (base.isEmpty()) {
            base = "存檔_" + timestamp();
        }
        return sanitizeFileName(base) + ".json";
    }

    private String sanitizeFileName(String name) {
        String out = name.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t\\x00-\\x1f]", "_").trim();
        if (out.length() > 80) out = out.substring(0, 80);
        return out.isEmpty() ? "存檔" : out;
    }

    private String timestamp() {
        return new SimpleDateFormat("yyyyMMdd-HHmm", Locale.TAIWAN).format(new Date());
    }

    private void showSlotChooser(String slotsJson) {
        try {
            JSONArray arr = new JSONArray(slotsJson);
            if (arr.length() == 0) {
                dumpStorageDiagnostics();
                return;
            }

            final String[] keys = new String[arr.length()];
            final String[] labels = new String[arr.length()];
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                keys[i] = o.optString("key");
                labels[i] = o.optString("label", keys[i]);
            }

            new AlertDialog.Builder(this)
                    .setTitle("要匯出哪一個角色？")
                    .setItems(labels, (dialog, which) -> {
                        String js = "window.__exportSlotByKey && window.__exportSlotByKey("
                                + JSONObject.quote(keys[which]) + ");";
                        webView.evaluateJavascript(js, null);
                    })
                    .setNegativeButton("取消", null)
                    .show();
        } catch (Exception e) {
            showDebugDialog("❌ 讀取清單失敗", e.toString());
        }
    }

    private String loadSaveHookJs() {
        if (saveHookJs != null) return saveHookJs;
        try (InputStream is = getAssets().open("save_hook.js");
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = is.read(buffer)) != -1) {
                bos.write(buffer, 0, read);
            }
            saveHookJs = new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            saveHookJs = "";
        }
        return saveHookJs;
    }

    private void showDebugDialog(String title, String message) {
        runOnUiThread(() -> {
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton("確定", null)
                    .show();
        });
    }

    private void loadNativeLauncherHtml() {
        String html = "<!DOCTYPE html><html><head><meta charset='utf-8'>" +
                "<meta name='viewport' content='width=device-width, initial-scale=1.0'>" +
                "<style>" +
                "body{background:#121212;color:#fff;font-family:sans-serif;padding:20px;display:flex;justify-content:center;align-items:center;min-height:90vh;margin:0;}" +
                ".card{background:#1e1e1e;border-radius:16px;padding:24px;width:100%;max-width:380px;text-align:center;box-sizing:border-box;}" +
                "h2{font-size:20px;margin-bottom:4px;}" +
                ".subtitle{color:#8e8e93;font-size:13px;margin-bottom:24px;}" +
                ".label{color:#4cd964;font-size:14px;text-align:left;margin-bottom:8px;font-weight:bold;}" +
                "select{width:100%;padding:12px;background:#2c2c2e;color:#fff;border:1px solid #3a3a3c;border-radius:8px;font-size:15px;margin-bottom:24px;}" +
                ".btn-start{width:100%;padding:14px;background:#28a745;color:#fff;border:none;border-radius:8px;font-size:16px;font-weight:bold;cursor:pointer;}" +
                "</style></head><body>" +
                "<div class='card'>" +
                "  <h2>🎮 放置天堂 啟動器</h2>" +
                "  <div class='subtitle'>防斷線引擎與外掛模組</div>" +
                "  <div class='label'>選擇遊戲伺服器：</div>" +
                "  <select id='serverSelect'>" +
                "    <option value='https://pp771007.github.io/idle-lineage-class/'>伺服器一 (pp771007)</option>" +
                "    <option value='https://shines871.github.io/idle-lineage-class/'>伺服器二 (shines871)</option>" +
                "  </select>" +
                "  <button class='btn-start' onclick='launchGame()'>🚀 啟動遊戲</button>" +
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
        String js = "(function(){" +
                "var xhr=new XMLHttpRequest();" +
                "xhr.open('GET','" + blobUrl + "',true);" +
                "xhr.responseType='blob';" +
                "xhr.onload=function(){" +
                "  var reader=new FileReader();" +
                "  reader.onloadend=function(){" +
                "    var base64=reader.result.split(',')[1];" +
                "    AndroidBridge.saveBase64File(base64,'save_" + System.currentTimeMillis() + ".json');" +
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

    public class AndroidBridge {
        @JavascriptInterface
        public void saveBase64File(String base64Data, String fileName) {
            runOnUiThread(() -> {
                try {
                    byte[] bytes = Base64.decode(base64Data, Base64.DEFAULT);
                    File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    if (!dir.exists()) dir.mkdirs();
                    File outFile = new File(dir, fileName);
                    FileOutputStream fos = new FileOutputStream(outFile);
                    fos.write(bytes);
                    fos.close();
                    Toast.makeText(MainActivity.this, "✅ 存檔已匯出：" + fileName, Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "❌ 匯出失敗", Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_RESULT_CODE) {
            if (filePathCallback == null) return;
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
