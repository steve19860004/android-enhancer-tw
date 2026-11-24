# 版權、授權與聲明

## 版權與授權

Copyright © 2026 steve19860004

本程式為自由軟體：你可以依據自由軟體基金會發布的 **GNU 通用公眾授權條款第 3 版（GPLv3）**
重新散布及／或修改本程式（完整條款見同目錄的 `LICENSE`）。

本程式是基於「希望它有用」的前提散布，但**不提供任何擔保**，連「可售性」或「適用於特定用途」
的默示擔保都沒有。使用風險由使用者自行承擔。

### 專有函式庫連結例外（GPL Linking Exception）

作為特別例外，著作權人允許將本程式與下列**專有函式庫**連結並散布其結合物，
且不因此要求這些專有函式庫一併適用 GPLv3：

- Google Play 服務／Google ML Kit（翻譯、語言偵測、文字辨識 OCR）

即使 GPLv3 的條款對結合後的整體另有要求，上述例外仍然有效。

## 獨立重製聲明

本專案為**獨立重製**，參考公開的功能說明與介面行為，以全新程式碼撰寫，
**未取得也未複製**任何第三方 App 的原始碼。本專案與「Android補強整合CN／Android_Enhancer_CN」
（dx9901mk5）**無任何關聯，亦非其官方版本**，不代表、不隸屬於原作者。
所有提及之商標、產品與 App 名稱均屬各自所有人，僅供辨識之用。

## 第三方元件

- `app/src/main/assets/s2tw.txt`、`twphrases.txt`：由 [OpenCC](https://github.com/BYVoid/OpenCC)
  字典（STCharacters、STPhrases、TWVariants、TWPhrases）轉出，Apache License 2.0。
- [Shizuku API](https://github.com/RikkaApps/Shizuku-API)（Apache 2.0）、
  [HiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass)（Apache 2.0）、
  Google ML Kit（Google API 服務條款）、AndroidX／Material Components（Apache 2.0）。

以上第三方元件的授權條款與 GPLv3 相容，或依上述連結例外處理。

## 使用須知與免責

- 本工具的進階功能透過 **Shizuku** 以 adb shell 權限執行，或使用 Android 無障礙服務，皆**免 root**。
- 「環境隱身」只會**真的關閉**開發者選項、USB 偵錯或無障礙服務後再還原，**不偽裝任何系統數值**。
- 請僅將本工具用於**你自己的裝置**與合法用途。因使用本工具造成的任何後果，作者不負責任。
