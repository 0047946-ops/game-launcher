package com.idle.lineage.launcher;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.Base64;
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

public class MainActivity extends Activity {

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private final static int FILE_CHOOSER_RESULT_CODE = 10001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        checkAllFilesAccessPermission();

        webView = new WebView(this);
        setContentView(webView);

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

        // 監聽原生下載
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

        // 接管檔案選擇器，修復匯入並加入格式自動校正處理
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
                Toast.makeText(MainActivity.this, "⚠️ 網址連線失敗 (404)，請確認網路狀態", Toast.LENGTH_LONG).show();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                
                if (url != null && url.startsWith("http")) {
                    // 1. 自動注入雙外掛
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

                    // 2. 🔥 升級版：深度攔截人物選擇畫面 Blob 匯出、點擊事件與存檔格式相容校正
                    String fixSaveExportAndImportJs =
                        "(function(){" +
                        "if(window.__fix_save_active) return;" +
                        "window.__fix_save_active = true;" +

                        // (A) 全域快取最近產生的 Blob 內容
                        "window.__last_blob_base64 = null;" +

                        // (B) 攔截 URL.createObjectURL，第一時間抓取產生的二進位資料
                        "var originalCreateObjectURL = URL.createObjectURL;" +
                        "URL.createObjectURL = function(blob){" +
                        "  var url = originalCreateObjectURL.apply(this, arguments);" +
                        "  try {" +
                        "    var reader = new FileReader();" +
                        "    reader.onloadend = function(){" +
                        "      if(reader.result && reader.result.indexOf('data:') === 0){" +
                        "        window.__last_blob_base64 = reader.result.split(',')[1];" +
                        "      }" +
                        "    };" +
                        "    reader.readAsDataURL(blob);" +
                        "  } catch(err){}" +
                        "  return url;" +
                        "};" +

                        // (C) 主動監聽 DOM 點擊事件：完美攔截人物選擇畫面的匯出按鈕與 <a> 標籤
                        "document.addEventListener('click', function(e) {" +
                        "  var target = e.target;" +
                        "  while(target && target.tagName !== 'A') {" +
                        "    target = target.parentElement;" +
                        "  }" +
                        "  if(target && target.href) {" +
                        "    var href = target.href;" +
                        "    if(href.indexOf('blob:') === 0 || target.hasAttribute('download')) {" +
                        "      e.preventDefault();" +
                        "      e.stopPropagation();" +
                        "      var filename = target.getAttribute('download') || ('fable5_character_' + Date.now() + '.json');" +
                        "      if(window.__last_blob_base64) {" +
                        "        AndroidBridge.saveBase64File(window.__last_blob_base64, filename);" +
                        "      } else {" +
                        "        fetch(href)" +
                        "          .then(function(res){ return res.blob(); })" +
                        "          .then(function(blob){" +
                        "            var r = new FileReader();" +
                        "            r.onloadend = function(){" +
                        "              if(r.result) {" +
                        "                var b64 = r.result.split(',')[1];" +
                        "                AndroidBridge.saveBase64File(b64, filename);" +
                        "              }" +
                        "            };" +
                        "            r.readAsDataURL(blob);" +
                        "          }).catch(function(err){});" +
                        "      }" +
                        "    }" +
                        "  }" +
                        "}, true);" +

                        // (D) 攔截原形鏈的 click 模擬點擊
                        "var originalClick = HTMLAnchorElement.prototype.click;" +
                        "HTMLAnchorElement.prototype.click = function() {" +
                        "  if (this.href && (this.href.indexOf('blob:') === 0 || this.hasAttribute('download'))) {" +
                        "    var filename = this.getAttribute('download') || ('fable5_character_' + Date.now() + '.json');" +
                        "    if (window.__last_blob_base64) {" +
                        "      AndroidBridge.saveBase64File(window.__last_blob_base64, filename);" +
                        "      return;" +
                        "    }" +
                        "  }" +
                        "  return originalClick.apply(this, arguments);" +
                        "};" +

                        // (E) 重寫 FileReader 讀取機制：自動校正與解開外掛封裝格式
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
                    view.evaluateJavascript(fixSaveExportAndImportJs, null);
                }
            }
        });

        loadNativeLauncherHtml();
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
                "  <h2>🎮 放置天堂雙外掛啟動器</h2>" +
                "  <div class='subtitle'>Android 原生 WebView 注入版</div>" +
                "  <div class='label'>選擇遊戲伺服器：</div>" +
                "  <select id='serverSelect'>" +
                "    <option value='https://pp771007.github.io/idle-lineage-class/'>伺服器一 (pp771007)</option>" +
                "    <option value='https://shines871.github.io/idle-lineage-class/'>伺服器二 (shines871)</option>" +
                "  </select>" +
                "  <button class='btn-start' onclick='launchGame()'>🚀 啟動遊戲並載入雙外掛</button>" +
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
                "if(window.__last_blob_base64){" +
                "  AndroidBridge.saveBase64File(window.__last_blob_base64, 'fable5_save_" + System.currentTimeMillis() + ".json');" +
                "} else {" +
                "  var xhr=new XMLHttpRequest();" +
                "  xhr.open('GET','" + blobUrl + "',true);" +
                "  xhr.responseType='blob';" +
                "  xhr.onload=function(){" +
                "    var reader=new FileReader();" +
                "    reader.onloadend=function(){" +
                "      var base64=reader.result.split(',')[1];" +
                "      AndroidBridge.saveBase64File(base64,'fable5_save_" + System.currentTimeMillis() + ".json');" +
                "    };" +
                "    reader.readAsDataURL(xhr.response);" +
                "  };" +
                "  xhr.send();" +
                "}" +
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
                    Toast.makeText(MainActivity.this,
                            "✅ 角色存檔已成功匯出至 Download 資料夾：\n" + fileName, Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this,
                            "❌ 匯出失敗：" + e.getMessage(), Toast.LENGTH_LONG).show();
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
