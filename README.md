# tokaDisplay

畫面顯示服務：管理血條 BossBar、固定與單次訊息、側邊記分板、MOTD 輪播與廣播公告。

## 主要功能

- **血條**：顯示在畫面頂部，支援七種顏色與自訂顏色別名。
- **訊息區**：血條下方的文字。第一行是基本資訊狀態列，其下是固定訊息與單次訊息，多個插件的內容會依優先序疊在一起，不會互相覆蓋。
- **側邊記分板**：依版面設定組合各插件的區段；插件直接送出的記分板優先顯示，多份直接送出的內容再依優先序選取。
- **MOTD 與廣播**：伺服器名稱輪播與定時廣播，由管理員用指令增修，不必改檔。

畫面由上到下是這樣：

```
        [線上]12  21:30  玩家名稱     第一行：基本資訊（置中，只有擁有者能改）
  ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓     血條（顏色、填充度可變）
  = 傳送冷卻 12 秒                      固定訊息（= 開頭，直到被移除）
  - 歡迎回來                            單次訊息（- 開頭，時間到消失）
```

## 管理指令

| 指令 | 用法 | 說明 | 權限 |
| --- | --- | --- | --- |
| `/display` | `/display` | 顯示服務狀態與用法；`/display help` 查看說明 | `tokaDisplay.admin` |
| `/display list` | `/display list [玩家]` | 列出固定與單次訊息，不指定玩家則列出全部 | `tokaDisplay.admin` |
| `/display unpin` | `/display unpin <玩家或*> <訊息id或編號>` | 移除指定訊息，`*` 代表所有玩家 | `tokaDisplay.admin` |
| `/display clear` | `/display clear <玩家或*>` | 清除固定與單次訊息（基本資訊不受影響） | `tokaDisplay.admin` |
| `/display testbar` | `/display testbar [顏色] [填充度]` | 在自己的血條顯示 10 秒測試，填充度 0–100 | `tokaDisplay.admin` |
| `/display motd` | `list`、`add <文字>`、`set <ID> <文字>`、`remove <ID>` | 管理 MOTD | `tokaDisplay.motd.manage` |
| `/display broadcast` | 用法同 `motd` | 管理廣播 | `tokaDisplay.broadcast.manage` |

以上管理權限預設都是 OP。MOTD 與廣播分別檢查各自的管理權限，其他管理操作檢查 `tokaDisplay.admin`。玩家沒有需要輸入的指令。

## 設定 config.yml

變更後需重新啟動。

| key | 預設 | 說明 |
| --- | --- | --- |
| `language` | `zh-TW` | 訊息語言 |
| `update-ticks` | `5` | 畫面更新間隔 tick |
| `max-sources` | `32` | 每位玩家、每種顯示管道的來源上限 |
| `storage.backend` | `sqlite` | 公告儲存格式：`sqlite`、`json`、`yaml`；切換前需搬遷資料 |
| `announcements.enabled` | `true` | 是否發布 MOTD 與廣播；zero 舊公告模組仍啟用時會暫停發布 |
| `announcements.motd-seconds` | `10` | MOTD 輪播間隔（秒） |
| `announcements.broadcast-seconds` | `25` | 廣播間隔（秒） |
| `bossbar.enabled`、`sidebar.enabled` | `true` | 關閉後對應畫面不會出現 |
| `sidebar.title` | 空白標題 | 側欄標題，可用色碼 |
| `sidebar.refresh-ticks` | `20` | 組合並更新側欄的間隔 tick，內容沒變不會重送 |
| `sidebar.layout` | 下列版面範例 | 側欄由上而下的內容，項目是區段 id 或 `-`（空白行） |

### 側欄版面

側欄由 tokaDisplay 組合，各插件註冊一行文字作為區段。`sidebar.layout` 預設：

```yaml
sidebar:
  layout: [region, position, bearing, "-", money, coin, "-", item, "-", score, tps, ping, area]
```

內建區段有 座標 `position`、方位 `bearing`、手持物品 `item`、`tps`、`ping`；`region`、`money`、`coin`、`score`、`area` 由其他插件註冊。沒有註冊或當下沒內容的區段會略過，連續或頭尾的空白行自動收斂，最多 15 行，超過的略過。插件用 `sidebar` 直接送出記分板時，優先於這個版面。

### 血條與訊息設定

| key | 預設 | 說明 |
| --- | --- | --- |
| `default-color` | `white` | 沒指定顏色時的血條顏色 |
| `colors` | cooldown: red、ready: green、notice: yellow | 顏色別名 |
| `messages.flash-seconds` | `15` | 單次訊息預設停留秒數 |
| `messages.max-flash-lines`、`messages.max-pinned` | `4`、`8` | 單次訊息同時行數／每人固定訊息上限 |
| `messages.pinned-max-seconds` | `0` | 固定訊息最長停留秒數，`0` 不限；擔心訊息被忘記關掉可設如 `21600` |
| `messages.flash-prefix`、`messages.pinned-prefix` | `§f- `、`§f= ` | 單次／固定訊息的開頭，可用色碼 |
| `messages.trim-blank-lines` | `true` | 清掉空白行，血條下方不留空位 |
| `messages.bar-gap-lines` | `1` | 第一行下方有內容時，為血條預留的行數 |
| `messages.status-min-width` | `60` | 第一行置中用的最小寬度 |

## 串接

請將 tokaDisplay 列為選用依賴 softdepend，以 `Main.ready()` 確認服務已啟用，再於主執行緒呼叫 `dev.toka.display.Main` 的靜態方法。

| 方法 | 參數 | 用途 |
| --- | --- | --- |
| `baseBar` | 玩家、文字、填充度、顏色、存活 tick | 設定第一行基本資訊（僅該資訊的擁有者使用） |
| `flash` | 玩家、文字、秒數、填充度、顏色 | 送出單次訊息，時間到自動消失 |
| `pin` | 玩家、id、文字、秒數、優先序、填充度、顏色 | 固定訊息；同 id 再送會更新，秒數 0 代表直到移除 |
| `unpin` | 玩家、id | 移除自己送出的固定訊息 |
| `bossBar` | 玩家、來源、文字、進度、優先序、存活 tick | 舊版血條介面，相容用 |
| `sidebar` | 玩家、來源、標題、行、分數、優先序、存活 tick | 顯示側邊記分板（最多 15 行，行文字不可重複） |
| `registerSection` | id、函式（玩家 → 一行文字，null 或空字串代表不顯示）；可在前面加 owner 名稱 | 註冊側欄區段，位置由 `sidebar.layout` 決定；函式丟例外時該區段本輪略過 |
| `unregisterSection`、`unregisterSections` | id／owner 名稱 | 移除區段；插件停用時用 owner 名稱一次移除自己註冊的區段 |
| `remove`、`removeSource` | 玩家與來源／來源 | 移除自己建立的血條訊息與記分板 |
| `listMessages`、`removeMessage`、`clearMessages` | 玩家（與 id 或編號） | 查看、移除、清除訊息（管理指令即用這些） |

顏色可填七種顏色名稱或 `colors` 別名，留空使用預設色。

## 疑難排解

- **畫面沒有血條或記分板？** 確認 `bossbar.enabled`、`sidebar.enabled` 為 `true`。
- **MOTD 與廣播沒有動靜？** 確認 `announcements.enabled` 為 `true`，且 zero 的舊 `motd`／`broadcast` 模組已停用；舊模組仍啟用或無法確認其狀態時，本插件會暫停發布。
- **某則固定訊息一直留著？** 用 `/display list` 找到後以 `/display unpin` 移除。
