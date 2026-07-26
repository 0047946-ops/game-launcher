package com.idle.lineage.launcher;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
import android.webkit.WebResourceResponse;
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
    private static final String KEY_GAME_URL = "custom_game_url";
    
    private static final String DEFAULT_GAME_URL = "https://pp771007.github.io/";

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

        // 原生下載監聽器（處理一般非 Blob 連結）
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
                    Toast.makeText(this, "📥 已開始匯出存檔：" + fileName, Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Toast.makeText(this, "❌ 匯出失敗：" + e.getMessage(), Toast.LENGTH_LONG).show();
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
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                Toast.makeText(MainActivity.this, "⚠️ 網頁載入失敗，請確認網路連線或網址正確性", Toast.LENGTH_LONG).show();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                
                if (url != null && url.startsWith("http")) {
                    SharedPreferences sp = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
                    
                    // 1. 自動載入雙外掛腳本
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

                    // 2. 自動灌入外部存檔到遊戲 localStorage
                    String localJson = sp.getString(KEY_SAVE_JSON, "");
                    if (!localJson.isEmpty()) {
                        String safeJson = localJson.replace("'", "\\'").replace("\n", "").replace("\r", "");
                        String syncJs = "(function(){" +
                                "try {" +
                                "  localStorage.setItem('RAW_INJECT_SAVE_DATA', '" + safeJson + "');" +
                                "  localStorage.setItem('fable5_save', '" + safeJson + "');" +
                                "  localStorage.setItem('save_data', '" + safeJson + "');" +
                                "} catch(e){}" +
                                "})()";
                        view.evaluateJavascript(syncJs, null);
                    }

                    // 3. 背景每 3 秒備份存檔
                    String autoSyncBackJs = "(function(){" +
                            "if(window.__sync_listener_active) return;" +
                            "window.__sync_listener_active = true;" +
                            "setInterval(function(){" +
                            "  try {" +
                            "    var data = localStorage.getItem('fable5_save') || localStorage.getItem('RAW_INJECT_SAVE_DATA') || localStorage.getItem('save_data');" +
                            "    if(data && data.length > 20){" +
                            "      AndroidBridge.saveGameDataSilent(data);" +
                            "    }" +
                            "  }catch(e){}" +
                            "}, 3000);" +
                            "})()";
                    view.evaluateJavascript(autoSyncBackJs, null);

                    // 4. 🔥 核心亮點：重寫網頁 Blob 下載與 URL.createObjectURL，專治人物選擇畫面的匯出！
                    String overrideBlobDownloadJs =
                        "(function(){" +
                        "if(window.__blob_override_active) return;" +
                        "window.__blob_override_active = true;" +
                        
                        // 攔截 HTML5 點擊下載事件
                        "document.addEventListener('click', function(e){" +
                        "  var target = e.target.closest && e.target.closest('a');" +
                        "  if(target && target.href){" +
                        "    var href = target.href;" +
                        "    if(href.indexOf('blob:') === 0 || target.hasAttribute('download')){" +
                        "      e.preventDefault();" +
                        "      e.stopPropagation();" +
                        "      fetch(href).then(function(res){ return res.blob(); }).then(function(blob){" +
                        "        var reader = new FileReader();" +
                        "        reader.onloadend = function(){" +
                        "          var base64 = reader.result.split(',')[1];" +
                        "          var fileName = target.download || ('fable5_save_' + Date.now() + '.json');" +
                        "          AndroidBridge.saveBase64File(base64, fileName);" +
                        "        };" +
                        "        reader.readAsDataURL(blob);" +
                        "      }).catch(function(err){" +
                        "        AndroidBridge.exportNativeSave();" +
                        "      });" +
                        "    }" +
                        "  }" +
                        "}, true);" +

                        // 重寫 URL.createObjectURL，捕捉動態生成的下載檔
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
                        "})()";
                    view.evaluateJavascript(overrideBlobDownloadJs, null);
                }
            }
        });

        handleExternalFileIntent(getIntent());
        
        loadNativeLauncherHtml();
    }

    private void loadNativeLauncherHtml() {
        SharedPreferences sp = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String savedUrl = sp.getString(KEY_GAME_URL, DEFAULT_GAME_URL);

        String html = "<!DOCTYPE html><html><head><meta charset='utf-8'>" +
                "<meta name='viewport' content='width=device-width, initial-scale=1.0'>" +
                "<style>" +
                "body{background:#121212;color:#fff;font-family:sans-serif;padding:20px;text-align:center;}" +
                "h2{color:#4CAF50;margin-top:30px;}" +
                ".btn{display:block;width:100%;padding:15px 0;margin:12px 0;background:#1e88e5;color:#fff;" +
                "border:none;border-radius:8px;font-size:16px;font-weight:bold;cursor:pointer;}" +
                ".btn-export{background:#43a047;}" +
                "input[type='text']{width:90%;padding:10px;margin:10px 0;border-radius:5px;border:none;}" +
                "</style></head><body>" +
                "<h2>天堂放置版 雙外掛啟動器</h2>" +
                "<button class='btn' onclick='location.href=\"" + savedUrl + "\"'>🚀 開始遊戲</button>" +
                "<button class='btn btn-export' onclick='AndroidBridge.exportNativeSave()'>💾 匯出最新存檔 (.json)</button>" +
                "<br><hr style='border-color:#333;'><br>" +
                "<label>遊戲目標網址設置：</label>" +
                "<input type='text' id='gameUrl' value='" + savedUrl + "'>" +
                "<button class='btn' style='background:#757575;' onclick='saveUrl()'>儲存網址設定</button>" +
                "<script>" +
                "function saveUrl(){" +
                "  var val = document.getElementById('gameUrl').value;" +
                "  AndroidBridge.saveGameUrl(val);" +
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
                            "✅ 人物存檔已成功匯出至 Download 資料夾：\n" + fileName, Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this,
                            "❌ 匯出失敗：" + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
        }

        @JavascriptInterface
        public void exportNativeSave() {
            SharedPreferences sp = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            String jsonText = sp.getString(KEY_SAVE_JSON, "");
            if (jsonText.isEmpty()) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "⚠️ 目前無暫存角色資料，請先開啟遊戲！", Toast.LENGTH_SHORT).show());
                return;
            }
            try {
                String base64 = Base64.encodeToString(jsonText.getBytes("UTF-8"), Base64.NO_WRAP);
                saveBase64File(base64, "fable5_save_" + System.currentTimeMillis() + ".json");
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "❌ 匯出失敗：" + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }

        @JavascriptInterface
        public void saveGameUrl(String newUrl) {
            SharedPreferences sp = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            sp.edit().putString(KEY_GAME_URL, newUrl).apply();
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "✅ 遊戲網址已更新！", Toast.LENGTH_SHORT).show());
        }

        @JavascriptInterface
        public void saveGameDataSilent(String jsonText) {
            SharedPreferences sp = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            sp.edit().putString(KEY_SAVE_JSON, jsonText).apply();
        }

        @JavascriptInterface
        public String loadGameData() {
            SharedPreferences sp = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            return sp.getString(KEY_SAVE_JSON, "");
        }

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
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleExternalFileIntent(intent);
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
