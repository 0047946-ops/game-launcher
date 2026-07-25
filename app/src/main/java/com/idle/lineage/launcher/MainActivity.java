package com.idle.lineage.launcher;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.Base64;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;

public class MainActivity extends Activity {

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private final static int FILE_CHOOSER_RESULT_CODE = 10001;
    private static final String PREF_NAME = "IdleLineageSaveData";
    private static final String KEY_SAVE_JSON = "player_save_json";
    private static final String KEY_CUSTOM_PLUGIN_URL = "custom_plugin_url";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

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

        // 處理檔點擊與下載
        webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            try {
                if (url.startsWith("blob:")) {
                    String js = "javascript:(function(){" +
                            "var xhr=new XMLHttpRequest();" +
                            "xhr.open('GET','" + url + "',true);" +
                            "xhr.responseType='blob';" +
                            "xhr.onload=function(){" +
                            "  var reader=new FileReader();" +
                            "  reader.onloadend=function(){" +
                            "    var base64=reader.result.split(',')[1];" +
                            "    AndroidBridge.saveBase64File(base64,'fable5_save.json');" +
                            "  };" +
                            "  reader.readAsDataURL(xhr.response);" +
                            "};" +
                            "xhr.send();" +
                            "})()";
                    webView.evaluateJavascript(js, null);
                } else {
                    DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                    request.setMimeType(mimetype);
                    request.addRequestHeader("User-Agent", userAgent);
                    request.setNotificationVisibility(
                            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);

                    String fileName = guessFileName(contentDisposition, url);
                    request.setDestinationInExternalPublicDir(
                            Environment.DIRECTORY_DOWNLOADS, fileName);

                    DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                    dm.enqueue(request);
                    Toast.makeText(this, "已開始下載：" + fileName, Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Toast.makeText(this, "下載失敗：" + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });

        // 綁定原生橋樑
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
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (url != null && url.startsWith("http")) {
                    // 1. 預設腳本 1
                    String js1 = "if(!window.__main_plugin_loaded){window.__main_plugin_loaded=true;" +
                            "var s1=document.createElement('script');" +
                            "s1.src='https://cdn.jsdelivr.net/gh/qcc781192000/idle-lineage-plugin@main/main.user.js';" +
                            "document.head.appendChild(s1);}";
                    
                    // 2. 預設腳本 2
                    String js2 = "if(!window.__gm_shop_loaded){window.__gm_shop_loaded=true;" +
                            "var s2=document.createElement('script');" +
                            "s2.src='https://kid0924.github.io/idle-lineage-class/klh_GMShop.js?t='+Date.now();" +
                            "document.head.appendChild(s2);}";
                    
                    view.evaluateJavascript(js1, null);
                    view.evaluateJavascript(js2, null);

                    // 3. 注入玩家自訂外掛腳本網址 (若有設定)
                    SharedPreferences sp = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
                    String customPluginUrl = sp.getString(KEY_CUSTOM_PLUGIN_URL, "");
                    if (!customPluginUrl.isEmpty()) {
                        String jsCustom = "if(!window.__custom_plugin_loaded){window.__custom_plugin_loaded=true;" +
                                "var sc=document.createElement('script');" +
                                "sc.src='" + customPluginUrl + "';" +
                                "document.head.appendChild(sc);}";
                        view.evaluateJavascript(jsCustom, null);
                    }

                    // 4. 攔截下載
                    String hookDownload =
                        "(function(){" +
                        "document.addEventListener('click', function(e){" +
                        "  var a = e.target.closest && e.target.closest('a[download]');" +
                        "  if(a && a.href && a.href.indexOf('blob:') === 0){" +
                        "    e.preventDefault();" +
                        "    fetch(a.href).then(r=>r.blob()).then(function(blob){" +
                        "      var reader = new FileReader();" +
                        "      reader.onloadend = function(){" +
                        "        var base64 = reader.result.split(',')[1];" +
                        "        var name = a.download || 'fable5_save.json';" +
                        "        AndroidBridge.saveBase64File(base64, name);" +
                        "      };" +
                        "      reader.readAsDataURL(blob);" +
                        "    });" +
                        "  }" +
                        "}, true);" +
                        "})()";
                    view.evaluateJavascript(hookDownload, null);
                }
            }
        });

        // 處理從手機檔案管理器直接點擊開啟 .json 檔的 Intent
        handleExternalFileIntent(getIntent());

        webView.loadUrl("file:///android_asset/index.html");
    }

    private void handleExternalFileIntent(Intent intent) {
        if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction())) {
            Uri uri = intent.getData();
            if (uri != null) {
                try {
                    InputStream inputStream = getContentResolver().openInputStream(uri);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    inputStream.close();
                    
                    String jsonContent = sb.toString();
                    getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                            .edit().putString(KEY_SAVE_JSON, jsonContent).apply();
                    
                    Toast.makeText(this, "✅ 已成功讀入外部 .json 存檔！", Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Toast.makeText(this, "❌ 讀取外部檔案失敗：" + e.getMessage(), Toast.LENGTH_SHORT).show();
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

        // 1. 匯出檔案到 Download 資料夾
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
                            "✅ 存檔已成功匯出至 Download：" + fileName, Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this,
                            "❌ 匯出失敗：" + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
        }

        // 2. 原生私有空間：保存角色資料 JSON
        @JavascriptInterface
        public void saveGameData(String jsonText) {
            SharedPreferences sp = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            sp.edit().putString(KEY_SAVE_JSON, jsonText).apply();
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "✅ 角色進度已成功同步至手機原生儲存區！", Toast.LENGTH_SHORT).show());
        }

        // 3. 原生私有空間：讀取角色資料 JSON
        @JavascriptInterface
        public String loadGameData() {
            SharedPreferences sp = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            return sp.getString(KEY_SAVE_JSON, "");
        }

        // 4. 一鍵取得手機剪貼簿內容
        @JavascriptInterface
        public String getClipboardText() {
            try {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null && clipboard.hasPrimaryClip() && clipboard.getPrimaryClip().getItemCount() > 0) {
                    CharSequence text = clipboard.getPrimaryClip().getItemAt(0).getText();
                    return text != null ? text.toString() : "";
                }
            } catch (Exception ignored) {}
            return "";
        }

        // 5. 新增：保存玩家自訂外掛腳本網址
        @JavascriptInterface
        public void saveCustomPluginUrl(String url) {
            SharedPreferences sp = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            sp.edit().putString(KEY_CUSTOM_PLUGIN_URL, url).apply();
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "✅ 自訂外掛腳本網址已儲存！", Toast.LENGTH_SHORT).show());
        }

        // 6. 新增：讀取玩家自訂外掛腳本網址
        @JavascriptInterface
        public String getCustomPluginUrl() {
            SharedPreferences sp = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            return sp.getString(KEY_CUSTOM_PLUGIN_URL, "");
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
