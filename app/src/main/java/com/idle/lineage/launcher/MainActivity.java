package com.idle.lineage.launcher;

import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ContentValues;
import android.content.Intent;
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
import android.widget.Toast;

import androidx.annotation.Keep;
import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "IdleLineageLauncher";

    // ==================== 存檔匯出名前綴 ====================
    /** 匯出檔名前綴。留空 = 不加前綴（檔名最短）；想加就填，例如 "放置天堂" */
    private static final String SAVE_NAME_PREFIX = "";

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private final static int FILE_CHOOSER_RESULT_CODE = 10001;

    private ActivityResultLauncher<Intent> fileChooserLauncher;
    private ActivityResultLauncher<Intent> createDocumentLauncher;

    private byte[] pendingSaveBytes = null;
    private String pendingSaveFileName = null;

    @Keep
    public class AndroidBridge {

        @JavascriptInterface
        @Keep
        public void saveBase64File(String dataUrlOrBase64, String mimeType, String fileName) {
            Log.d(TAG, "🎯 [JS 觸發導出] 檔名: " + fileName + " | 長度: " + (dataUrlOrBase64 != null ? dataUrlOrBase64.length() : 0));
            runOnUiThread(() -> processAndSaveFile(dataUrlOrBase64, mimeType, fileName));
        }

        /** 舊版介面（保留相容）：JS 端只傳 base64 + 檔名 */
        @JavascriptInterface
        @Keep
        public void saveBase64FileLegacy(String base64Data, String fileName) {
            Log.d(TAG, "📦 [舊版 JS 觸發導出] 檔名: " + fileName);
            runOnUiThread(() -> processAndSaveFile(base64Data, "application/json", fileName));
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

        checkAllFilesAccessPermission();

        webView = new WebView(this);
        setContentView(webView);

        // 初始化新版 ActivityResultLauncher
        initFileChooserLauncher();
        initCreateDocumentLauncher();

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
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);

        // 允許背景播放音訊（防止 Android WebView 自動凍結 AudioContext 離線背景流）
        settings.setMediaPlaybackRequiresUserGesture(false);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");

        webView.setWebChromeClient(new WebChromeClient() {

            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                Log.w(TAG, "🌐 [JS Console] " + consoleMessage.message());
                return true;
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
                                              FileChooserParams fileChooserParams) {
                if (MainActivity.this.filePathCallback != null) {
                    MainActivity.this.filePathCallback.onReceiveValue(null);
                }
                MainActivity.this.filePathCallback = filePathCallback;

                Intent intent = fileChooserParams.createIntent();
                try {
                    // 優先使用新版 ActivityResultLauncher
                    fileChooserLauncher.launch(intent);
                } catch (Exception e) {
                    MainActivity.this.filePathCallback = null;
                    return false;
                }
                return true;
            }

            /** 攔截 prompt 中的 SIG1 存檔訊號 */
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

            /** 攔截 alert 中的 SIG1 存檔訊號 */
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

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.startsWith("data:")) {
                    processAndSaveFile(url, "application/json", null);
                    return true;
                }
                if (url.startsWith("blob:")) {
                    // blob: 的內容只有網頁端讀得到，交給 JS 端處理
                    Log.d(TAG, "攔到 blob: 連結，改由 JS 端處理: " + url);
                    triggerBlobDownload(url);
                    return true;
                }
                return false;
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                Toast.makeText(MainActivity.this, "⚠️ 網址連線失敗 (" + errorCode + ")，請確認網路狀態", Toast.LENGTH_LONG).show();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);

                if (url == null) return;

                // ===== 所有頁面都注入：存檔匯入修復 + 存檔攔截 Hook =====
                // 3. 核心檔案讀取修復：確保匯入不同來源/外掛格式的存檔時能自動解析相容
                String fixImportJs =
                    "(function(){" +
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
                view.evaluateJavascript(fixImportJs, null);

                // 4. 注入存檔攔截 Hook（所有頁面都注入，包含人物選擇畫面）
                String saveHookJs = buildSaveHookJs();
                view.evaluateJavascript(saveHookJs, null);

                // ===== 只有 HTTP 遊戲頁面才注入外掛 + 防斷線 =====
                if (url.startsWith("http")) {
                    // 1. 自動注入【10 大外掛模組】(含主外掛 + 9個書籤模組)
                    String totalPluginJs = "(function () {" +
                            "'use strict';" +
                            "if(window.__all_plugins_loaded) return;" +
                            "window.__all_plugins_loaded = true;" +

                            // 先載入原本的主外掛
                            "var s0 = document.createElement('script');" +
                            "s0.src = 'https://cdn.jsdelivr.net/gh/qcc781192000/idle-lineage-plugin@main/main.user.js?v=' + Date.now();" +
                            "document.body.appendChild(s0);" +

                            // 接著依序載入 9 個書籤模組
                            "const b = 'https://kid0924.github.io/idle-lineage-class/';" +
                            "const t = window.location.hostname.includes('pp771007');" +
                            "const c = ['klh_initial.js','klh_GMShop.js','klh_mobile-perf.js','klh_perf-monitor.js','klh_Backpack.js','klh_pk.js','klh_Pandora.js'].map(x => b + x);" +
                            "const n = t ? [...[b+'klh_remove-banner.js'],...c] : [...['https://pp771007.github.io/idle-lineage-class/afk-lzcache.js', 'https://pp771007.github.io/idle-lineage-class/afk-offline.js'],...c];" +
                            "function s(e, t) {" +
                            "    const node = document.createElement('div');" +
                            "    node.textContent = e;" +
                            "    node.style.cssText = 'position:fixed;top:20px;right:20px;background:' + (t ? '#2ecc71' : '#e74c3c') + ';color:white;padding:12px 24px;border-radius:8px;z-index:99999;font-family:sans-serif;box-shadow:0 4px 12px rgba(0,0,0,0.15);transition:opacity 0.5s';" +
                            "    document.body.appendChild(node);" +
                            "    setTimeout(() => {" +
                            "        node.style.opacity = '0';" +
                            "        setTimeout(() => node.remove(), 500);" +
                            "    }, 2500);" +
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
                            "    .then(() => { s('🎉 【10大外掛模組】全部注入成功！', !0); })" +
                            "    .catch(r => {" +
                            "        const f = (r && typeof r === 'string') ? r.split('/').pop().split('?')[0] : '';" +
                            "        s('❌ 載入失敗' + (f ? '：' + f : '！'), !1);" +
                            "    });" +
                            "})();";
                    view.evaluateJavascript(totalPluginJs, null);

                    // 2. 自動注入【TMEngine v106.0 全域極致整合防斷線引擎】
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

                            "const NetworkOptimizer = {" +
                            "    _isMobile: false," +
                            "    detectEnvironment: async () => {" +
                            "        const conn = navigator.connection || {};" +
                            "        NetworkOptimizer._isMobile = conn.type === 'cellular' || /Android|webOS|iPhone|iPad/i.test(navigator.userAgent);" +
                            "        try {" +
                            "            const start = Date.now();" +
                            "            await fetch(window.location.href, { method: 'HEAD', cache: 'no-cache' });" +
                            "            const rtt = Date.now() - start;" +
                            "            if (rtt > 150) NetworkOptimizer._isMobile = true;" +
                            "        } catch (e) {}" +
                            "    }," +
                            "    getJitterParams: () => {" +
                            "        return NetworkOptimizer._isMobile ? { base: 500, variance: 700 } : { base: 120, variance: 250 };" +
                            "    }" +
                            "};" +

                            "const DOMWatcher = {" +
                            "    waitForEl: (selector, success) => {" +
                            "        const el = document.querySelector(selector);" +
                            "        if (el) { success(el); return; }" +
                            "        const obs = new MutationObserver((mutations, obs) => {" +
                            "            const target = document.querySelector(selector);" +
                            "            if (target) { obs.disconnect(); success(target); }" +
                            "        });" +
                            "        if (document.body) {" +
                            "            obs.observe(document.body, { childList: true, subtree: true });" +
                            "        } else {" +
                            "            document.addEventListener('DOMContentLoaded', () => {" +
                            "                obs.observe(document.body, { childList: true, subtree: true });" +
                            "            });" +
                            "        }" +
                            "    }" +
                            "};" +

                            "const GuildInterfaceOptimizer = {" +
                            "    isGuildActive: () => {" +
                            "        const guildPanel = document.querySelector('.guild-interface, .blood-pledge-panel, [data-view=\"guild\"]');" +
                            "        return guildPanel !== null && guildPanel.offsetParent !== null;" +
                            "    }," +
                            "    executeGuildLogic: () => {" +
                            "        if (!GuildInterfaceOptimizer.isGuildActive()) return;" +
                            "        const checkInBtn = document.querySelector('.guild-checkin-btn:not(.completed)');" +
                            "        if (checkInBtn) {" +
                            "            setTimeout(() => checkInBtn.click(), PerformanceCore.getJitter(500, 1000));" +
                            "        }" +
                            "        const donateBtn = document.querySelector('.guild-donate-confirm');" +
                            "        if (donateBtn && Math.random() > 0.95) {" +
                            "            setTimeout(() => donateBtn.click(), PerformanceCore.getJitter(800, 1500));" +
                            "        }" +
                            "    }" +
                            "};" +

                            "window.executeLogic = function() {" +
                            "    if (GuildInterfaceOptimizer.isGuildActive()) {" +
                            "        GuildInterfaceOptimizer.executeGuildLogic();" +
                            "        return;" +
                            "    }" +
                            "    const hpText = document.querySelector('.hp-text')?.innerText;" +
                            "    if (hpText) {" +
                            "        const [cur, max] = hpText.split('/').map(Number);" +
                            "        if (cur / max < 0.75) {" +
                            "            const potionBtn = document.querySelector('#btn-use-potion') || document.querySelector('.potion-btn');" +
                            "            if (potionBtn) potionBtn.click();" +
                            "        }" +
                            "    }" +
                            "    const attackBtn = document.querySelector('.attack-btn');" +
                            "    if (attackBtn && !attackBtn.classList.contains('cooldown')) {" +
                            "        const { base, variance } = NetworkOptimizer.getJitterParams();" +
                            "        setTimeout(() => attackBtn.click(), PerformanceCore.getJitter(base, variance));" +
                            "    }" +
                            "    const buffs = [" +
                            "        { selector: '.status-haste', btn: '#btn-use-haste-potion' }," +
                            "        { selector: '.status-shield', btn: '#btn-use-shield' }," +
                            "        { selector: '.status-holy-weapon', btn: '#btn-use-holy-weapon' }," +
                            "        { selector: '.status-berserk', btn: '#btn-use-berserk' }" +
                            "    ];" +
                            "    buffs.forEach(buff => {" +
                            "        if (document.querySelector(buff.selector) === null) {" +
                            "            const targetBtn = document.querySelector(buff.btn);" +
                            "            if (targetBtn && Math.random() > 0.8) {" +
                            "                setTimeout(() => targetBtn.click(), PerformanceCore.getJitter(400, 800));" +
                            "            }" +
                            "        }" +
                            "    });" +
                            "    if (Math.random() > 0.995) {" +
                            "        const sellBtn = document.querySelector('#btn-sell-all-waste');" +
                            "        if (sellBtn && sellBtn.offsetParent !== null) sellBtn.click();" +
                            "    }" +
                            "};" +

                            "const PageVisibilityModule = {" +
                            "    init: () => {" +
                            "        document.addEventListener('visibilitychange', () => {" +
                            "            if (!document.hidden && typeof window.executeLogic === 'function') {" +
                            "                window.executeLogic();" +
                            "            }" +
                            "        });" +
                            "    }" +
                            "};" +

                            "const HeartbeatModule = {" +
                            "    sendKeepAliveSignal: () => {" +
                            "        if (window.socket && window.socket.readyState === WebSocket.OPEN) {" +
                            "            window.socket.send(JSON.stringify({ type: 'heartbeat', timestamp: Date.now() }));" +
                            "        } else {" +
                            "            fetch(window.location.href, { method: 'HEAD', cache: 'no-cache', keepalive: true }).catch(() => {});" +
                            "        }" +
                            "    }" +
                            "};" +

                            "const WebWorkerModule = {" +
                            "    init: () => {" +
                            "        if (!window.Worker) return;" +
                            "        const workerCode = `let intervalId = null;" +
                            "        self.onmessage = function(e) {" +
                            "            if (e.data === 'start') {" +
                            "                if (intervalId) clearInterval(intervalId);" +
                            "                intervalId = setInterval(() => { self.postMessage('ping'); }, 1000);" +
                            "            } else if (e.data === 'stop') {" +
                            "                if (intervalId) clearInterval(intervalId);" +
                            "            }" +
                            "        };`;" +
                            "        const blob = new Blob([workerCode], { type: 'application/javascript' });" +
                            "        const workerUrl = URL.createObjectURL(blob);" +
                            "        const worker = new Worker(workerUrl);" +
                            "        worker.postMessage('start');" +
                            "        worker.onmessage = function(e) {" +
                            "            if (e.data === 'ping') HeartbeatModule.sendKeepAliveSignal();" +
                            "        };" +
                            "    }" +
                            "};" +

                            "const AudioKeepAliveModule = {" +
                            "    silentAudioCtx: null," +
                            "    init: () => {" +
                            "        try {" +
                            "            const AudioContext = window.AudioContext || window.webkitAudioContext;" +
                            "            if (!AudioContext) return;" +
                            "            AudioKeepAliveModule.silentAudioCtx = new AudioContext();" +
                            "            const oscillator = AudioKeepAliveModule.silentAudioCtx.createOscillator();" +
                            "            const gainNode = AudioKeepAliveModule.silentAudioCtx.createGain();" +
                            "            gainNode.gain.value = 0.0001;" +
                            "            oscillator.connect(gainNode);" +
                            "            gainNode.connect(AudioKeepAliveModule.silentAudioCtx.destination);" +
                            "            oscillator.start();" +
                            "            document.addEventListener('visibilitychange', () => {" +
                            "                if (AudioKeepAliveModule.silentAudioCtx && AudioKeepAliveModule.silentAudioCtx.state === 'suspended') {" +
                            "                    AudioKeepAliveModule.silentAudioCtx.resume();" +
                            "                }" +
                            "            });" +
                            "        } catch (e) {}" +
                            "    }" +
                            "};" +

                            "const initSystem = async () => {" +
                            "    await NetworkOptimizer.detectEnvironment();" +
                            "    PageVisibilityModule.init();" +
                            "    WebWorkerModule.init();" +
                            "    AudioKeepAliveModule.init();" +

                            "    const div = document.createElement('div');" +
                            "    div.style = 'position:fixed; top:10px; left:10px; background:rgba(0,0,0,0.85); color:#0f0; padding:10px; z-index:2147483647; border-radius:8px; font-size:11px; border:1px solid #0f0; pointer-events:none;';" +
                            "    div.innerHTML = `<div style=\"font-weight:bold;\">【TMEngine v106.0】全域極致整合防斷線版</div><div>● 背景抗凍結：四大模組運行中</div><div>● 模式：${NetworkOptimizer._isMobile ? '手機動態適配' : 'WIFI高速運行'}</div>`;" +

                            "    const attachUI = () => {" +
                            "        if (document.body) document.body.appendChild(div);" +
                            "        else setTimeout(attachUI, 100);" +
                            "    };" +
                            "    attachUI();" +

                            "    DOMWatcher.waitForEl('.attack-btn', () => {" +
                            "        setInterval(window.executeLogic, 250);" +
                            "    });" +
                            "};" +
                            "initSystem();" +
                            "})();";
                    view.evaluateJavascript(tmEngineJs, null);
                }
            }
        });

        // 監聽原生下載（處理標準 Blob 與一般存檔下載）
        webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            try {
                String suggested = URLUtil.guessFileName(url, contentDisposition, mimetype);
                runOnUiThread(() -> processAndSaveFile(url, mimetype, suggested));
            } catch (Exception e) {
                Toast.makeText(this, "❌ 下載失敗：" + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });

        loadNativeLauncherHtml();
    }

    // ==================== ActivityResultLauncher 初始化 ====================

    private void initFileChooserLauncher() {
        fileChooserLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (filePathCallback == null) return;
                    Uri[] results = null;
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Intent dataIntent = result.getData();
                        if (dataIntent.getData() != null) {
                            results = new Uri[]{dataIntent.getData()};
                        } else if (dataIntent.getClipData() != null) {
                            int count = dataIntent.getClipData().getItemCount();
                            results = new Uri[count];
                            for (int i = 0; i < count; i++) {
                                results[i] = dataIntent.getClipData().getItemAt(i).getUri();
                            }
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
                    if (result.getResultCode() == RESULT_OK && result.getData() != null && result.getData().getData() != null) {
                        Uri uri = result.getData().getData();
                        if (pendingSaveBytes != null) {
                            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                                if (os != null) {
                                    os.write(pendingSaveBytes);
                                    os.flush();
                                    Toast.makeText(MainActivity.this, "✅ 檔案已成功儲存！", Toast.LENGTH_SHORT).show();
                                }
                            } catch (Exception e) {
                                showDebugDialog("❌ SAF 寫入失敗", e.getMessage());
                            } finally {
                                pendingSaveBytes = null;
                                pendingSaveFileName = null;
                            }
                        }
                    } else {
                        pendingSaveBytes = null;
                        pendingSaveFileName = null;
                    }
                }
        );
    }

    // ==================== 存檔處理核心 ====================

    /**
     * 統一處理各種格式的存檔資料並匯出到 Download 資料夾。
     * 支援格式：SIG1、data URI、base64、純 JSON、純文字。
     */
    private void processAndSaveFile(String dataUrlOrBase64, String mimeType, String fileName) {
        if (dataUrlOrBase64 == null || dataUrlOrBase64.isEmpty()) {
            return;
        }

        // blob: 只是一個指標，Java 這端讀不到內容，交給 JS 端處理
        if (dataUrlOrBase64.startsWith("blob:")) {
            Log.w(TAG, "收到 blob: 網址，Java 端無法讀取，略過: " + dataUrlOrBase64);
            return;
        }

        try {
            byte[] bytes;

            if (dataUrlOrBase64.contains("SIG1:")) {
                String sigData = dataUrlOrBase64.substring(dataUrlOrBase64.indexOf("SIG1:")).trim();
                bytes = sigData.getBytes(StandardCharsets.UTF_8);
            } else if (dataUrlOrBase64.trim().startsWith("{") || dataUrlOrBase64.trim().startsWith("[")) {
                // 純 JSON 文字
                bytes = dataUrlOrBase64.trim().getBytes(StandardCharsets.UTF_8);
            } else if (dataUrlOrBase64.startsWith("data:")) {
                int commaIndex = dataUrlOrBase64.indexOf(",");
                if (commaIndex != -1) {
                    String header = dataUrlOrBase64.substring(0, commaIndex);
                    String content = dataUrlOrBase64.substring(commaIndex + 1);

                    if (header.contains(";base64")) {
                        bytes = Base64.decode(content, Base64.DEFAULT);
                    } else {
                        String decodedText = java.net.URLDecoder.decode(content, "UTF-8");
                        bytes = decodedText.getBytes(StandardCharsets.UTF_8);
                    }
                } else {
                    bytes = dataUrlOrBase64.getBytes(StandardCharsets.UTF_8);
                }
            } else if (dataUrlOrBase64.matches("[A-Za-z0-9+/=\\r\\n]{16,}")) {
                // 只有「整串都是 base64 字元」才解碼
                try {
                    bytes = Base64.decode(dataUrlOrBase64, Base64.DEFAULT);
                } catch (Exception e) {
                    bytes = dataUrlOrBase64.getBytes(StandardCharsets.UTF_8);
                }
            } else {
                bytes = dataUrlOrBase64.getBytes(StandardCharsets.UTF_8);
            }

            // 組出「看得懂是誰的存檔」的檔名
            fileName = buildSaveFileName(fileName, bytes);
            Log.d(TAG, "最終檔名: " + fileName);

            if (writeToDownloads(bytes, fileName, "application/json")) {
                Toast.makeText(MainActivity.this, "✅ 角色存檔已成功匯出至 Download 資料夾：\n" + fileName, Toast.LENGTH_LONG).show();
                notifyJsExported();
            } else {
                saveViaSAF(bytes, fileName);
            }

        } catch (Exception e) {
            Toast.makeText(this, "❌ 資料解析異常：" + e.getMessage(), Toast.LENGTH_LONG).show();
            Log.e(TAG, "processAndSaveFile 異常", e);
        }
    }

    /** 把資料寫進「下載」資料夾，成功回傳 true */
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

        // Android 9 以下：直接寫入
        try {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!dir.exists()) dir.mkdirs();
            File outFile = new File(dir, fileName);
            FileOutputStream fos = new FileOutputStream(outFile);
            fos.write(bytes);
            fos.close();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Direct Write 寫入失敗: " + e.getMessage());
            return false;
        }
    }

    /** 告訴網頁端「已經存好了」 */
    private void notifyJsExported() {
        runOnUiThread(() -> {
            try {
                webView.evaluateJavascript("window.__markExported && window.__markExported();", null);
            } catch (Exception ignored) {
            }
        });
    }

    /** SAF 備援：當 MediaStore 失敗時用 SAF 讓用戶選擇儲存位置 */
    private void saveViaSAF(byte[] bytes, String fileName) {
        this.pendingSaveBytes = bytes;
        this.pendingSaveFileName = fileName;

        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, fileName);

        try {
            createDocumentLauncher.launch(intent);
        } catch (Exception e) {
            shareSaveFile(bytes, fileName);
        }
    }

    /** 最後備援：用 Share 分享選單讓用戶選儲存方式 */
    private void shareSaveFile(byte[] data, String fileName) {
        try {
            File cacheFile = new File(getCacheDir(), fileName);
            try (FileOutputStream fos = new FileOutputStream(cacheFile)) {
                fos.write(data);
                fos.flush();
            }

            Uri contentUri = androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", cacheFile);

            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("application/json");
            shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            startActivity(Intent.createChooser(shareIntent, "儲存遊戲存檔: " + fileName));
        } catch (Exception e) {
            showDebugDialog("❌ Share 分享選單失敗", e.getMessage());
        }
    }

    // ==================== 檔名處理 ====================

    /**
     * 決定匯出檔名。優先順序：
     * 1. JS 端傳來的檔名
     * 2. Java 端自己從存檔內容解析等級與職業
     * 3. 都失敗才用「存檔_時間」
     */
    private String buildSaveFileName(String rawName, byte[] bytes) {
        String base = rawName == null ? "" : rawName.trim();

        // 去掉副檔名
        base = base.replaceAll("(?i)\\.(json|txt|sav|dat|bin)$", "").trim();

        // 罐頭檔名視為沒給
        if (base.matches("(?i)(idle[_-]?lineage[_-]?save|save|savefile|download|downloadfile|export|progress|存檔|下載|進度|未命名|fable5_save_\\d+)?")) {
            base = "";
        }
        if (base.isEmpty()) {
            base = extractCharInfo(bytes);
        }
        if (base.isEmpty()) {
            base = "存檔_" + timestamp();
        }
        if (!SAVE_NAME_PREFIX.isEmpty() && !base.startsWith(SAVE_NAME_PREFIX)) {
            base = SAVE_NAME_PREFIX + "_" + base;
        }
        return sanitizeFileName(base) + ".json";
    }

    /** 職業代號對照中文 */
    private String mapClass(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String v = raw.trim();

        // 已經是中文就直接用
        if (v.matches(".*[\u4e00-\u9fff].*")) {
            return v.length() > 6 ? v.substring(0, 6) : v;
        }

        String[] order = {"王子", "騎士", "法師", "妖精", "黑暗妖精", "幻術士", "龍騎士", "戰士"};
        if (v.matches("\\d{1,2}")) {
            int i = Integer.parseInt(v);
            if (i == 0) return order[0];
            return (i <= order.length) ? order[i - 1] : "";
        }

        String k = v.toLowerCase().replaceAll("[\\s_\\-]", "");
        switch (k) {
            case "prince": case "royal": case "king": case "royalty": return "王子";
            case "knight": case "kn": return "騎士";
            case "mage": case "wizard": case "wiz": return "法師";
            case "elf": return "妖精";
            case "darkelf": case "de": return "黑暗妖精";
            case "illusionist": case "illusion": case "il": return "幻術士";
            case "dragonknight": case "dk": return "龍騎士";
            case "warrior": case "fighter": case "wa": return "戰士";
            default: return "";
        }
    }

    /** 從存檔內容組出「56等騎士」；讀不到就退回角色名 */
    private String extractCharInfo(byte[] bytes) {
        try {
            String text = new String(bytes, StandardCharsets.UTF_8);
            String probe = text;

            // 實際格式：SIG1:<hash>.<hash>.<長度>:<JSON>
            int i = text.indexOf("SIG1:");
            if (i >= 0) {
                String body = text.substring(i + 5).trim();
                int colon = body.indexOf(':');
                if (colon >= 0) body = body.substring(colon + 1).trim();

                if (body.startsWith("{") || body.startsWith("[")) {
                    probe = body;
                } else {
                    // 保險：舊格式可能是 base64
                    String b64 = body.split("[.|,;\\s]")[0];
                    try {
                        String decoded = new String(Base64.decode(b64, Base64.DEFAULT), StandardCharsets.UTF_8);
                        if (decoded.contains("{")) probe = decoded;
                    } catch (Exception ignored) {
                    }
                }
            }

            // LZ1: 是 LZString 壓縮，Java 這端解不開
            if (probe.startsWith("LZ1:")) {
                Log.d(TAG, "內容是 LZ1 壓縮格式，Java 端不解析");
                return "";
            }

            // 盡量只在玩家資料區塊裡找
            String scope = probe;
            Matcher pm = Pattern.compile("\"p\"\\s*:\\s*\\{").matcher(probe);
            if (pm.find()) {
                int from = pm.start();
                scope = probe.substring(from, Math.min(from + 3000, probe.length()));
            }

            String level = firstNumber(scope, new String[]{"charLevel", "level", "lv", "lvl"});
            if (level.isEmpty()) level = firstNumber(probe, new String[]{"charLevel", "level", "lv", "lvl"});

            String rawClass = firstMatch(scope, new String[]{"cls", "class", "charClass", "className", "job", "career"});
            if (rawClass.isEmpty()) rawClass = firstNumber(scope, new String[]{"cls", "class", "classId", "job"});
            String cls = mapClass(rawClass);
            if (cls.isEmpty()) {
                // 職業代號認不出來時，用 avatar 欄位補
                String avatar = firstMatch(scope, new String[]{"avatar"});
                if (!avatar.isEmpty()) {
                    cls = avatar.replaceAll("^[男女]", "");
                    if (cls.length() > 6) cls = cls.substring(0, 6);
                }
            }

            String out = "";
            if (!level.isEmpty()) out += level + "等";
            if (!cls.isEmpty()) out += cls;
            if (!out.isEmpty()) return out;

            return firstMatch(scope, new String[]{
                    "charName", "characterName", "playerName", "nickName", "nickname", "cname", "charname", "name"});
        } catch (Exception e) {
            Log.w(TAG, "解析角色資訊失敗: " + e.getMessage());
            return "";
        }
    }

    private String firstMatch(String text, String[] keys) {
        for (String key : keys) {
            try {
                Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"\\\\]{1,24})\"").matcher(text);
                if (m.find()) {
                    String v = m.group(1).trim();
                    if (!v.isEmpty()) return v;
                }
            } catch (Exception ignored) {
            }
        }
        return "";
    }

    private String firstNumber(String text, String[] keys) {
        for (String key : keys) {
            try {
                Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(\\d{1,3})").matcher(text);
                if (m.find()) return m.group(1);
            } catch (Exception ignored) {
            }
        }
        return "";
    }

    /** 清掉檔名不能用的字元（中文可以保留） */
    private String sanitizeFileName(String name) {
        String out = name.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t\\x00-\\x1f]", "_")
                .replaceAll("_{2,}", "_")
                .replaceAll("^[._]+", "")
                .trim();
        if (out.length() > 80) out = out.substring(0, 80);
        return out.isEmpty() ? "存檔" : out;
    }

    private String timestamp() {
        return new java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.TAIWAN)
                .format(new java.util.Date());
    }

    // ==================== 存檔欄位選擇器 ====================

    private void showSlotChooser(String slotsJson) {
        try {
            org.json.JSONArray arr = new org.json.JSONArray(slotsJson);
            if (arr.length() == 0) {
                new AlertDialog.Builder(this)
                        .setTitle("找不到任何存檔")
                        .setMessage("網頁端沒有回報任何存檔欄位。")
                        .setPositiveButton("關閉", null)
                        .setNeutralButton("🔍 診斷", (d, w) -> dumpStorageDiagnostics())
                        .show();
                return;
            }

            final String[] keys = new String[arr.length()];
            final String[] labels = new String[arr.length()];
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.getJSONObject(i);
                keys[i] = o.optString("key");
                String label = o.optString("label");
                if (label.isEmpty()) label = keys[i];
                labels[i] = label;
            }

            new AlertDialog.Builder(this)
                    .setTitle("要匯出哪一個角色？")
                    .setItems(labels, (dialog, which) -> {
                        String js = "window.__exportSlotByKey && window.__exportSlotByKey("
                                + org.json.JSONObject.quote(keys[which]) + ");";
                        webView.evaluateJavascript(js, null);
                    })
                    .setNegativeButton("取消", null)
                    .setNeutralButton("🔍 診斷", (d, w) -> dumpStorageDiagnostics())
                    .show();
        } catch (Exception e) {
            showDebugDialog("❌ 讀取存檔清單失敗", e.toString());
        }
    }

    /** 診斷：把 localStorage 實際長相存成 txt 放進下載資料夾 */
    private void dumpStorageDiagnostics() {
        webView.evaluateJavascript("window.__dumpStorage ? window.__dumpStorage() : 'no hook'", value -> {
            String text;
            try {
                Object parsed = new org.json.JSONTokener(value).nextValue();
                text = String.valueOf(parsed);
            } catch (Exception e) {
                text = value;
            }

            String name = "存檔診斷_" + timestamp() + ".txt";
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            boolean ok = writeToDownloads(bytes, name, "text/plain");

            Log.d(TAG, "===== 存檔診斷 =====\n" + text);
            String head = text.length() > 1500 ? text.substring(0, 1500) + "\n…（完整內容看下載的檔案）" : text;
            showDebugDialog(ok ? "診斷已存成 " + name : "診斷（存檔失敗，內容如下）", head);
        });
    }

    // ==================== 存檔攔截 Hook JS ====================

    /** 建構存檔攔截的 JS 程式碼，注入到網頁端。
     *  此 Hook 在所有頁面（包含人物選擇畫面）都會注入，
     *  確保無論在哪個畫面操作匯出/匯入都能正常運作。
     */
    private String buildSaveHookJs() {
        return "(function(){" +
                "if(window.__save_hook_active) return;" +
                "window.__save_hook_active = true;" +

                "const SLOT_KEYS = [" +
                "    'afk-lzcache-save','idle-lineage-save','save','savefile','saveFile'," +
                "    'gameData','game_data','idle_lineage_save','playerData','player_data'," +
                "    'characterData','character_data','progress','idleLineageSave'," +
                "    'gameSave','idleLzSave','idleLineageSave_v2','idleLineageSave_v3'," +
                "    'idleLineageSave_v4','idleLineageSave_v5','idleLineageSave_v6'," +
                "    'idleLineageSave_v7','idleLineageSave_v8','idleLineageSave_v9'," +
                "    'idleLineageSave_v10','idleLineageSave_v11','idleLineageSave_v12'" +
                "];" +

                "const CLASS_MAP = {" +
                "    '0':'王子','1':'騎士','2':'法師','3':'妖精','4':'黑暗妖精'," +
                "    '5':'幻術士','6':'龍騎士','7':'戰士'," +
                "    'prince':'王子','knight':'騎士','mage':'法師','elf':'妖精'," +
                "    'darkelf':'黑暗妖精','de':'黑暗妖精','illusionist':'幻術士'," +
                "    'il':'幻術士','dragonknight':'龍騎士','dk':'龍騎士'," +
                "    'warrior':'戰士','fighter':'戰士','wa':'戰士'" +
                "};" +

                ";let _exported = false;" +
                "window.__markExported = function(){ _exported = true; };" +

                // ===== 攔截 createElement('a') 下載（blob: + data: 都行） =====
                "const origCreateElement = document.createElement.bind(document);" +
                "document.createElement = function(tag){" +
                "    const el = origCreateElement(tag);" +
                "    if(tag === 'a'){" +
                "        const origClick = el.click;" +
                "        el.click = function(){" +
                "            const href = el.href || el.getAttribute('href');" +
                "            const dl = el.download || '';" +
                "            if(href && (href.startsWith('data:') || href.startsWith('blob:')) && (dl.includes('json') || dl.includes('save') || dl.includes('export') || dl.includes('import'))" +
                "                || (href && href.startsWith('data:application/json'))" +
                "                || (href && href.startsWith('data:text/json'))){ " +
                "                if(href.startsWith('blob:')){" +
                "                    // blob: 先讀取內容再轉為 data URI 傳給 Java 端" +
                "                    var xhr=new XMLHttpRequest();" +
                "                    xhr.open('GET',href,true);" +
                "                    xhr.responseType='blob';" +
                "                    xhr.onload=function(){" +
                "                        var reader=new FileReader();" +
                "                        reader.onloadend=function(){" +
                "                            var dataUri=reader.result;" +
                "                            if(window.AndroidBridge)window.AndroidBridge.saveBase64File(dataUri,'application/json',dl||'save.json');" +
                "                        };" +
                "                        reader.readAsDataURL(xhr.response);" +
                "                    };" +
                "                    xhr.send();" +
                "                }else{" +
                "                    if(window.AndroidBridge)window.AndroidBridge.saveBase64File(href,'application/json',dl||'save.json');" +
                "                }" +
                "                return;" +
                "            }" +
                "            return origClick.apply(el, arguments);" +
                "        };" +
                "    }" +
                "    return el;" +
                "};" +

                // ===== 攔截 fetch 下載 =====
                "const origFetch = window.fetch;" +
                "window.fetch = function(...args){" +
                "    return origFetch.apply(this, args).then(r => {" +
                "        if(!r) return r;" +
                "        const ct = (r.headers && r.headers.get) ? (r.headers.get('content-type')||'') : '';" +
                "        const cd = (r.headers && r.headers.get) ? (r.headers.get('content-disposition')||'') : '';" +
                "        if(ct.includes('json') || ct.includes('octet') || ct.includes('download') || cd.includes('filename')){" +
                "            r.clone().blob().then(b => {" +
                "                try{" +
                "                    const reader = new FileReader();" +
                "                    reader.onloadend = function(){" +
                "                        if(window.AndroidBridge) window.AndroidBridge.saveBase64File(reader.result,'application/json','save.json');" +
                "                    };" +
                "                    reader.readAsDataURL(b);" +
                "                }catch(e){}" +
                "            }).catch(()=>{});" +
                "        }" +
                "        return r;" +
                "    }).catch(e => origFetch.apply(this, args));" +
                "};" +

                // ===== 攔截 XHR 下載 =====
                "const origXHROpen = XMLHttpRequest.prototype.open;" +
                "const origXHRSend = XMLHttpRequest.prototype.send;" +
                "XMLHttpRequest.prototype.open = function(method, url){" +
                "    this._saveHookUrl = url;" +
                "    return origXHROpen.apply(this, arguments);" +
                "};" +
                "XMLHttpRequest.prototype.send = function(){" +
                "    const self = this;" +
                "    const origOnLoad = this.onload;" +
                "    this.onload = function(){" +
                "        if(self._saveHookUrl && (self._saveHookUrl.includes('save') || self._saveHookUrl.includes('export') || self._saveHookUrl.includes('json'))" +
                "            && self.responseType !== 'blob'){" +
                "            try{" +
                "                const text = self.responseText || self.response;" +
                "                if(text && (text.startsWith('{') || text.startsWith('[') || text.startsWith('SIG1:') || text.startsWith('LZ1:')))" +
                "                    if(window.AndroidBridge)window.AndroidBridge.saveBase64File(text,'application/json','save.json');" +
                "            }catch(e){}" +
                "        }" +
                "        if(origOnLoad) origOnLoad.apply(self, arguments);" +
                "    };" +
                "    return origXHRSend.apply(this, arguments);" +
                "};" +

                // ===== 攔截 navigator.save 等 =====
                "if(navigator.save){const origSave=navigator.save.bind(navigator);navigator.save=function(data,filename){if(data&&filename&&filename.includes('json')){if(window.AndroidBridge)window.AndroidBridge.saveBase64File(data,'application/json',filename);return;}return origSave(data,filename);};}" +

                // ===== localStorage 解析工具 =====
                "function getCharLabel(d){" +
                "    try{" +
                "        if(!d||!d.p) return '未知';" +
                "        const p=d.p;" +
                "        const cls=CLASS_MAP[String(p.cls||p.class||'')]||CLASS_MAP[(p.className||'').toLowerCase()]||p.avatar||'未知';" +
                "        return (p.charLevel||p.level||0)+'等'+cls;" +
                "    }catch(e){return '未知';}" +
                "};" +

                "function parseSave(raw){" +
                "    if(!raw) return null;" +
                "    try{" +
                "        if(raw.startsWith('SIG1:')){" +
                "            const body=raw.substring(5).trim();" +
                "            const ci=body.indexOf(':');" +
                "            const jsonPart=ci>=0?body.substring(ci+1):body;" +
                "            return JSON.parse(jsonPart);" +
                "        }" +
                "        if(raw.startsWith('{')||raw.startsWith('[')) return JSON.parse(raw);" +
                "        const parsed=JSON.parse(raw);" +
                "        if(parsed&&parsed.data) return typeof parsed.data==='string'?JSON.parse(parsed.data):parsed.data;" +
                "        if(parsed&&parsed.save) return typeof parsed.save==='string'?JSON.parse(parsed.save):parsed.save;" +
                "        return parsed;" +
                "    }catch(e){return null;}" +
                "};" +

                // ===== 攔截外掛的 export/import 按鈕 =====
                "function hookExportImportButtons(){" +
                "    const mutationObserver = new MutationObserver((mutations) => {" +
                "        mutations.forEach((m) => {" +
                "            m.addedNodes.forEach((node) => {" +
                "                if(node.nodeType !== 1) return;" +
                "                // 攔截所有帶有 download 屬性的連結" +
                "                const links = node.querySelectorAll ? node.querySelectorAll('a[download]') : (node.tagName === 'A' && node.download ? [node] : []);" +
                "                links.forEach((a) => {" +
                "                    const origClick = a.click;" +
                "                    a.click = function(){" +
                "                        const href = a.href || a.getAttribute('href');" +
                "                        if(href && (href.startsWith('blob:') || href.startsWith('data:')))" +
                "                            if(window.AndroidBridge)window.AndroidBridge.saveBase64File(href,'application/json',a.download||'save.json');" +
                "                    };" +
                "                });" +
                "                // 攔截外掛的匯入按鈕（通常會觸發 file input）" +
                "                const fileInputs = node.querySelectorAll ? node.querySelectorAll('input[type=file]') : [];" +
                "                fileInputs.forEach((input) => {" +
                "                    const origOnchange = input.onchange;" +
                "                    input.addEventListener('change', function(e){" +
                "                        if(!this.files || !this.files[0]) return;" +
                "                        const reader = new FileReader();" +
                "                        reader.onload = function(ev){" +
                "                            if(window.AndroidBridge){" +
                "                                window.AndroidBridge.log('[SaveHook] 攔到匯入檔案: ' + this.files[0].name);" +
                "                            }" +
                "                        };" +
                "                        reader.readAsText(this.files[0]);" +
                "                        if(origOnchange) origOnchange.call(this, e);" +
                "                    });" +
                "                });" +
                "            });" +
                "        });" +
                "    });" +
                "    if(document.body){" +
                "        mutationObserver.observe(document.body, { childList: true, subtree: true });" +
                "    }else{" +
                "        document.addEventListener('DOMContentLoaded', () => {" +
                "            mutationObserver.observe(document.body, { childList: true, subtree: true });" +
                "        });" +
                "    }" +
                "};" +
                "hookExportImportButtons();" +

                // ===== localStorage 掃描 + 多欄位選擇 =====
                "function checkLocalStorage(){" +
                "    if(_exported){_exported=false;return;}" +
                "    const slots=[];" +
                "    for(const key of SLOT_KEYS){" +
                "        try{" +
                "            const raw=localStorage.getItem(key);" +
                "            if(!raw) continue;" +
                "            const d=parseSave(raw);" +
                "            if(d) slots.push({key:key,label:getCharLabel(d)});" +
                "        }catch(e){}" +
                "    }" +
                "    if(slots.length===0) return;" +
                "    if(slots.length===1){" +
                "        const s=slots[0];" +
                "        const raw=localStorage.getItem(s.key);" +
                "        if(window.AndroidBridge) window.AndroidBridge.saveBase64File('SIG1:dummy:'+raw,'application/json',s.label+'.json');" +
                "    }else{" +
                "        if(window.AndroidBridge) window.AndroidBridge.pickSaveSlot(JSON.stringify(slots.map(s=>({key:s.key,label:s.label}))));" +
                "    }" +
                "    if(window.AndroidBridge) window.AndroidBridge.log('[SaveHook] 掃描 localStorage 找到 '+slots.length+' 格存檔');" +
                "};" +

                // 曝光為 global function 給啟動器按鈕使用
                "window.checkLocalStorage = checkLocalStorage;" +

                // 曝光函式給 Java 端呼叫
                "window.__exportSlotByKey=function(key){" +
                "    try{" +
                "        const raw=localStorage.getItem(key);" +
                "        if(!raw) return;" +
                "        const d=parseSave(raw);" +
                "        const label=d?getCharLabel(d):key;" +
                "        if(window.AndroidBridge) window.AndroidBridge.saveBase64File('SIG1:dummy:'+raw,'application/json',label+'.json');" +
                "    }catch(e){}" +
                "};" +

                "window.__dumpStorage=function(){" +
                "    const out=[];" +
                "    for(const key of SLOT_KEYS){" +
                "        const raw=localStorage.getItem(key);" +
                "        if(raw) out.push(key+': '+raw.substring(0,200));" +
                "    }" +
                "    out.push('--- ALL localStorage keys ---');" +
                "    for(let i=0;i<localStorage.length;i++){" +
                "        const k=localStorage.key(i);" +
                "        const v=localStorage.getItem(k);" +
                "        out.push(k+': '+String(v||'').substring(0,200));" +
                "    }" +
                "    return out.join('\\n');" +
                "};" +

                // 定時掃描 localStorage（每 3 秒）
                "let scanInterval = setInterval(checkLocalStorage, 3000);" +

                // 監聽 localStorage 變更
                "window.addEventListener('storage', e => {" +
                "    if(e.key && SLOT_KEYS.includes(e.key)) checkLocalStorage();" +
                "});" +

                "}());";
    }

    // ==================== Blob 下載處理（備援） ====================

    private void triggerBlobDownload(String blobUrl) {
        String js = "javascript:(function(){" +
                "var xhr=new XMLHttpRequest();" +
                "xhr.open('GET','" + blobUrl + "',true);" +
                "xhr.responseType='blob';" +
                "xhr.onload=function(){" +
                "  var reader=new FileReader();" +
                "  reader.onloadend=function(){" +
                "    AndroidBridge.saveBase64FileLegacy(reader.result,'fable5_save_" + System.currentTimeMillis() + ".json');" +
                "  };" +
                "  reader.readAsDataURL(xhr.response);" +
                "};" +
                "xhr.send();" +
                "})()";
        webView.evaluateJavascript(js, null);
    }

    // ==================== 權限檢查 ====================

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

    // ==================== 啟動器 UI ====================

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
                ".btn-export{width:100%;padding:12px;background:#3498db;color:#fff;border:none;border-radius:8px;font-size:14px;font-weight:bold;cursor:pointer;margin-top:12px;display:flex;align-items:center;justify-content:center;gap:8px;}" +
                ".btn-export:active{background:#2980b9;}" +
                "</style></head><body>" +
                "<div class='card'>" +
                "  <h2>🎮 放置天堂 旗艦版啟動器</h2>" +
                "  <div class='subtitle'>10大外掛模組 + TMEngine 防斷線引擎 + 智慧存檔管理</div>" +
                "  <div class='label'>選擇遊戲伺服器：</div>" +
                "  <select id='serverSelect'>" +
                "    <option value='https://pp771007.github.io/idle-lineage-class/'>伺服器一 (pp771007)</option>" +
                "    <option value='https://shines871.github.io/idle-lineage-class/'>伺服器二 (shines871)</option>" +
                "  </select>" +
                "  <button class='btn-start' onclick='launchGame()'>🚀 啟動遊戲與防斷線外掛</button>" +
                "  <button class='btn-export' onclick='exportSaves()'>💾 智慧匯出存檔（掃描 localStorage）</button>" +
                "</div>" +
                "<script>" +
                "function launchGame(){" +
                "  var url = document.getElementById('serverSelect').value;" +
                "  location.href = url;" +
                "}" +
                "function exportSaves(){" +
                "  if(typeof window.checkLocalStorage === 'function'){" +
                "    window.checkLocalStorage();" +
                "    if(window.AndroidBridge) window.AndroidBridge.toast('正在掃描存檔...');" +
                "  }else{" +
                "    alert('存檔攔截模組尚未載入，請確認 Hook 是否正確注入。');" +
                "  }" +
                "}" +
                "</script>" +
                "</body></html>";

        webView.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null);
    }

    // ==================== Debug Dialog ====================

    private void showDebugDialog(String title, String message) {
        runOnUiThread(() -> {
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton("確定", null)
                    .setCancelable(false)
                    .show();
        });
    }

    // ==================== 返回鍵處理 ====================

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
