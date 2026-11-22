# 車機麥克風上行：研究筆記（2026-11-17）

> 狀態：**WinPlay 端到端已通（0.1.42.37，2026-11-17），待真車驗證**。協定事實來自 DiPlay（車機端實作，GPL-3.0，只讀事實、不抄程式碼）
> 的原始碼與 PR #898、catplay issue #15；Android 端權限為 S24 Ultra（SDK 37）實測。

## 結論

車機麥克風 → 手機這條路**協定上已經清楚，而且跟我們現有的媒體音訊下行幾乎同構**；
真正的難點在 Android 端：把收到的聲音交給 Gemini／通話 App。

| 環節 | 可行性 | 說明 |
|---|---|---|
| 向車機要麥克風串流 | ✅ 清楚 | 手機（我們）發音訊 SETUP，`input=true` |
| 收車機的麥克風封包並解密 | ✅ 清楚 | 與下行同一套 RTP + ChaCha20-Poly1305，只是方向反過來、金鑰標籤不同 |
| 車機語音鍵觸發 | ✅ 清楚 | 車機經事件通道送 `requestSiri` 指令 |
| 把聲音交給 Gemini / VoIP App | ⚠️ 有路但未驗證 | shell 有 `MODIFY_AUDIO_ROUTING`，可試 AudioPolicy 錄音注入 |
| 一般電話（電信通話）用車機麥克風 | ❌ 不可行 | 通話上行在基頻／音訊 HAL，非 root 注入不進去 |

## 協定（手機＝發送端視角）

### 1. 建立麥克風串流：音訊 SETUP

由**手機**發起（與我們現在的媒體音訊 SETUP 同一個 RTSP 請求）。DiPlay 的處理邏輯顯示車機看這些欄位：

- `type: 100`（主音訊）
- `audioType`：`speechRecognition`（語音助理）／`telephony`（通話）——這兩種車機**一律開麥**；
  `default`／`compatibility` 只有帶 `input: true`（或帶上行埠）才開麥（例如 App 錄語音訊息）
- `input: true`
- **`dataPort`：手機自己監聽的 UDP 埠**——車機把麥克風封包送到「手機位址:這個埠」
- `streamConnectionID`：金鑰推導要用（見下）
- `audioFormat`：**上下行共用同一個格式**（DiPlay PR #898：只限制輸出格式沒用，iPhone 會選一個雙向通用的）。
  要從車機 `/info` 的 `audioInputFormats` 與 `audioOutputFormats` 交集裡選
- `framesPerPacket`（選填）：決定每包幾毫秒

車機回應照常給它自己的下行 `dataPort`／`controlPort`（Siri 的回答、通話對方聲音走下行）。

### 2. 封包格式（車機 → 手機，UDP）

與我們送媒體音訊給車機的格式相同，方向相反：

- 12 位元組 RTP 標頭**明文**：`0x80`、PT = 串流 type（100）、序號（大端 u16）、時間戳（大端 u32）、SSRC
- 酬載 ChaCha20-Poly1305 加密；**AAD = 標頭第 4–12 位元組**（時間戳 + SSRC）
- nonce = 4 個零位元組 + 8 位元組小端計數器；封包尾端 = 16 位元組 tag + 同一個 8 位元組小端計數器
- 時間戳每包遞增「樣本數」；Opus 的時鐘跟格式走（48k 格式每 20ms +960、24k 格式 +480、16k 格式 +320）

### 3. 金鑰

`HKDF-SHA512(ikm = 配對共享密鑰, salt = "DataStream-Salt" + streamConnectionID, info = "DataStream-Input-Encryption-Key", 長度 32)`

下行用的是同一公式、info 換成 `DataStream-Output-Encryption-Key`——我們送媒體音訊時已經在用這套推導（對照 `AirPlayClient` 現有實作）。

### 4. 格式

- PCM：**16-bit 大端**單聲道（Android 錄到的是小端，車機端要換位元組序；我們收到後要換回來）
- 常見取值（`audioFormat` 位元）：`0x100` = LPCM 32k 單聲道、`0x4000` = LPCM 48k 單聲道、
  `0x154` = 8/16/24/32k 單聲道 PCM 組合；Opus：`0x10000000` 16k、`0x20000000` 24k、`0x40000000` 48k
- 通話常用 vocoder 取樣率（8/16/24/32k）；Siri 實測常用 24k
- catplay issue #15 的一台車機：MainAudio `audio_input_formats` = PCM 8k/16k/24k 單聲道；
  telephony 與 speechRecognition 的 `input_latency_micros` = 106000

### 5. 觸發

- **車機語音鍵 → 手機**：車機經事件通道送 `{"type": "requestSiri", "params": {"siriAction": 2}}`（按下）
  與 `siriAction: 3`（放開）。我們收到後可以改成「喚起 Gemini」。
- **手機主動**（例如「Hey Google」）：手機自己發 speechRecognition 的 SETUP 即可，車機看到就開麥。
- `/info` 的 `modes` 裡有 `appStates`（`speech` 帶 `speechMode`、`phoneCall`）——語音進行中可能還要經
  modesChanged 宣告 speech 狀態，讓車機切到語音介面／壓低媒體音量（未驗證是否必要）。
- DiPlay 的開麥時機：通話等到第一個下行封包才開（拿對方聲音當回音消除參考）；Siri／語音訊息若 500ms 內
  沒有下行也直接開。

## Android 端：把聲音交給 App

S24 Ultra 實測 `com.android.shell`（即我們的 Shizuku UserService）擁有：
`MODIFY_AUDIO_ROUTING`、`CAPTURE_AUDIO_OUTPUT`、`CAPTURE_AUDIO_HOTWORD`、`MODIFY_PHONE_STATE`、
`MODIFY_AUDIO_SETTINGS_PRIVILEGED`。

- **候選做法（未驗證）**：在 UserService 用隱藏 API 註冊一個 `AudioPolicy`，內含
  `ROUTE_FLAG_LOOP_BACK` + `RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET`（MIC / VOICE_RECOGNITION /
  VOICE_COMMUNICATION）的 `AudioMix`，再用 `createAudioTrackSource` 拿到 AudioTrack，把車機麥克風 PCM 寫進去
  ——系統會把它當成這些錄音來源的輸入，交給正在錄音的 App。這是系統內建的「錄音注入」機制。
- 要驗證的風險：
  1. Gemini 的熱詞偵測（`src:HOTWORD`）多半走 DSP，注入不到；但喚醒後的聽寫若用一般錄音來源，可能吃得到。
  2. 一般電信通話的上行在基頻，注入無效；VoIP（WhatsApp、LINE、Telegram）多用 VOICE_COMMUNICATION，可能有效。
  3. 注入期間手機自己的麥克風會被取代，結束必須解除註冊（跟虛擬螢幕一樣要考慮殘留與三層收口）。
  4. 各 ROM（榮耀／三星）對 AudioPolicy 的限制不同。

## 實驗 1：錄音注入（2026-11-17，S24 Ultra / SDK 37）—— ✅ 通過

獨立 dex 以 `app_process` 用 shell（uid 2000）身分執行（不改 App）：
`AudioMixingRule`（`setTargetMixRole(MIX_ROLE_INJECTOR=1)` + 對 MIC/VOICE_RECOGNITION/VOICE_COMMUNICATION/
DEFAULT/UNPROCESSED 各 `addRule(attrs, RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET=2)`）→ `AudioMix`（48k 單聲道
`CHANNEL_OUT_MONO`、`ROUTE_FLAG_LOOP_BACK=2`）→ `AudioManager.registerAudioPolicy` 回 0 →
`createAudioTrackSource(mix)` 寫 1 kHz 正弦波。

| 錄音來源 | 注入前 | 注入中 |
|---|---|---|
| MIC | rms 507、1k 佔比 0.000（真實環境音） | rms 8485、1k 佔比 1.000 |
| VOICE_RECOGNITION / VOICE_COMMUNICATION / DEFAULT / UNPROCESSED | — | 全部 1k 佔比 1.000 |

結論：注入期間真實麥克風被**完全取代**；注銷後恢復。踩坑：
- 三星 `CompatSandbox` 會讀 `ActivityThread.getConfiguration()`，自建 system context 前必須補
  `mConfigurationController`（否則 NPE，與 scrcpy 的處理相同）。
- app_process 結束時 binder 執行緒池不會自己退，要 `System.exit(0)`。
- 本次是**同一程式自己錄**；其它 App（錄音機、Gemini）是否也被注入，待實驗 2 驗證。

## 實驗 2：其它 App 是否被注入（2026-11-17）—— ✅ 通過（熱詞除外）

`MicInject hold 60` 注入 1 kHz 期間由使用者操作，從 `APM_AudioPolicyManager` 日誌看每次開麥被路由到的裝置
（`0x80000100` = `AUDIO_DEVICE_IN_REMOTE_SUBMIX`，即注入；`0x80000004` = `AUDIO_DEVICE_IN_BUILTIN_MIC`）：

| App / 用途 | 錄音來源 | 路由到 | 結果 |
|---|---|---|---|
| 三星錄音機 | MIC (1) | `0x80000100` REMOTE_SUBMIX | ✅ 被注入 |
| Gemini 喚起後聽寫 | VOICE_RECOGNITION (6) | `0x80000100` REMOTE_SUBMIX | ✅ 被注入 |
| Gemini「Hey Google」熱詞 | HOTWORD (1999) | `0x80000004` 內建麥克風（SoundTrigger / DSP） | ❌ 不受影響 |

使用者實機確認：錄音機回放確為注入的嗶聲，Gemini 聽寫收不到真人說話。

結論：**喚起後的語音輸入可以改用車機麥克風**；熱詞偵測走晶片上的 SoundTrigger，注入不到，
「Hey Google」仍要對著手機喊（或改由車機語音鍵 `requestSiri` 直接喚起 Gemini）。
注意注入期間**手機自己的麥克風對這些 App 完全失效**，實作時只能在語音／通話串流存活期間註冊、結束立即注銷。

## 建議的驗證順序

1. **先做 Android 端注入實驗**（不碰協定）：UserService 註冊注入 AudioPolicy，餵一段測試音（例如 1 kHz 正弦波），
   用系統錄音機／Gemini 聽寫看錄不錄得到。這一步不通，整條路就不用做。
2. 協定端：仿照現有媒體音訊下行，加一個 speechRecognition + `input=true` 的 SETUP，開 UDP 監聽並解密，
   先把收到的 PCM 存檔驗證（WinPlay 支援麥克風上行時可在電腦上測）。
3. 串起來：Gemini 喚起（或收到 `requestSiri`）→ 開麥串流 → 注入 → 結束時收口。

## v1 實作（2026-11-17）

只接**車機語音鍵**這條路（`requestSiri`）；「Hey Google」仍用手機麥克風。

- `shizuku/CarInputServiceImpl.startMicInjection(sampleRate)` / `stopMicInjection()`（AIDL 追加在末尾）：
  註冊注入 AudioPolicy，回傳管道寫端；管道 EOF（App 死掉）即自動注銷，不會把手機麥克風永久佔住。
- `airplay/CarMic.kt`：格式協商（speechRecognition 檔位、輸入 ∩ 輸出、PCM 單聲道 24k > 16k > 48k > 32k > 8k）、
  封包解密（大端轉小端）、Input 金鑰推導；單測 `CarMicTest`。
- `AirPlayClient.runMicStream` / `teardownMicStream`：SETUP 帶 `input=true` + 本機 UDP `dataPort`，之後補一次 RECORD。
- `audio/CarMicController.kt`：車機語音鍵 → 開 UDP、開注入、SETUP → 等第一個包（≤1.5s）→
  `cmd voiceinteraction show` 喚起助理 → 每 500ms 查 `isAssistantListening`：開過麥後連續關麥 2s／10s 都沒開麥／
  超過 2 分鐘即收口（關 socket、撤注入、TEARDOWN）。再按一次語音鍵 = 結束並收起助理。
  會話退役（`retireSession`）與推流結束（`onEnded`）兩端都會 `stop`。
- 失敗任何一步都退回「只唤起助理、用手機麥克風」。

驗證時看日誌 `CARMIC:` 行：`麦克风流 SETUP 往返…`、`✓ 车机麦克风已到达（Nms）`、`结束（原因）收包=N 坏包=N`。
已知風險：媒體音訊若也是 type=100（PCM 路線），某些車機 TEARDOWN 只看 type 會連帶拆掉媒體流（日誌會提示）。

## v2（2026-11-17）：自動切換 + 下行靜音

- **實驗 3（錄音進行中才註冊注入）✅**：同一個 AudioRecord 在 1 秒內就切到注入音，對方 App 不用重開錄音。
  → 改為**自動**：會話期間每 400ms 經 UserService `activeMicApps()`（AppOps `startWatchingActive`
  RECORD_AUDIO）查誰在錄音；有別的 App 在錄就開車機麥克風，沒人錄 2s 後撤掉。語音鍵路徑保留（10s 寬限）。
- **WinPlay 讀碼發現**（`resources/app.asar.unpacked/build/engine/protocol/cpStack.js`）：宣告
  `speechRecognition` 的輸入格式為 PCM 單聲道 | Opus；**收到該串流第一個下行包才開麥**（mic runs while audio
  flows），只收不發的 v1 在 WinPlay 上永遠收不到麥克風。v2 在麥克風串流期間每 20ms 送一包靜音下行。
- 消耗：監視只是一次本機 binder 呼叫（讀一張表，不開 shell 行程）；麥克風串流只在有人錄音時存在，
  上下行各約 50 包/秒（24k 單聲道 PCM 約 0.4 Mbps）。

## WinPlay 端到端打通（0.1.42.37）—— ✅

三星錄音機錄音 → 自動開車機麥克風 → 145ms 收到第一包 → 8s 收 422 包、壞包 0 → 錄音回放為電腦麥克風的聲音。
沿途踩的坑（每一個都會讓「看起來都對、但錄到的是空的」）：

1. **UserService 建注入軌道被拒**（`Cannot create AudioTrack`）：`AudioPolicy.createAudioTrackSource` 用進程的
   opPackageName（DroidCar 包名）+ uid 2000，身分不符。改為照 AOSP 自建 `AudioTrack.Builder().setContext(shell)`
   （API 34+；12/13 退回系統方法）。
2. **軌道 tags 必須以 `addr=` 開頭**：APM 只認開頭；多加 `fixedVolume;` 會讓軌道進不了 mix，錄音拿到靜音。
3. **只收不發 → WinPlay 不開麥**：它收到該串流第一個下行包才 `openMicUplink`，所以要持續送靜音下行。
4. **WinPlay 麥克風包頭 bug**：`h.writeUInt16BE(m.seq)` 漏 offset，序號蓋在位元組 0..1（`0x80`/PT 被覆蓋）。
   我們不再校驗 RTP 版本位，真偽交給 Poly1305（AAD 只覆蓋 4..12，不受影響）；單測鎖住。
5. 連線 ID 限制在 2^53 以內（JS 接收端把 plist 整數讀成 Number 會舍入，鹽對不上）——本次非主因，防患保留。

