# Velocity Performance Test Plugin

一個用於 Velocity Proxy 的效能測試插件，可檢測主機規格、網路速度以及後端伺服器狀態。

## 功能特點

- **主機規格檢測** — CPU 型號與核心數、系統／行程 CPU 負載、JVM 堆積、系統實體記憶體、磁碟空間、JVM 執行時間
- **網路測速** — 純 Java 實作，自動從多個節點中挑選延遲最低者，量測下載／上傳／延遲／抖動
- **失效節點自動跳過** — 節點若回傳錯誤頁或資料量異常，會自動改用下一個節點
- **對外 IP 偵測** — 依序嘗試多個查詢服務
- **後端伺服器狀態** — 平行 ping 所有後端，顯示線上狀態、玩家數、版本與回應時間
- **結果快取與流量保護** — 同時間只會有一次測速，短期內重複查詢直接回傳快取
- **可設定** — 測速節點、資料量、逾時等皆可在 `config.toml` 調整，無需重新編譯

## 系統需求

- Velocity **4.1.1** 或更高版本
- Java **25** 或更高版本

## 編譯

專案內含 Maven Wrapper，不需要預先安裝 Maven：

```bash
./mvnw clean package        # Linux / macOS
.\mvnw.cmd clean package    # Windows
```

產物為 `target/VelocityPerformanceTest-2.0.0.jar`。

## 安裝

將 JAR 放入 Velocity 的 `plugins/` 資料夾後重啟。首次啟動會在
`plugins/velocityperf/config.toml` 產生預設設定檔。

## 指令

| 指令 | 說明 | 權限 |
|------|------|------|
| `/vperf` | 顯示說明 | `velocityperf.use` |
| `/vperf specs` | Proxy 主機規格 | `velocityperf.use` |
| `/vperf network` | 網路測速 | `velocityperf.use` |
| `/vperf servers` | 後端伺服器狀態 | `velocityperf.use` |
| `/vperf all` | 完整報告 | `velocityperf.use` |
| `/vperf reload` | 重新載入設定 | `velocityperf.admin` |

別名：`/vp`、`/serverperf`

主控台預設擁有全部權限。玩家請透過 LuckPerms 等權限插件授予
`velocityperf.use`（以及管理者的 `velocityperf.admin`）。

## 設定檔

`plugins/velocityperf/config.toml`：

```toml
[network]
download-mib = 50               # 下載測試資料量
upload-mib = 25                 # 上傳測試資料量（0 = 停用）
upload-url = "https://speed.cloudflare.com/__up"
ping-samples = 5                # 延遲取樣次數（用於計算 jitter）
connect-timeout-seconds = 5
request-timeout-seconds = 30
transfer-budget-seconds = 20    # 單次傳輸的時間上限
result-cache-seconds = 60       # 測速結果快取時間
command-cooldown-seconds = 10   # 每位玩家的指令冷卻
public-ip-services = [ "https://api.ipify.org", ... ]

[[network.download-targets]]
name = "Cloudflare"
url = "https://speed.cloudflare.com/__down?bytes={bytes}"
```

節點 URL 中的 `{bytes}` 會被替換成實際要求的位元組數。測試會平行量測所有節點的
延遲，再依延遲由低到高依序嘗試下載，直到成功為止。

## 流量說明

一次完整測速預設會消耗約 **75 MiB**（下載 50 + 上傳 25）。以下機制可避免濫用：

- **結果快取** — `result-cache-seconds` 內重複查詢直接回傳上次結果
- **單次併發** — 同時只允許一次測速，後到的請求共用同一份結果
- **指令冷卻** — 每位玩家受 `command-cooldown-seconds` 限制

若要降低流量，可調小 `download-mib` / `upload-mib`，或把 `upload-mib` 設為 0。

## 開發資訊

- **作者**：shi0103
- **版本**：2.0.0
- **技術棧**：Java 25、Velocity API 4.1.1、Adventure 5（MiniMessage）、Brigadier、JUnit 6

### 架構

| 檔案 | 職責 |
|------|------|
| `VelocityPerfPlugin` | 插件進入點、生命週期、指令註冊 |
| `PerfConfig` | 設定檔載入與驗證（不可變 record） |
| `PerfService` | 執行緒模型、單次併發限制、結果快取 |
| `SystemProbe` | 主機與 JVM 規格擷取 |
| `NetworkTester` | HTTP 測速（下載／上傳／延遲） |
| `PerfCommand` | Brigadier 指令樹與 MiniMessage 版面 |
| `Format` | 顯示層格式化 |

量測層一律回傳型別化數值（`OptionalDouble`、`OptionalLong`…），字串只在 `Format`
與 `PerfCommand` 產生，因此「量測失敗」與「數值為 0」不會被混為一談。
