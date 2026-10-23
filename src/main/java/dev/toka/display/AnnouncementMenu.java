package dev.toka.display;

import cn.nukkit.Player;
import cn.nukkit.form.element.ElementInput;
import cn.nukkit.form.element.ElementLabel;
import cn.nukkit.form.element.ElementToggle;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.response.FormResponseModal;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.window.FormWindowCustom;
import cn.nukkit.form.window.FormWindowModal;
import cn.nukkit.form.window.FormWindowSimple;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The admin window for the MOTD and the broadcasts; every change goes through AnnouncementService, the same path as the commands. */
final class AnnouncementMenu {
    private static final int PAGE = 8;
    private static final String ICON_MOTD = "textures/items/book_writable", ICON_BROADCAST = "textures/items/map_filled",
            ICON_ADD = "textures/ui/color_plus", ICON_SEARCH = "textures/items/compass_item";
    private final Main plugin;
    private final AnnouncementService service;
    private final Ui ui = new Ui();

    AnnouncementMenu(Main plugin, AnnouncementService service) { this.plugin = plugin; this.service = service; }

    Ui ui() { return ui; }

    private String t(String key, Object... args) { return plugin.messages().text(key, args); }

    private static String number(int value) { return String.format(Locale.ROOT, "%,d", value); }

    private boolean may(Player player, String kind) {
        if (player.hasPermission("tokaDisplay." + kind + ".manage")) return true;
        player.sendMessage(t("err.noManagePermission"));
        return false;
    }

    void open(Player player) {
        int[] counts = service.counts();
        var window = new FormWindowSimple(t("ui.menu.title"), t("ui.menu.body", number(counts[0]), number(counts[1])));
        window.addButton(Ui.button(t("ui.menu.motd", number(counts[0])), ICON_MOTD));
        window.addButton(Ui.button(t("ui.menu.broadcast", number(counts[1])), ICON_BROADCAST));
        ui.show(player, window, answer -> {
            int id = ((FormResponseSimple) answer).getClickedButtonId();
            if (id == 0) list(player, "motd", "", 0);
            else if (id == 1) list(player, "broadcast", "", 0);
        });
    }

    private void list(Player player, String kind, String query, int page) {
        if (!may(player, kind)) return;
        String needle = query.toLowerCase(Locale.ROOT);
        List<JsonObject> hits = new ArrayList<>();
        int total = 0;
        for (var row : service.rows(kind)) {
            JsonObject entry = row.getAsJsonObject();
            total++;
            if (needle.isEmpty() || entry.get("text").getAsString().toLowerCase(Locale.ROOT).contains(needle)
                    || String.valueOf(entry.get("id").getAsInt()).equals(needle)) hits.add(entry);
        }
        int pages = Math.max(1, (hits.size() + PAGE - 1) / PAGE);
        int current = Math.max(0, Math.min(page, pages - 1));
        var window = new FormWindowSimple(t("ui.list.title", t("ui.kind." + kind), current + 1, pages),
                t(query.isEmpty() ? "ui.list.body" : "ui.list.bodyQuery", number(hits.size()), number(total), query));
        List<Runnable> actions = new ArrayList<>();
        window.addButton(Ui.button(t("ui.back"), null));
        actions.add(() -> open(player));
        window.addButton(Ui.button(t("ui.search"), ICON_SEARCH));
        actions.add(() -> search(player, kind, query));
        window.addButton(Ui.button(t("ui.add"), ICON_ADD));
        actions.add(() -> add(player, kind));
        if (current > 0) { window.addButton(Ui.button(t("ui.prev"), null)); actions.add(() -> list(player, kind, query, current - 1)); }
        for (int i = current * PAGE; i < Math.min(hits.size(), (current + 1) * PAGE); i++) {
            JsonObject entry = hits.get(i);
            int id = entry.get("id").getAsInt();
            String text = entry.get("text").getAsString().replace('\n', ' ');
            if (text.length() > 24) text = text.substring(0, 24) + "…";
            window.addButton(Ui.button(t("ui.entry", id, text), null));
            actions.add(() -> edit(player, kind, id));
        }
        if (current < pages - 1) { window.addButton(Ui.button(t("ui.next"), null)); actions.add(() -> list(player, kind, query, current + 1)); }
        ui.show(player, window, answer -> {
            int id = ((FormResponseSimple) answer).getClickedButtonId();
            if (id >= 0 && id < actions.size()) actions.get(id).run();
        });
    }

    private void search(Player player, String kind, String query) {
        var window = new FormWindowCustom(t("ui.search"));
        window.addElement(new ElementInput(t("ui.searchLabel"), t("ui.searchHint"), query));
        ui.show(player, window, answer -> list(player, kind, ((FormResponseCustom) answer).getInputResponse(0).trim(), 0));
    }

    private void add(Player player, String kind) {
        var window = new FormWindowCustom(t("ui.add"));
        window.addElement(new ElementLabel(t("ui.textHint")));
        window.addElement(new ElementInput(t("ui.textLabel"), "", ""));
        ui.show(player, window, answer -> {
            String text = ((FormResponseCustom) answer).getInputResponse(1);
            if (text == null || text.isBlank()) { player.sendMessage(t("err.emptyText")); list(player, kind, "", 0); return; }
            run(player, kind, "add", -1, text);
        });
    }

    private void edit(Player player, String kind, int id) {
        String current = null;
        for (var row : service.rows(kind)) if (row.getAsJsonObject().get("id").getAsInt() == id) current = row.getAsJsonObject().get("text").getAsString();
        if (current == null) { list(player, kind, "", 0); return; }
        var window = new FormWindowCustom(t("ui.edit.title", t("ui.kind." + kind), id));
        window.addElement(new ElementLabel(t("ui.textHint")));
        window.addElement(new ElementInput(t("ui.textLabel"), "", current.replace("\n", "\\n")));
        window.addElement(new ElementToggle(t("ui.edit.delete"), false));
        ui.show(player, window, answer -> {
            var response = (FormResponseCustom) answer;
            if (response.getToggleResponse(2)) { confirmDelete(player, kind, id); return; }
            String text = response.getInputResponse(1);
            if (text == null || text.isBlank()) { player.sendMessage(t("err.emptyText")); list(player, kind, "", 0); return; }
            run(player, kind, "set", id, text);
        });
    }

    private void confirmDelete(Player player, String kind, int id) {
        var window = new FormWindowModal(t("ui.delete.title"), t("ui.delete.body", t("ui.kind." + kind), id), t("ui.delete.yes"), t("ui.delete.no"));
        ui.show(player, window, answer -> {
            if (((FormResponseModal) answer).getClickedButtonId() == 0) run(player, kind, "remove", id, "");
            else edit(player, kind, id);
        });
    }

    private void run(Player player, String kind, String action, int id, String text) {
        if (!may(player, kind)) return;
        service.submit(kind, action, id, text, (message, error) -> {
            if (!player.isOnline()) return;
            player.sendMessage(error == null ? message : t("msg.announce.failed", error.getClass().getSimpleName()));
            list(player, kind, "", 0);
        });
    }
}
