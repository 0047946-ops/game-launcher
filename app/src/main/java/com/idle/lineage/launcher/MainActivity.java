package com.example.idlelineageapp; // ⚠️ 請確認與您專案的 AndroidManifest.xml 套件名稱一致

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
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

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "IdleLineageApp";
    private static final String PREFS_NAME = "GamePrefs";
    private static final String KEY_CURRENT_VERSION = "current_version";
    private static final String GITHUB_RELEASE_API = "https://api.github.com/repos/pp771007/idle-lineage-class/releases/latest";

    // 🌐 我們研究的雙網址架構（原作者 / 加掛版）
    private static final String URL_ORIGINAL_GAME = "https://shines871.github.io/idle-lineage-class/";
    private static final String URL_MODDED_GAME = "https://pp771007.github.io/idle-lineage-class/";

    // 🚀 我們建置的三大 GitHub 雲端基地網址
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

    private static final String SAVE_NAME_PREFIX = "";

    @Keep
    public class WebAppInterface {
        @JavascriptInterface
        @Keep
        public void saveBase64File(String dataUrlOrBase64, String mimeType, String fileName) {
            Log.d(TAG, "🎯 [JS 觸發導出] 檔名: " + fileName + " | 長度: " + (dataUrlOrBase64 != null ? dataUrlOrBase64.length() : 0));
            runOnUiThread(() -> processAndSaveFile(dataUrlOrBase64, mimeType, fileName));
        }

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

        // 🛡️ 使用安全尋找資源的方式，確保就算 XML 稍微有落差也不會編譯失敗
        setContentView(getResId("layout", "activity_main"));

        webView = findViewById(getResId("id", "webView"));
        layoutLoading = findViewById(getResId("id", "layoutLoading"));
        progressBar = findViewById(getResId("id", "progressBar"));
        tvLoadingStatus = findViewById(getResId("id", "tvLoadingStatus"));

        // 若 xml 裡沒有 layoutLoading，則容錯建立 fallback WebView
        if (webView == null) {
            webView = new WebView(this);
            setContentView(webView);
        }

        gameDir = new File(getFilesDir(), "game");
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        initFileChooserLauncher();
        initCreateDocumentLauncher();

        setupWebView();

        executor.execute(() -> {
            loadGameInWebView();
            checkForUpdates();
        });
    }

    private int getResId(String resType, String resName) {
        try {
            return getResources().getIdentifier(resName, resType, getPackageName());
        } catch (Exception e) {
            return 0;
        }
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
                    Log.d(TAG, "頁面載入完成，開始注入我們的三大雲端基地腳本: " + url);
                    
                    // 🎯 自動雙向注入我們的靈魂腳本
                    injectRemoteScript(view, URL_SAVE_HOOK);
                    injectRemoteScript(view, URL_MASTER_ENGINE);
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
            Log.d(TAG, "讀取雲端基地 release.json 配置: " + URL_RELEASE_JSON);
            URL releaseUrl = new URL(URL_RELEASE_JSON);
            HttpURLConnection releaseConn = (HttpURLConnection) releaseUrl.openConnection();
            releaseConn.setConnectTimeout(3000);
            if (releaseConn.getResponseCode() == 200) {
                Log.d(TAG, "✅ 雲端基地連線正常，後勤腳本同步中");
            }

            Log.d(TAG, "檢查 GitHub Release 最新版本...");
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

                Log.d(TAG, "最新版本: " + latestVersion + " | 當前本地版本: " + currentVersion);

                if (!latestVersion.equals(currentVersion)) {
                    showLoadingUI("發現新版本 (" + latestVersion + ")，準備下載熱更新包...");

                    String zipUrl = json.getString("zipball_url");
                    File downloadedZip = new File(getCacheDir(), "update.zip");

                    if (downloadFileWithProgress(zipUrl, downloadedZip)) {
                        updateProgressUI("正在解壓縮遊戲資源，請稍候...", -1);
                        deleteRecursive(gameDir);
                        gameDir.mkdirs();
                        unzip(downloadedZip, gameDir);
                        downloadedZip.delete();

                        prefs.edit().putString(KEY_CURRENT_VERSION, latestVersion).apply();

                        showToast("熱更新完成！正在重新載入遊戲...");
                        loadGameInWebView();
                    } else {
                        showToast("熱更新下載失敗，將自動載入線上版本。");
                        hideLoadingUI();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "熱更新檢查發生異常", e);
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
                Log.e(TAG, "下載服務器返回錯誤碼: " + responseCode);
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
                            updateProgressUI("下載熱更新包中 (" + progress + "%)...", progress);
                        }
                    } else {
                        updateProgressUI("下載熱更新包中 (" + (total / 1024) + " KB)...", -1);
                    }
                }
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "下載檔案時發生例外", e);
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
