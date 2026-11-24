# 免Root百寶箱

免 root 的 Android 整合工具箱，給陸版 ROM（vivo／iQOO、小米、OPPO）和一般 Android 用。
全部免 root，需要高權限的部分透過 **Shizuku**。套件名：`tw.local.enhancer`，最低 Android 10。

## 功能

| 分頁 | 功能 | 需要 |
|---|---|---|
| 切換器 | 懸浮球／側邊欄手勢／桌面捷徑／快捷磁貼，扇形展開最近 App；排除清單 | 懸浮窗、無障礙（或使用情況存取） |
| 1 來電音樂 | LINE、Telegram、Messenger 等通話時暫停音樂，掛斷自動恢復 | 通知讀取 |
| 2 相簿守護 | 開相機時暫停 Google 相簿連網，離開 N 秒後恢復；24 小時紀錄 | Shizuku、無障礙 |
| 3 語音攔截 | 攔截藍心小V／小愛／小布等陸版助理，改叫 Gemini 浮層／Gemini App／指定 App | 無障礙（Shizuku 選配） |
| 4 環境隱身 | 誠實版嚴格模式：進入指定 App 時真的關閉開發者選項與偵錯，離開／鎖屏恢復 | 寫入安全設定（Shizuku 授權一次） |
| 5 捲動翻譯 | 穿透式原位翻譯：文字節點＋OCR 雙引擎、ML Kit 離線翻譯、簡轉台灣繁體、可拖曳膠囊控制條、快捷磁貼 | 無障礙（OCR 需 Android 11+） |
| 6 工具 | 畫圈搜尋（實驗）、雙擊電源鍵開相機、Google 地圖時間軸 640dp、Shizuku 靜默安裝 APK、輸入法管理 | 多數需 Shizuku |
| ⚙ 設定 | 權限中心、一鍵 Shizuku 授權全部權限、設定備份／還原 JSON | — |

## 沒有照抄的部分

- **ADB=2 偽裝、隱藏無障礙騙過銀行偵測**：沒有做。Tab 4 只做「真的關掉、離開再打開」。
- **付費授權／試用期**：沒有，全部功能直接可用。
- 記憶體快照縮圖、Vivo 小螢幕無障礙守護、導航語音修復、移除系統警示通知：原版是針對特定機型的修補，這版先不做。

## 編譯

推到 `main` 分支後，GitHub Actions 會自動編譯，APK 會出現在 **Releases**（手機直接下載安裝）。
簽章固定用 `app/enhancer.keystore`，所以新版可以直接覆蓋安裝。

本機編譯：`gradle :app:assembleRelease`（JDK 17、Android SDK 35）。

## 第一次使用

1. 安裝 APK → 開啟 Shizuku 並啟動服務。
2. 本 App「⚙ 設定」→「授權 Shizuku」→「一鍵透過 Shizuku 授予全部權限」。
   （Android 13+ 側載 App 會自動解除「受限制的設定」，無障礙才開得起來。）
3. 到各分頁打開要用的功能。

## 授權與聲明

- 授權：**GPLv3**（見 `LICENSE`）。歡迎自由使用、修改、散布；衍生版本須同樣以 GPLv3 開源並標示出處。附專有函式庫連結例外（Google ML Kit），詳見 `NOTICE.md`。
- 版權：© 2026 steve19860004。
- **獨立重製聲明**：本專案為獨立重製，以全新程式碼撰寫，未複製任何第三方 App 原始碼；與「Android補強整合CN／Android_Enhancer_CN」無關，亦非其官方版本。
- 本程式不提供任何擔保，使用風險自負。請僅用於自己的裝置與合法用途。
