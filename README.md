# tokaDisplay

**tokaDisplay 0.2.8** 是 Nukkit 共用資訊顯示服務：管理頂部血條、血條下方的訊息區與側邊記分板，並提供 MOTD 與廣播公告。其他插件以 API 送出顯示內容，本插件負責排序、疊加與清理。

## 安裝

- 需要 Java 21；已驗證 Nukkit PM1E 4511。
- 將 `tokaDisplay-0.2.8.jar` 放入 `plugins/`，完整重啟（不使用 reload）。
- 其他插件使用時，將本插件列為 provided 依賴與 softdepend，確認已啟用後才呼叫。
- 使用共用套件 tokaStorage（公告資料）與 tokaI18n（語系），皆已內嵌於 JAR。

## 功能

- 血條：7 種遊戲顏色（pink、blue、red、green、yellow、purple、white），可用 `colors` 自訂別名。
- 訊息區：第一行為基本資訊（僅擁有者可改），其下依序為固定訊息與單次訊息，彼此疊加。
- 側邊記分板：由單一服務依來源與優先序選擇內容；只移除自己建立的畫面。
- 公開 API（主執行緒，類別 `dev.toka.display.Main`）：`baseBar`、`flash`、`pin`、`unpin`、`listMessages`、`removeMessage`、`clearMessages`、`bossBar`、`sidebar`、`remove`、`removeSource`。

## 指令與權限

| 指令 | 用途 | 權限（預設） |
| --- | --- | --- |
| `/display`、`/display help` | 查詢狀態、查看說明 | `tokaDisplay.use`（所有人） |
| `/display list`、`unpin <玩家或*> <id或編號>`、`clear <玩家或*>` | 查看、移除、清除訊息 | `tokaDisplay.admin`（OP） |
| `/display testbar` | 測試血條顏色 | `tokaDisplay.admin` |
| `/display motd｜broadcast list｜add｜set｜remove` | 管理 MOTD 與廣播 | `tokaDisplay.motd.manage`／`tokaDisplay.broadcast.manage`（OP） |

## 設定（`config.yml`）

| key | 預設 | 說明 |
| --- | --- | --- |
| `language` | `zh-TW` | 語言 |
| `update-ticks` | `5` | 更新頻率 |
| `max-sources` | `32` | 每位玩家、每種管道的來源上限 |
| `storage.backend` | `sqlite` | 公告儲存：`sqlite`、`json`、`yaml` |
| `announcements.enabled` | `true` | 公告發布開關 |
| `announcements.motd-seconds` / `broadcast-seconds` | `10` / `25` | 輪播間隔 |
| `bossbar.enabled` / `sidebar.enabled` | `true` | 血條／側欄開關 |
| `default-color` | `white` | 血條預設顏色 |
| `colors` | cooldown: red、ready: green、notice: yellow | 顏色別名 |
| `messages.flash-seconds` | `15` | 單次訊息預設秒數 |
| `messages.max-flash-lines` / `max-pinned` | `4` / `8` | 單次、固定訊息上限 |
| `messages.flash-prefix` / `pinned-prefix` | `§f- ` / `§f= ` | 訊息前綴 |
| `messages.trim-blank-lines` | `true` | 清除空白行 |
| `messages.bar-gap-lines` | `1` | 血條下方預留行數 |
| `messages.status-min-width` | `60` | 第一行置中寬度 |
| `messages.pinned-max-seconds` | `0` | 固定訊息最長時間，0 為不限 |

## 版本紀錄

- 0.2.8：文件整理。
- 0.2.7：所有文字移入 tokaI18n `zh-TW`，新增 `/display help`。
- 0.2.5：第一行以空白移至文字區中間，其餘行靠左。
- 0.2.4：修正血條顏色編號（白色曾顯示為深紫）；有後續內容才留血條行。
- 0.2.3：訊息前綴、不留空白行，新增 `/display testbar`。

授權：MIT。
