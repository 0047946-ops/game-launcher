// ==UserScript==
// @name         Idle Lineage Master Engine
// @namespace    http://tampermonkey.net/
// @version      1.0.1
// @description  全功能外掛與防斷線總引擎
// @author       0047946-ops
// @match        *://*/*
// @grant        none
// ==UserScript==

(function() {
    'use strict';
    console.log("🎮 [MasterEngine] 線上外掛總加載器啟動中...");

    // 動態加載防斷線與自動化模組
    const modules = [
        "https://raw.githubusercontent.com/0047946-ops/game-launcher/main/save_hook.js"
    ];

    modules.forEach(url => {
        const script = document.createElement('script');
        script.src = url + '?v=' + Date.now(); // 防快取機制
        script.type = 'text/javascript';
        document.head.appendChild(script);
    });
})();
