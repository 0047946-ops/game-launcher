# 放置天堂外掛啟動器 (Game Launcher APK)

這是一個專為 Android 手機打造的網頁遊戲外掛啟動器專案，支援自動從遠端 GitHub 注入外掛腳本，完美繞過瀏覽器限制。

## 支援功能
* **多伺服器切換**：可自由選擇不同的遊戲伺服器網址。
* **自動外掛注入**：透過 GitHub 雲端原始碼動態加載。
* **雲端自動打包**：透過 GitHub Actions 自動編譯成 Android APK。
# 🎮 放置天堂雙外掛啟動器

跨平台雙外掛載入方案，支援 Android 原生 APK 與 iOS / PC 網頁 PWA 模式。

---

### 📥 玩家下載與入口連結

* 🤖 **Android 玩家（推薦下載 APK）**：
  [👉 點此下載最新版 Android APK](https://github.com/0047946-ops/game-launcher/releases/latest)
  *(下載安裝後直接開啟，底層自動強制注入雙外掛，免設定)*

* 🍎 **iOS (iPhone/iPad) & 電腦玩家（網頁入口）**：
  [👉 點此開啟線上啟動器網址](https://0047946-ops.github.io/game-launcher/)
  *(配合 Safari 瀏覽器 Userscripts 擴充套件使用，支援「新增至主畫面」全螢幕執行)*

---

### 📱 iOS 玩家設定快速指引
1. 至 App Store 下載免費擴充套件 **Userscripts** 並在 Safari 設定中啟用。
2. 於 Userscripts 匯入雙外掛整合腳本。
3. 開啟上述網頁入口，點擊 Safari 選單「分享」->「加入主畫面」即可全螢幕自動載入雙外掛！

iOS Safari 擴充功能模式（最推薦！100% 自動載入）
​這是在 iOS 系統上最穩定、完全不需要付費或每 7 天重新簽名的標準解法。
​iOS 玩家操作步驟：
​步驟 1：開啟 iPhone 的 App Store，下載免費腳本管理器：Userscripts 或 Stay。
​步驟 2：進入 iPhone「設定」->「Safari 瀏覽器」->「擴充功能」，將下載的套件開啟並設為「允許」。
​步驟 3：點擊 Safari 網址列左側按鈕開啟 Userscripts，新增以下兩條外掛網址：
​主外掛網址：[https://cdn.jsdelivr.net/gh/qcc781192000/idle-lineage-plugin@main/main.user.js](https://cdn.jsdelivr.net/gh/qcc781192000/idle-lineage-plugin@main/main.user.js)
​GM 商店網址：[https://kid0924.github.io/idle-lineage-class/klh_GMShop.js](https://kid0924.github.io/idle-lineage-class/klh_GMShop.js)
​步驟 4：使用 Safari 開啟我的網址 [https://0047946-ops.github.io/game-launcher/](https://0047946-ops.github.io/game-launcher/) 並點擊啟動，外掛即會在背景自動注入並順利執行。

// ==UserScript==
// @name         放置天堂-GM免費商店
// @match        *://shines871.github.io/*
// @match        *://pp771007.github.io/*
// @run-at       document-end
// ==UserScript==

(function() {
    'use strict';
    if (!window.__gm_shop_loaded) {
        window.__gm_shop_loaded = true;
        var script = document.createElement('script');
        script.src = 'https://kid0924.github.io/idle-lineage-class/klh_GMShop.js?t=' + Date.now();
        document.head.appendChild(script);
    }
})();


 iOS 朋友完成上述設定後，整個運作過程會達到非常滑順的「App 化」體驗：
### 一、 視覺與操作上的「App 化」效果
 1. **全螢幕沉浸體驗（無網址列）**：
   * 點擊手機桌面的「放置天堂」圖示開啟後，Safari 上下的網址列、分頁按鈕與工具列都會**自動隱藏**。
   * 遊戲畫面會以 100% 全螢幕呈現，不再像是在「逛網頁」，而是像打開一個獨立下載的 iOS 遊戲 App。
 2. **背景自動載入雙外掛**：
   * 點擊啟動遊戲後，背景的 Userscripts 擴充套件會在一瞬間自動注入**主外掛**與 **GM 免費商店**。
   * 畫面上會直接出現外掛的控制功能面板，玩家**不需要點擊任何按鈕或貼上任何程式碼**。
 3. **獨立多工切換**：
   * 在 iPhone 的多工任務切換畫面（從螢幕底部向上滑）中，這個遊戲會擁有**獨立的應用程式卡片**，可以隨時切換回 LINE、FB 或其他 App，再切回來依然保持連線。
### 二、 iOS 朋友的最佳配置懶人包（可直接轉發）
為了讓您的 iOS 朋友能最快速設定好，您可以直接將以下內容複製傳給他：
> 📱 **iOS (iPhone/iPad) 雙外掛自動載入設定教學**
>  1. **下載擴充套件**：至 App Store 搜尋並下載免費的 **Userscripts**。
>  2. **開啟權限**：進入 iPhone「設定」->「Safari 瀏覽器」->「擴充功能」-> 開啟「Userscripts」並設為允許。
>  3. **新增腳本**：打開 Safari 點擊 Userscripts 圖示，建立一個新腳本，貼上以下內容並儲存：
> ```javascript
> // ==UserScript==
> // @name         放置天堂雙外掛整合包
> // @match        *://shines871.github.io/*
> // @match        *://pp771007.github.io/*
> // @run-at       document-end
> // ==UserScript==
> 
> (function() {
>     'use strict';
>     // 1. 載入主外掛
>     if (!window.__main_plugin_loaded) {
>         window.__main_plugin_loaded = true;
>         var s1 = document.createElement('script');
>         s1.src = 'https://cdn.jsdelivr.net/gh/qcc781192000/idle-lineage-plugin@main/main.user.js';
>         document.head.appendChild(s1);
>     }
>     // 2. 載入 GM 免費商店
>     if (!window.__gm_shop_loaded) {
>         window.__gm_shop_loaded = true;
>         var s2 = document.createElement('script');
>         s2.src = 'https://kid0924.github.io/idle-lineage-class/klh_GMShop.js?t=' + Date.now();
>         document.head.appendChild(s2);
>     }
> })();
> 
> ```
>  4. **加到桌面**：用 Safari 開啟 [https://0047946-ops.github.io/game-launcher/](https://0047946-ops.github.io/game-launcher/)，點擊底部「分享」按鈕 [↑] -> 選擇 **「新增至主畫面」**。
>  5. **開玩**：以後直接點擊桌面的遊戲圖示，就能全螢幕自動載入雙外掛遊玩！
> 
### 總結
透過這個方案，**Android 玩家用 APK，iOS 玩家用桌面 PWA**，兩邊都能享受到不需要每次手動貼程式碼、點開即玩的「原生 App 級」流暢體驗！
