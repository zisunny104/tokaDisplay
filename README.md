# tokaDisplay

Nukkit 共用資訊顯示服務，Java 21，版本 0.2.1。最終讓 zero 的顯示功能有獨立歸屬。

## 第一階段已實作

- **頂部血條區**：血條可以改顏色和填充度。顏色只有遊戲提供的 7 種（pink、blue、red、green、yellow、purple、white），預設白色，設定檔 `colors` 可以自訂別名。
- **訊息區**（血條下面的文字）：第一行是基本資訊，只有它的擁有者能改，任何來源都蓋不掉；接著是固定訊息，最後是單次訊息，彼此疊加、不互相覆蓋。
- **兩種送法（共用同一個訊息基底，用繼承而不是各寫一套）**：單次發送閃過就消失，沒指定秒數用預設 15 秒；固定發送會留在那裡，同一個 id 再送就是換內容，指定秒數則時間到消失，沒指定則留到被移除。任何一則訊息都可以選擇性地帶血條的填充度與顏色。
- **管理與清除**：`listMessages`、`removeMessage`、`clearMessages`、`unpin` 是公開 API；`/display list`、`/display unpin <玩家或*> <id或編號>`、`/display clear <玩家或*>` 只是呼叫它們。`messages.pinned-max-seconds` 可以限制沒指定秒數的固定訊息最久留多久。
- 側邊記分板由單一服務管理，按來源與優先序選擇內容。舊的 `bossBar(...)` 呼叫仍可用：來源 `zero:status` 視為基本資訊，其他來源視為固定訊息。
- 同優先序按來源字串穩定排序；逾時清除後恢復較低優先序內容。
- 只移除自己建立的 BossBar／記分板；不掃掉其他插件的 BossBar。
- 玩家離線、來源停止更新、插件停用時清理。側邊記分板只更新差異，避免每次重建。
- `/display` 查狀態，權限 tokaDisplay.admin，預設 OP。
- zero 1.7.16 的相容 adapter 提交既有 BossBar／側欄內容；它暫時仍負責組合遊戲資料與原有冷卻。

廣播／MOTD 正式清單採 tokaStorage 0.1.3 的 SQLite／JSON／YAML，預設 SQLite；設定為 `storage.backend`。短期顯示狀態不持久化。玩家共用偏好由 Profiles 管理，避免重複來源。

## 使用

將 tokaDisplay-0.2.7.jar 與 zero-1.7.18.jar 放入 plugins，移走舊版 zero JAR，完整重啟。zero 使用可選整合，不用額外安裝 ScoreboardAPI。缺少 Display 時 zero 暫用舊顯示，作為移除舊實作前的相容措施；Display 已啟用但渲染失敗時不建立競爭顯示，也不停止傳送／保護等業務。

不要使用 reload。此版本尚未在正式 VPS 或真實玩家畫面驗收，不會自動更新正式服。

## API

使用本插件作 provided 依賴與 softdepend，確認已啟用後才呼叫。所有 API 都在伺服器主執行緒使用。

```java
// 基本資訊（只有擁有者能改）：文字、血條填充度、顏色、多久沒再送就消失（tick）
Main.baseBar(player, "上線 12 人", 100f, "white", 150);
// 單次發送：秒數 0 用預設；填充度負數＝不動血條；顏色空白用預設
Main.flash(player, "歡迎來到幻月服", 0, -1f, null);
// 固定發送：同一個 id 再送就是換內容；秒數 0＝留到被移除；優先權越大越靠上
Main.pin(player, "portal:wild", "[隨機傳送]冷卻剩餘 30 秒", 0, 10, 25f, "cooldown");
Main.unpin(player, "portal:wild");
// 管理：列出（JSON）、移除（id 或編號）、清除（基本資訊不受影響）
String json = Main.listMessages(player);
boolean removed = Main.removeMessage(player, "portal:wild");
Main.clearMessages(player);
// 舊的呼叫仍可用
Main.bossBar(player, "portal:teleport", "等待傳送", 50f, 100, 100);
Main.sidebar(player, "regions:info", "領地資訊", new String[]{"擁有者：..."}, new int[]{1}, 10, 100);
Main.remove(player, "portal:teleport");
Main.removeSource("regions:info");
```

Main 的完整名稱為 dev.toka.display.Main。進度 0～100；ttlTicks 1～72000，以單調時鐘計算顯示有效期，不作遊戲冷卻時鐘。每位玩家／管道來源數預設上限 32。側欄最多 15 行，行字串須唯一。插件自行計算業務數據，只傳入顯示快照，不將餘額、權限與區域規則交給 Display。

## 公告管理與資料 API

`/display motd|broadcast list|add <文字>|set <ID> <文字>|remove <ID>` 管理公告，權限為 `tokaDisplay.motd.manage`／`tokaDisplay.broadcast.manage`，預設 OP，可由權限插件配置。MOTD 是伺服器列表名稱輪播；broadcast 使用訊息區通知，不是一般聊天。`announcements.enabled` 控制發布，預設 true；偵測到 zero 的舊 MOTD／廣播排程仍運行時會暫停發布，避免重複。

舊系統的備份、交接、核對及關閉舊功能由 **zero** 負責，Display 不提供交接指令，不讀寫 zero 的資料庫或設定。既有資料中的 `legacySource` 欄位仍可讀取，但不再用來判斷新快照相同，也不建立 `migration-backups`。

一般公開 API（主執行緒）：

```java
Main.replaceAnnouncements(String json); // throws Exception；空目的地或目前內容相同才接受
String json = Main.announcementsSnapshot();
boolean same = Main.verifyAnnouncements(String json);
Main.setAnnouncementsEnabled(boolean enabled); // 僅保存本插件 announcements.enabled
String rows = Main.listAnnouncements("motd");
int id = Main.changeAnnouncement("broadcast", "add", -1, "公告");
```

快照格式：

```json
{"schema":1,"motd":[{"id":4,"text":"伺服器標題"}],"broadcast":[{"id":9,"text":"公告內容"}]}
```

清單順序以 ID 排序比對，ID 及內容保留；重複 ID、空白文字或不同的非空目的地會拒絕。寫入後讀回驗證，不會以以前的來源快照覆蓋使用者後續修改。API 不負責舊資料備份，也不會自動關閉其他插件。

進出訊息、ActionBar、Title、顯示偏好整合仍待後續工作；唯一拆分計畫：[zero 主文件](https://github.com/zisunny104/zero/blob/master/docs/ARCHITECTURE.md)。

## 建置

JDK 21、Maven，先將測試核心安裝為 cn.nukkit:Nukkit:1.0-SNAPSHOT。執行 build.ps1（可傳 Maven／Repository）；產物在專案 output 與同層 plugin-modernization/output，不進版控。測試包含優先序、穩定排序、來源替換／清除、逾時及來源上限；實際畫面仍需玩家驗收。

驗證紀錄（2026-10-16）：4 項仲裁測試通過；tests/smoke.py 使用獨立世界、停用資料庫，確認有／無 Display 都可啟動 zero 基本模組。這不是實際玩家畫面驗收。

## 多語系

所有顯示文字（指令回應、用法、`/display help`、後台 log）都在 `lang/zh-TW.yml`，由內嵌的 tokaI18n 讀取；config.yml 的 `language` 決定語言（目前只有 `zh-TW`）。伺服器擁有者可把同名檔放進 `plugins/tokaDisplay/lang/` 覆蓋個別文字。血條前綴 `flash-prefix`、`pinned-prefix` 仍在 config.yml。詳見 [docs/I18N.md](docs/I18N.md)。
