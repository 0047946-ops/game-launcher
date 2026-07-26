package com.example.idlelineageapp;

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
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.URLUtil;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Keep;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "IdleLineageApp";
    private static final String PREFS_NAME = "GamePrefs";
    private static final String KEY_CURRENT_VERSION = "current_version";
    private static final String GITHUB_RELEASE_API = "https://api.github.com/repos/pp771007/idle-lineage-class/releases/latest";

    // 🌐 [我們研究成果植入] 雙網址支援（原作者正式版 / 加掛擴充版）
    private static final String URL_ORIGINAL_GAME = "https://shines871.github.io/idle-lineage-class/";
    private static final String URL_MODDED_GAME = "https://pp771007.github.io/idle-lineage-class/";

    // 🚀 [我們研究成果植入] 三大 GitHub 雲端後勤基地網址
    private static final String URL_RELEASE_JSON = "https://raw.githubusercontent.com/0047946-ops/game-launcher/main/release.json";
    private static final String URL_SAVE_HOOK = "https://raw.githubusercontent.com/0047946-ops/game-launcher/main/save_hook.js";
    private static final String URL_MASTER_ENGINE = "https://raw.githubusercontent.com/0047946-ops/game-launcher/main/scripts/main.user.js";

    private WebView webView;
    private RelativeLayout layoutLoading;
    private ProgressBar progressBar;
    private TextView tvLoadingStatus;

    private File gameDir;
    private SharedPreferences prefs;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ValueCallback<Uri[]> filePathCallback;
    private ActivityResultLauncher<Intent> fileChooserLauncher;
    private ActivityResultLauncher<Intent> createDocumentLauncher;

    private byte[] pendingSaveBytes = null;
    private String pendingSaveFileName = null;

    /** 匯出檔名前綴。留空 = 不加前綴（檔名最短）；想加就填，例如 "放置天堂" */
    private static final String SAVE_NAME_PREFIX = "";
    /** assets/save_hook.js 的內容快取 */
    private String saveHookJs = null;

    @Keep
    public class WebAppInterface {
        @JavascriptInterface
        @Keep
        public void saveBase64File(String dataUrlOrBase64, String mimeType, String fileName) {
            Log.d(TAG, "🎯 [JS 觸發導出] 檔名: " + fileName + " | 長度: " + (dataUrlOrBase64 != null ? dataUrlOrBase64.length() : 0));
            runOnUiThread(() -> processAndSaveFile(dataUrlOrBase64, mimeType, fileName));
        }

        /** JS 端攔不到下載時，把 localStorage 裡找到的所有存檔丟回來讓玩家自己選 */
        @JavascriptInterface
        @Keep
        public void pickSaveSlot(String slotsJson) {
            runOnUiThread(() -> showSlotChooser(slotsJson));
        }

        @JavascriptInterface
        @Keep
        public void log(String message) {
            Log.d(TAG, "🌐 [SaveHook] " + message);
        }

        @JavascriptInterface
        @Keep
        public void toast(String message) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show());
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);
        layoutLoading = findViewById(R.id.layoutLoading);
        progressBar = findViewById(R.id.progressBar);
        tvLoadingStatus = findViewById(R.id.tvLoadingStatus);

        gameDir = new File(getFilesDir(), "game");
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        initFileChooserLauncher();
        initCreateDocumentLauncher();

        setupWebView();

        executor.execute(() -> {
            initGameAssetsIfNeeded();
            loadGameInWebView();
            checkForUpdates();
        });
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

            webView.addJavascriptInterface(new WebAppInterface(), "AndroidBridge");
            webView.addJavascriptInterface(new WebAppInterface(), "Android");

            webView.setWebChromeClient(new WebChromeClient() {
                @Override
                public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                    if (MainActivity.this.filePathCallback != null) {
                        MainActivity.this.filePathCallback.onReceiveValue(null);
                    }
                    MainActivity.this.filePathCallback = filePathCallback;
                    Intent intent = fileChooserParams.createIntent();
                    try {
                        fileChooserLauncher.launch(intent);
                    } catch (Exception e) {
                        MainActivity.this.filePathCallback = null;
                        return false;
                    }
                    return true;
                }

                @Override
                public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                    Log.d(TAG, "🌐 [Console] " + consoleMessage.message() + " -- Line: " + consoleMessage.lineNumber() + " of " + consoleMessage.sourceId());
                    return true;
                }
            });

            webView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    super.onPageFinished(view, url);
                    Log.d(TAG, "頁面載入完成: " + url);
                    
                    // 🎯 [我們研究成果植入] 自動注入雲端 SaveHook 與 Master Engine 擴充引擎
                    injectRemoteScript(view, URL_SAVE_HOOK);
                    injectRemoteScript(view, URL_MASTER_ENGINE);
                }
            });
        });
    }

    // 🎯 [我們研究成果植入] 動態腳本注入方法
    private void injectRemoteScript(WebView view, String scriptUrl) {
        String js = "javascript:(function(){" +
                "var s=document.createElement('script');" +
                "s.src='" + scriptUrl + "?v=" + System.currentTimeMillis() + "';" +
                "document.head.appendChild(s);})();";
        view.evaluateJavascript(js, null);
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

    private void initGameAssetsIfNeeded() {
        // 預留資產初始化
    }

    private void loadGameInWebView() {
        mainHandler.post(() -> {
            File indexFile = new File(gameDir, "index.html");
            if (indexFile.exists()) {
                Log.d(TAG, "優先載入本地熱更新解壓遊戲包");
                webView.loadUrl("file://" + indexFile.getAbsolutePath());
            } else {
                Log.d(TAG, "載入雲端加掛版遊戲網址: " + URL_MODDED_GAME);
                webView.loadUrl(URL_MODDED_GAME);
            }
        });
    }

    private void checkForUpdates() {
        try {
            // 🌐 [我們研究成果植入] 讀取雲端基地 release.json 配置
            Log.d(TAG, "檢查雲端基地 release.json 配置: " + URL_RELEASE_JSON);
            URL releaseUrl = new URL(URL_RELEASE_JSON);
            HttpURLConnection releaseConn = (HttpURLConnection) releaseUrl.openConnection();
            releaseConn.setConnectTimeout(3000);
            if (releaseConn.getResponseCode() == 200) {
                Log.d(TAG, "✅ 雲端基地連線正常");
            }

            Log.d(TAG, "檢查 GitHub 最新版本...");
            URL url = new URL(GITHUB_RELEASE_API);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "IdleLineageAndroidApp");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            if (conn.getResponseCode() == 200) {
                InputStream is = conn.getInputStream();
                java.util.Scanner s = new java.util.Scanner(is).useDelimiter("\\A");
                String response = s.hasNext() ? s.next() : "";
                JSONObject json = new JSONObject(response);

                String latestVersion = json.getString("tag_name");
                String currentVersion = prefs.getString(KEY_CURRENT_VERSION, "");

                Log.d(TAG, "最新版本: " + latestVersion + " | 當前版本: " + currentVersion);

                if (!latestVersion.equals(currentVersion)) {
                    showLoadingUI("發現新版本 (" + latestVersion + ")，準備下載...");

                    String zipUrl = json.getString("zipball_url");
                    File downloadedZip = new File(getCacheDir(), "update.zip");

                    if (downloadFileWithProgress(zipUrl, downloadedZip)) {
                        updateProgressUI("正在解壓縮遊戲資源，請稍候...", -1);
                        deleteRecursive(gameDir);
                        gameDir.mkdirs();
                        unzip(downloadedZip, gameDir);
                        downloadedZip.delete();

                        prefs.edit().putString(KEY_CURRENT_VERSION, latestVersion).apply();

                        showToast("更新完成！正在載入新版遊戲...");
                        loadGameInWebView();
                    } else {
                        showToast("更新下載失敗，將繼續使用現有版本。");
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

            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "伺服器回傳 HTTP 錯誤: " + responseCode);
                return false;
            }

            int fileLength = conn.getContentLength();

            try (InputStream is = conn.getInputStream();
                 FileOutputStream fos = new FileOutputStream(outputFile)) {
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
                            updateProgressUI("下載更新包中 (" + progress + "%)...", progress);
                        }
                    } else {
                        updateProgressUI("下載更新包中 (" + (total / 1024) + " KB)...", -1);
                    }
                }
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "下載更新包過程發生異常", e);
            return false;
        }
    }

    private void initFileChooserLauncher() {
        fileChooserLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (filePathCallback == null) return;
                    Uri[] results = null;
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        String dataString = result.getData().getDataString();
                        if (dataString != null) {
                            results = new Uri[]{Uri.parse(dataString)};
                        }
                    }
                    filePathCallback.onReceiveValue(results);
                    filePathCallback = null;
                }
        );
    }

    private void initCreateDocumentLauncher() {
        createDocumentLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri != null && pendingSaveBytes != null) {
                            saveBytesToUri(uri, pendingSaveBytes);
                        }
                    }
                    pendingSaveBytes = null;
                    pendingSaveFileName = null;
                }
        );
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
            String finalFileName = SAVE_NAME_PREFIX + (fileName != null ? fileName : "idle_save.json");
            pendingSaveFileName = finalFileName;

            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType(mimeType != null ? mimeType : "application/json");
            intent.putExtra(Intent.EXTRA_TITLE, finalFileName);

            createDocumentLauncher.launch(intent);
        } catch (Exception e) {
            Log.e(TAG, "解析導出資料失敗", e);
            Toast.makeText(this, "❌ 導出失敗: " + e.getMessage(), Toast.LENGTH_LONG).show();
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
            Log.e(TAG, "寫入檔案失敗", e);
            Toast.makeText(this, "❌ 寫入檔案失敗: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void showSlotChooser(String slotsJson) {
        try {
            JSONArray arr = new JSONArray(slotsJson);
            String[] items = new String[arr.length()];
            for (int i = 0; i < arr.length(); i++) {
                items[i] = arr.getString(i);
            }

            new AlertDialog.Builder(this)
                    .setTitle("選擇要導出的 LocalStorage 存檔 key")
                    .setItems(items, (dialog, which) -> {
                        String selectedKey = items[which];
                        String js = "window.__dumpSingleKey('" + selectedKey + "')";
                        webView.evaluateJavascript(js, null);
                    })
                    .setNegativeButton("取消", null)
                    .show();
        } catch (Exception e) {
            Log.e(TAG, "解析存檔清單失敗", e);
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
}
