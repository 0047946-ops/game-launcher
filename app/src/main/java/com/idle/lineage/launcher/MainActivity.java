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
    
    private static final String KEY_PLUGIN_URL_1 = "custom_plugin_url_1";
    private static final String KEY_PLUGIN_URL_2 = "custom_plugin_url_2";

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

        // 🔥 1. 原生下載監聽器（處理一般 URL 與 Blob URL）
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
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                
                if (url != null && url.startsWith("http")) {
                    SharedPreferences sp = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
                    
                    // 載入預設腳本
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

                    // 載入自訂腳本
                    String plugin1 = sp.getString(KEY_PLUGIN_URL_1, "");
                    if (!plugin1.isEmpty()) {
                        String jsCustom1 = "if(!window.__custom_plugin_1_loaded){window.__custom_plugin_1_loaded=true;" +
                                "var sc1=document.createElement('script');sc1.src='" + plugin1 + "';" +
                                "document.head.appendChild(sc1);}";
                        view.evaluateJavascript(jsCustom1, null);
                    }

                    String plugin2 = sp.getString(KEY_PLUGIN_URL_2, "");
                    if (!plugin2.isEmpty()) {
                        String jsCustom2 = "if(!window.__custom_plugin_2_loaded){window.__custom_plugin_2_loaded=true;" +
                                "var sc2=document.createElement('script');sc2.src='" + plugin2 + "';" +
                                "document.head.appendChild(sc2);}";
                        view.evaluateJavascript(jsCustom2, null);
                    }

                    // 自動同步進度至遊戲 DOM 空間
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

                    // 背景自動備份存檔
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
                            "}, 5000);" +
                            "})()";
                    view.evaluateJavascript(autoSyncBackJs, null);

                    // 🔥 2. 全域 Hook 攔截：強制將網頁中的匯出/下載動作轉導至 Android 原生檔案寫入
                    String hookDownload =
                        "(function(){" +
                        "if(window.__export_hook_active) return;" +
                        "window.__export_hook_active = true;" +
                        "document.addEventListener('click', function(e){" +
                        "  var a = e.target.closest && e.target.closest('a');" +
                        "  if(a && a.href){" +
                        "    if(a.href.indexOf('blob:') === 0 || a.hasAttribute('download')){" +
                        "      e.preventDefault();" +
                        "      fetch(a.href).then(r=>r.blob()).then(function(blob){" +
                        "        var reader = new FileReader();" +
                        "        reader.onloadend = function(){" +
                        "          var base64 = reader.result.split(',')[1];" +
                        "          var name = a.download || 'fable5_save.json';" +
                        "          AndroidBridge.saveBase64File(base64, name);" +
                        "        };" +
                        "        reader.readAsDataURL(blob);" +
                        "      });" +
                        "    }" +
                        "  }" +
                        "}, true);" +
                        "})()";
                    view.evaluateJavascript(hookDownload, null);
                }
            }
        });

        handleExternalFileIntent(getIntent());
        webView.loadUrl("file:///android_asset/index.html");
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

        // 🔥 3. 原生匯出寫入：寫入手機 Download 資料夾並跳出成功提示
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
                            "✅ 存檔已成功匯出至 Download 資料夾：\n" + fileName, Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this,
                            "❌ 匯出失敗：" + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
        }

        @JavascriptInterface
        public void saveGameData(String jsonText) {
            SharedPreferences sp = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            sp.edit().putString(KEY_SAVE_JSON, jsonText).apply();
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "✅ 角色進度已成功同步至手機原生儲存區！", Toast.LENGTH_SHORT).show());
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

        @JavascriptInterface
        public void saveCustomPluginUrls(String url1, String url2) {
            SharedPreferences sp = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            sp.edit().putString(KEY_PLUGIN_URL_1, url1)
                      .putString(KEY_PLUGIN_URL_2, url2).apply();
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "✅ 雙外掛腳本網址已成功更新！", Toast.LENGTH_SHORT).show());
        }

        @JavascriptInterface
        public String getCustomPluginUrl1() {
            return getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString(KEY_PLUGIN_URL_1, "");
        }

        @JavascriptInterface
        public String getCustomPluginUrl2() {
            return getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString(KEY_PLUGIN_URL_2, "");
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
