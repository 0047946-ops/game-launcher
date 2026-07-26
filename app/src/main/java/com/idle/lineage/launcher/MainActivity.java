package com.idle.lineage.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.Base64;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;

public class MainActivity extends Activity {

    private static final String TAG = "GameLauncher";

    private static final String URL_ORIGINAL = "https://shines871.github.io/idle-lineage-class/";
    private static final String URL_MODDED   = "https://pp771007.github.io/idle-lineage-class/";

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private final static int FILE_CHOOSER_RESULT_CODE = 10001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);
        setupWebView();

        showVersionChooser();
    }

    private void showVersionChooser() {
        new AlertDialog.Builder(this)
            .setTitle("放置天堂多功能外掛啟動器")
            .setMessage("請選擇要遊玩的版本")
            .setCancelable(false)
            .setPositiveButton("原版遊玩", (dialog, which) -> webView.loadUrl(URL_ORIGINAL))
            .setNegativeButton("加掛版遊玩", (dialog, which) -> webView.loadUrl(URL_MODDED))
            .show();
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

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");

        // 保留：一般 http(s) 下載連結的備用處理（這個遊戲用不到，但留著無害）
        webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            try {
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                request.setMimeType(mimetype);
                request.addRequestHeader("User-Agent", userAgent);
                request.setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                String fileName = guessFileName(contentDisposition, url);
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
                DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                dm.enqueue(request);
                toast("已開始下載：" + fileName);
            } catch (Exception e) {
                toast("❌ DownloadManager 失敗：" + e.getMessage());
            }
        });

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
                    toast("❌ 開啟檔案選擇器失敗：" + e.getMessage());
                    return false;
                }
                return true;
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (url != null && url.startsWith("http")) {
                    injectPlugins(view);
                    injectExportHook(view);
                }
            }
        });
    }

    private void injectPlugins(WebView view) {
        String currentUrl = view.getUrl();
        if (currentUrl != null && currentUrl.contains("pp771007")) {
            String js1 = "if(!window.__main_plugin_loaded){window.__main_plugin_loaded=true;" +
                    "var s1=document.createElement('script');" +
                    "s1.src='https://cdn.jsdelivr.net/gh/qcc781192000/idle-lineage-plugin@main/main.user.js';" +
                    "document.head.appendChild(s1);}";
            String js2 = "if(!window.__gm_shop_loaded){window.__gm_shop_loaded=true;" +
                    "var s2=document.createElement('script');" +
                    "s2.src='https://kid0924.github.io/idle-lineage-class/klh_GMShop.js?t='+Date.now();" +
                    "document.head.appendChild(s2);}";
            view.evaluateJavascript(js1, null);
            view.evaluateJavascript(js2, null);
        }
    }

    // 精準版：只攔截這款遊戲實際使用的 Blob + <a download> 匯出手法
    // 涵蓋人物選擇畫面與遊戲內畫面，因為兩者呼叫同一個 exportSave() 邏輯
    private void injectExportHook(WebView view) {
        String js =
            "(function(){" +
            "if (window.__export_hook_installed) return;" +
            "window.__export_hook_installed = true;" +

            "document.addEventListener('click', function(e){" +
            "  var a = e.target.closest && e.target.closest('a[download]');" +
            "  if(a && a.href && a.href.indexOf('blob:') === 0){" +
            "    e.preventDefault();" +
            "    var fname = a.download || 'save.json';" +
            "    AndroidBridge.logDebug('偵測到匯出，檔名：' + fname);" +
            "    fetch(a.href).then(function(r){ return r.blob(); }).then(function(blob){" +
            "      var reader = new FileReader();" +
            "      reader.onloadend = function(){" +
            "        var base64 = reader.result.split(',')[1];" +
            "        AndroidBridge.saveBase64File(base64, fname);" +
            "      };" +
            "      reader.readAsDataURL(blob);" +
            "    }).catch(function(err){ AndroidBridge.logDebug('讀取失敗：' + err.message); });" +
            "  }" +
            "}, true);" +

            "AndroidBridge.logDebug('匯出攔截已安裝');" +
            "})();";

        view.evaluateJavascript(js, null);
    }

    private String guessFileName(String contentDisposition, String url) {
        String fileName = "download_" + System.currentTimeMillis();
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

    private void toast(String msg) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show());
    }

    public class AndroidBridge {
        @JavascriptInterface
        public void logDebug(String msg) {
            Log.d(TAG, msg);
            toast("🔧 " + msg);
        }

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
                    toast("✅ 匯出成功：" + outFile.getAbsolutePath());
                } catch (Exception e) {
                    toast("❌ 寫檔失敗：" + e.getMessage());
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
