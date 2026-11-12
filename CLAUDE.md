# ClaudeETF（台美股資產）開發規範

Android 原生 App：Kotlin＋Jetpack Compose（Material3）。只在 GitHub Actions 建置（`.github/workflows/build-apk.yml`：先跑 `testReleaseUnitTest`，再 `assembleRelease`，成功即發布 Release，App 內線上更新）。所有文字、更新說明、commit 訊息一律使用繁體中文。

## 1. 架構（實際專案結構）
- 狀態：`data/AppState.kt` 單例（Compose `mutableStateOf`／`mutableStateListOf`），以 SharedPreferences「claudeetf」保存；機密（金鑰）用 `saveSecret／loadSecret`（Keystore 加密）。
- 網路：`data/remote/Http.kt`（GET）與 OkHttp（POST、WebSocket）；JSON 用 `org.json`，**不使用 Retrofit、kotlinx.serialization**。
- 行情來源：富果 Fugle（盤中，串流＋REST）、證交所 MIS／OpenAPI、櫃買 OpenAPI、Yahoo Finance（美股、K 線歷史）。
- 分析：`KlineAnalysis`（K 線形態、量價、支撐壓力）、`KBarSignalEngine`（普通股／ETF 分流策略）、`InvestAnalysis`（投資分析指數各因子）、`PriceBand`（一年光譜）、`EntryAdvisor`（綜合進場建議）、`StockMetrics`（本地指標＋規則體檢）、`AiAnalysis`（多家 AI 服務結構化解讀，Agnes AI 優先：Agnes、Claude、OpenAI、Gemini、DeepSeek、自訂 OpenAI 相容）。

## 2. 個股分析三段式分離（嚴格執行）
1. **數據獲取層**：只從上述資料源取得 OHLCV、三大法人、本益比／淨值比／殖利率；嚴禁讓 LLM 推估或編造任何數字。
2. **本地運算層（純 Kotlin）**：MA5/10/20/60、20 日乖離率、RSI(14)、KD(9,3,3)、5 日均量比、量價狀態、外資／投信近 5 日累計、投信連買天數、K 棒型態，全部在 `StockMetrics.compute()` 以純函數計算。
3. **AI 解讀層**：只把 `ComputedStockMetrics.toJson()` 傳給 AI（依序嘗試已設定的服務，Agnes AI 優先），系統提示詞為 `app/src/main/assets/stock_analysis_skill.md`（與 `.claude/skills/stock-analysis/SKILL.md` 同內容），回傳 JSON 解析為 `StockAnalysisReport`。

## 3. 工程品質
- 網路與運算一律在 `Dispatchers.IO／Default`；UI 只讀 Compose 狀態。
- 不新增未經驗證的第三方套件，以免 CI 編譯失敗。
- Claude：系統提示詞區塊加 `cache_control: {"type":"ephemeral"}`（Prompt Caching）、低溫度；結果以「代號＋最新交易日」快取於 `filesDir/ai_analysis/`，同一交易日不重複呼叫，「重新分析」才強制刷新。
- 降級容錯：無網路、未設金鑰或 API 失敗時，一律顯示本地指標與本地規則體檢（`StockMetrics.localReport()`），不得拋出未捕獲例外。
- API 金鑰只由使用者在「設置 › 分析與提醒 › AI 個股體檢」為每家服務輸入並加密保存，嚴禁寫死在程式或 Repo。

## 4. 測試
- 純函數（指標、型態、規則）需有 JVM 單元測試（`app/src/test`），CI 會先跑測試。
