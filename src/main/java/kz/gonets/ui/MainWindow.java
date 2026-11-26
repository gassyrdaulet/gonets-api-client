package kz.gonets.ui;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DragEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;
import javafx.util.StringConverter;
import kz.gonets.CodeExport;
import kz.gonets.Highlight;
import kz.gonets.Http;
import kz.gonets.Json;
import kz.gonets.Logic;
import kz.gonets.Model;
import kz.gonets.Model.Env;
import kz.gonets.Model.EnvAuth;
import kz.gonets.Model.Item;
import kz.gonets.Model.KV;
import kz.gonets.Model.State;
import kz.gonets.Model.UiPrefs;
import kz.gonets.Pretty;
import kz.gonets.Signer;
import kz.gonets.Store;
import kz.gonets.Tokens;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Главное окно: коллекции и история слева, запрос и ответ справа. */
public class MainWindow {
    private static final int HL_LIVE = 20000, HL_BIG = 8_000_000, RES_BIG = 500000, RES_HL_BIG = 8_000_000; // RES_BIG — выше номера строк отключаются, подсветка живёт до RES_HL_BIG
    private static final List<String> METHODS = List.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");

    private final Stage stage;
    private final Store store;
    private State state;
    private UiPrefs ui;
    private final ExecutorService bg = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "gonets-bg");
        t.setDaemon(true);
        return t;
    });

    // ---- состояние редактора (как в прежней странице) ----
    private Item cur = Item.blankReq();
    // несохранённые правки запросов, с которых ушли (id → рабочая копия); спрашиваем о них только при закрытии окна
    private final Map<String, Item> drafts = new LinkedHashMap<>();
    private final Map<String, String> draftBase = new HashMap<>();
    private String unsavedBase;       // запрос вне коллекции: его состояние при открытии (null: считается несохранённым)
    private String side = "coll", reqTab = "body", resTab = "body";
    private Http.Result lastRes;
    private String selFolder, dragId, renamingId;
    private boolean dirtyState, suppress, sending;
    private boolean layoutReady; // до первой раскладки окна положение разделителей не запоминаем
    private String sideQuery = "";
    private List<String> curTokens = List.of();
    private Set<String> visibleIds;
    private int loadCount;

    // ---- элементы ----
    private final StackPane overlay = new StackPane();
    private final BorderPane app = new BorderPane();
    private final SplitPane mainSplit = new SplitPane();
    private final VBox sidebar = new VBox();
    private final Button tabColl = Ui.button("Коллекции", "small"), tabHist = Ui.button("История", "small");
    private final TextField sideSearch = new TextField();
    private final Button sideSearchClear = Ui.button("✕", "flat", "search-clear");
    private final Label sideCount = Ui.hint("");
    private final ListView<Row> sideList = new ListView<>();
    private final Button newReq = Ui.button("+ Запрос", "small"), newFolder = Ui.button("+ Папка", "small"), importCurl = Ui.button("Импорт cURL", "small");
    private final Button collapseSide = Ui.button("", "icon-btn");
    private WindowChrome chrome;

    private final TextField nameField = new TextField();
    private final HBox crumbs = new HBox(6);
    /** Меньше окно не сжимается: иначе строка запроса и панели теряют элементы. */
    private static final double MIN_W = 1100, MIN_H = 640;
    private final Button revertBtn = Ui.button("Откатить", "small");
    private final Label dirtyBadge = new Label("● Не сохранён");
    private final Button settingsBtn = Ui.button("", "icon-btn");
    private final Button aboutBtn = Ui.button("", "icon-btn");
    private final ComboBox<String> methodBox = new ComboBox<>();
    private final TextField urlField = new TextField();
    private final Button sendBtn = Ui.button("Отправить", "primary"), saveBtn = Ui.button("Сохранить"), cloneBtn = Ui.button("Клонировать"), exportBtn = Ui.button("Экспорт");
    private final Button debugBtn = Ui.button("Трафик", "small");
    private final ComboBox<String> envSel = new ComboBox<>();
    private final Button envEdit = Ui.button("Окружения");

    private final SplitPane reqRes = new SplitPane();
    private final HBox reqTabs = Ui.cls(new HBox(), "tabs");
    private final StackPane reqContent = Ui.cls(new StackPane(), "content");
    private final HBox resTabs = Ui.cls(new HBox(), "tabs");
    private final StackPane resContent = Ui.cls(new StackPane(), "content");
    private final FlowPane resMeta = Ui.cls(new FlowPane(14, 2), "meta");
    private final Button copyRes = Ui.button("Копировать", "small");
    private final Map<String, Button> reqTabBtns = new LinkedHashMap<>(), resTabBtns = new LinkedHashMap<>();
    private final Label cParams = count(), cHeaders = count(), cResHeaders = count();

    private final CodeEditor bodyEditor = new CodeEditor(true, HL_LIVE, HL_BIG);
    private final Label edStatus = Ui.cls(new Label(), "ed-status");
    private final Label resStatus = Ui.cls(new Label(), "ed-status");
    private final VBox resBox = new VBox(6);
    private String resStatSource = "";
    private final CodeEditor resEditor = new CodeEditor(false, Integer.MAX_VALUE, RES_HL_BIG);
    private final ProgressBar loadbar = new ProgressBar(ProgressBar.INDETERMINATE_PROGRESS);
    private final FindBar find = new FindBar(this::findTargets);
    private final PauseTransition dirtyTimer = new PauseTransition(Duration.millis(150));
    private EnvDialog envDialog;

    public MainWindow(Stage stage, Store store) {
        this.stage = stage;
        this.store = store;
    }

    // ---------- доступ для окон ----------
    Stage stage() { return stage; }
    State state() { return state; }
    void persist() { store.persist(); }

    /** Задача в фоне; результат приходит в поток интерфейса. Пока задача идёт, сверху виден индикатор. */
    <T> void background(Callable<T> task, BiConsumer<T, Throwable> done) {
        loading(true);
        bg.submit(() -> {
            T r = null;
            Throwable err = null;
            try { r = task.call(); } catch (Throwable t) { err = t; }
            T res = r;
            Throwable e = err;
            Platform.runLater(() -> { loading(false); done.accept(res, e); });
        });
    }

    private void loading(boolean on) {
        loadCount = Math.max(0, loadCount + (on ? 1 : -1));
        loadbar.setVisible(loadCount > 0);
    }

    private void toast(String msg) { Ui.toast(stage, msg); }

    // ---------- запуск ----------
    public void show() {
        state = store.load();
        List<String> notes = migrate();
        ui = state.ui == null ? new UiPrefs() : state.ui;
        ui.fix();
        state.ui = ui;
        build();
        // своя шапка вместо системной: окно без рамки, шапка и края для изменения размера — в WindowChrome
        stage.initStyle(Ui.MAC ? StageStyle.DECORATED : StageStyle.UNDECORATED);
        chrome = new WindowChrome(stage, overlay, () -> { if (confirmClose()) stage.close(); }, collapseSide, settingsBtn, aboutBtn);
        Scene scene = new Scene(chrome.root(), ui.winW != null ? ui.winW : 1360, ui.winH != null ? ui.winH : 860);
        scene.getStylesheets().add(Ui.CSS);
        stage.setScene(scene);
        if (ui.winX != null && ui.winY != null && onSomeScreen(ui.winX, ui.winY, scene.getWidth())) {
            stage.setX(ui.winX);
            stage.setY(ui.winY);
        } else stage.centerOnScreen();
        if (!ui.collapsed) mainSplit.setDividerPositions(Math.max(180, Math.min(640, ui.sideW)) / scene.getWidth());
        reqRes.setDividerPositions(clampR(ui.split));
        stage.setTitle("GoNets");
        stage.getIcons().add(Ui.icon());
        stage.setMinWidth(MIN_W);
        stage.setMinHeight(MIN_H);
        installKeys(scene);
        // закрытие системой (Alt+F4, панель задач) идёт сюда, кнопка «закрыть» в шапке — через confirmClose
        stage.setOnCloseRequest(e -> { if (!confirmClose()) e.consume(); });
        applyUi();
        Ui.applyTheme(scene.getRoot()); // окно ещё не показано: setTheme его не видит
        renderEnvSel();
        // при запуске — последний открытый запрос из коллекции (если он ещё есть), иначе новый
        Item last = Boolean.TRUE.equals(ui.restoreLast) ? Logic.findReq(state, ui.lastReqId) : null;
        if (last != null) {
            cur = Json.copy(last, Item.class).fix();
            cur.type = null;
            Logic.Loc loc = Logic.locate(state, last.id);
            selFolder = loc != null && loc.parent() != null ? loc.parent().id : null;
        } else cur = newBlank();
        loadCur();
        stage.show();
        chrome.setOnMaximized(this::widenSideForMaximized);
        if (Boolean.TRUE.equals(ui.maximized)) chrome.setMaximized(true);
        Platform.runLater(() -> {
            applySideWidth();
            applySplit();
            Platform.runLater(() -> layoutReady = true);
        });
        for (String n : notes) Platform.runLater(() -> toast(n));
    }

    /** Можно ли закрывать: если есть несохранённые запросы (открытый и отложенные), спрашиваем один раз про все. */
    private boolean confirmClose() {
        updateDirty();
        List<String> names = new ArrayList<>();
        if (dirtyState) names.add(reqLabel(cur));
        for (Item d : drafts.values()) names.add(reqLabel(d));
        if (!names.isEmpty()) {
            StringBuilder list = new StringBuilder();
            for (int i = 0; i < names.size() && i < 8; i++) list.append("• ").append(names.get(i)).append('\n');
            if (names.size() > 8) list.append("… и ещё ").append(names.size() - 8).append('\n');
            int choice = Ui.choose(stage,
                    names.size() == 1 ? "Сохранить изменения запроса?" : "Не сохранены запросы: " + names.size(),
                    list + "\nЕсли не сохранить, правки пропадут.", names.size() == 1 ? "Сохранить" : "Сохранить все", "Не сохранять", "Отмена");
            if (choice == 0) saveAll();
            else if (choice != 1) return false;
        }
        saveWindow();
        store.flush();
        return true;
    }

    private static String reqLabel(Item r) {
        return !nz(r.name).isBlank() ? r.name.trim() : !nz(r.url).isBlank() ? r.url.trim() : "Новый запрос";
    }

    /** Сохраняет открытый запрос и все отложенные. */
    private void saveAll() {
        if (dirtyState) save();
        for (Item d : new ArrayList<>(drafts.values())) putInTree(d);
        drafts.clear();
        draftBase.clear();
        persist();
    }

    /** Кладёт запрос в коллекцию: заменяет сохранённый или добавляет новый в начало. */
    private void putInTree(Item src) {
        Item copy = Json.copy(src, Item.class);
        copy.type = "req";
        if (nz(copy.name).isBlank()) {
            String u = nz(copy.url).replaceFirst("^https?://", "");
            copy.name = u.isEmpty() ? "Новый запрос" : u.substring(0, Math.min(60, u.length()));
        }
        Logic.Loc loc = Logic.locate(state, copy.id);
        if (loc != null && !loc.node().folder()) loc.arr().set(loc.i(), copy);
        else state.tree.add(0, copy);
    }

    private void saveWindow() {
        if (sideBeforeMax != null) ui.sideW = sideBeforeMax; // на весь экран панель шире обычного: сохраняем обычную
        ui.maximized = chrome.isMaximized();
        javafx.geometry.Rectangle2D b = chrome.normalBounds();
        ui.winX = b.getMinX();
        ui.winY = b.getMinY();
        ui.winW = b.getWidth();
        ui.winH = b.getHeight();
    }

    /** Сохранённое положение окна видно хотя бы на одном экране (монитор могли отключить). */
    private static boolean onSomeScreen(double x, double y, double w) {
        return !javafx.stage.Screen.getScreensForRectangle(x + 40, y + 10, Math.max(1, w - 80), 20).isEmpty();
    }

    /** Перенос старых форматов данных (как при старте прежней страницы). */
    private List<String> migrate() {
        List<String> notes = new ArrayList<>();
        if (state.tree == null) state.tree = new ArrayList<>();
        if (state.drafts == null) state.drafts = new LinkedHashMap<>();
        if (state.history == null) state.history = new ArrayList<>();
        if (state.envs == null) state.envs = new ArrayList<>();
        if (state.requests != null) {
            for (Item r : state.requests) { r.type = "req"; state.tree.add(r); }
            state.requests = null;
            store.persist();
        }
        state.tree.forEach(Item::fix);
        // черновиков больше нет: несохранённые правки прежних версий не восстанавливаются
        if (!state.drafts.isEmpty()) { state.drafts.clear(); store.persist(); }
        state.history.forEach(Item::fix);
        for (Env env : state.envs) {
            if (env.vars == null) env.vars = new ArrayList<>();
            EnvAuth a = env.auth;
            if (a == null || a.loginUrl == null || a.loginUrl.isEmpty() || (a.loginReqId != null && !a.loginReqId.isEmpty())) continue;
            Boolean enabled = a.enabled;
            Logic.migrateLegacyAuth(state, env, a.loginUrl.replaceAll("/login/?$", ""), a.username, a.password, a.appName, a.refreshUrl);
            env.auth.enabled = enabled;
            env.auth.loginUrl = env.auth.refreshUrl = env.auth.username = env.auth.password = env.auth.appName = null;
            env.auth.insecure = null;
            store.persist();
            notes.add("Авторизация окружения «" + env.name + "» перенесена в запросы папки «Авторизация»");
        }
        if (state.envs.isEmpty()) {
            Env d = new Env();
            d.id = "default";
            d.name = "Без окружения";
            state.envs.add(d);
        }
        if (state.envs.stream().noneMatch(e -> e.id.equals(state.activeEnv))) state.activeEnv = state.envs.get(0).id;
        return notes;
    }

    // ---------- разметка ----------
    private static Label count() { return Ui.cls(new Label(), "count"); }

    static SVGPath icon(String path, String cls) {
        SVGPath p = new SVGPath();
        p.setContent(path);
        p.getStyleClass().add(cls);
        return p;
    }

    private static final String ICON_PANEL = "M4.5 3.5h11a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2h-11a2 2 0 0 1-2-2v-9a2 2 0 0 1 2-2zM8 3.5v13";
    private static final String ICON_CHEVRON = "M6 3l5 5-5 5";
    private static final String ICON_COPY = "M8.8 7h5.9a1.8 1.8 0 0 1 1.8 1.8v5.9a1.8 1.8 0 0 1-1.8 1.8H8.8A1.8 1.8 0 0 1 7 14.7V8.8A1.8 1.8 0 0 1 8.8 7zM13 7V5.3A1.8 1.8 0 0 0 11.2 3.5H5.3A1.8 1.8 0 0 0 3.5 5.3v5.9A1.8 1.8 0 0 0 5.3 13H7";
    private static final String ICON_PENCIL = "M12.5 4.5l3 3M3.5 16.5l.8-3.4L13.4 4a1.4 1.4 0 0 1 2 0l.6.6a1.4 1.4 0 0 1 0 2l-9.1 9.1z";
    private static final String ICON_FOLDER = "M2.75 5.75A1.5 1.5 0 0 1 4.25 4.25h3.3l1.7 1.9h6.5a1.5 1.5 0 0 1 1.5 1.5v6.6a1.5 1.5 0 0 1-1.5 1.5H4.25a1.5 1.5 0 0 1-1.5-1.5z";

    private void build() {
        // ---- левая панель ----
        // кнопка «скрыть / показать панель запросов» живёт в шапке окна
        collapseSide.setGraphic(icon(ICON_PANEL, "icon-line"));
        collapseSide.getStyleClass().add("win-tool");
        collapseSide.setFocusTraversable(false);
        settingsBtn.setGraphic(icon("M3 5.5h6M13 5.5h4M3 10h2M9 10h8M3 14.5h8M15 14.5h2M11 3.5v4M7 8v4M13 12.5v4", "icon-line")); // «ползунки»
        settingsBtn.getStyleClass().add("win-tool");
        settingsBtn.setFocusTraversable(false);
        settingsBtn.setTooltip(new Tooltip(Ui.k("Настройки (Ctrl+,)")));
        aboutBtn.setGraphic(icon("M10 2.8a7.2 7.2 0 1 0 0 14.4a7.2 7.2 0 1 0 0-14.4zM10 9v5.2M10 6.1v.2", "icon-line"));
        aboutBtn.getStyleClass().add("win-tool");
        aboutBtn.setFocusTraversable(false);
        aboutBtn.setTooltip(new Tooltip("О программе"));
        HBox.setHgrow(tabColl, Priority.ALWAYS);
        HBox.setHgrow(tabHist, Priority.ALWAYS);
        tabColl.setMaxWidth(Double.MAX_VALUE);
        tabHist.setMaxWidth(Double.MAX_VALUE);
        HBox sideTabs = Ui.cls(new HBox(4, tabColl, tabHist), "side-tabs");
        sideSearch.setPromptText(Ui.k("Поиск запросов (Ctrl+K)"));
        sideSearch.getStyleClass().add("side-search");
        sideSearchClear.setVisible(false);
        sideSearchClear.setTooltip(new Tooltip("Очистить (Esc)"));
        sideSearchClear.setFocusTraversable(false);
        StackPane searchBox = new StackPane(sideSearch, sideSearchClear);
        StackPane.setAlignment(sideSearchClear, Pos.CENTER_RIGHT);
        sideCount.setVisible(false);
        sideCount.setManaged(false);
        VBox searchWrap = Ui.cls(new VBox(4, searchBox, sideCount), "side-search-box");
        sideList.getStyleClass().add("side-list");
        ScrollThumb.gap(sideList);
        sideList.setCellFactory(l -> new SideCell());
        sideList.setPlaceholder(new Label(""));
        VBox.setVgrow(sideList, Priority.ALWAYS);
        for (Button b : List.of(newReq, newFolder, importCurl)) { HBox.setHgrow(b, Priority.ALWAYS); b.setMaxWidth(Double.MAX_VALUE); }
        newReq.setTooltip(new Tooltip("Новый запрос"));
        newFolder.setTooltip(new Tooltip("Создать папку"));
        // кнопки переносятся на вторую строку, если панель узкая: подписи всегда видны целиком
        for (Button ab : List.of(newReq, newFolder, importCurl)) ab.setMinWidth(Region.USE_PREF_SIZE);
        FlowPane actions = Ui.cls(new FlowPane(6, 6, newReq, newFolder, importCurl), "side-actions");
        sidebar.getChildren().addAll(sideTabs, searchWrap, sideList, actions);
        sidebar.getStyleClass().add("aside");
        sidebar.setMinWidth(180);
        sidebar.setMaxWidth(640);

        // ---- верхние строки ----
        nameField.setPromptText("Название запроса (необязательно)");
        nameField.getStyleClass().add("name-field");
        HBox.setHgrow(nameField, Priority.ALWAYS);
        revertBtn.setTooltip(new Tooltip("Отменить несохранённые изменения этого запроса"));
        dirtyBadge.getStyleClass().add("dirty-badge");
        dirtyBadge.setTooltip(new Tooltip(Ui.k("Есть изменения, которых нет в коллекции. Сохранить: Ctrl+S")));
        crumbs.getStyleClass().add("crumbs");
        crumbs.setAlignment(Pos.CENTER_LEFT);
        HBox bar1 = Ui.cls(new HBox(8, nameField, revertBtn, dirtyBadge), "bar", "bar-top");
        bar1.setAlignment(Pos.CENTER_LEFT);

        methodBox.getItems().setAll(METHODS);
        methodBox.getStyleClass().add("method-box");
        methodBox.setCellFactory(l -> new ListCell<>() {
            @Override protected void updateItem(String s, boolean empty) {
                super.updateItem(s, empty);
                getStyleClass().removeIf(c -> c.startsWith("m-"));
                setText(empty ? null : s);
                if (!empty && s != null) getStyleClass().add("m-" + s);
            }
        });
        methodBox.setButtonCell(methodBox.getCellFactory().call(null));
        urlField.setPromptText("https://api.example.com/path?x=1  (можно {{переменные}})");
        urlField.getStyleClass().addAll("mono", "url-field");
        HBox.setHgrow(urlField, Priority.ALWAYS);
        sendBtn.setTooltip(new Tooltip(Ui.k("Отправить (Ctrl+Enter)")));
        saveBtn.setTooltip(new Tooltip(Ui.k("Сохранить запрос (Ctrl+S)")));
        cloneBtn.setTooltip(new Tooltip("Создать копию текущего запроса"));
        Label saveDot = Ui.cls(new Label("●"), "save-dot");
        saveBtn.setGraphic(saveDot);
        saveBtn.setContentDisplay(ContentDisplay.RIGHT);
        saveDot.setVisible(false);
        saveDot.setManaged(false);
        envSel.getStyleClass().add("env-sel");
        envSel.setConverter(new StringConverter<>() {
            public String toString(String id) {
                Env e = id == null ? null : envById(id);
                return e == null ? "" : e.name;
            }
            public String fromString(String s) { return s; }
        });
        envSel.setMaxWidth(220);
        // кнопки не сжимаются до «…»: при нехватке места уже становятся поля названия и адреса
        for (Region r : List.of(revertBtn, dirtyBadge, sendBtn, saveBtn, cloneBtn, exportBtn, envEdit, copyRes, collapseSide, settingsBtn, aboutBtn)) {
            r.setMinWidth(Region.USE_PREF_SIZE);
        }
        methodBox.setMinWidth(108);
        envSel.setMinWidth(130);
        urlField.setMinWidth(160);
        nameField.setMinWidth(120);
        HBox bar2 = Ui.cls(new HBox(8, methodBox, urlField, sendBtn, saveBtn, cloneBtn, exportBtn, envSel, envEdit), "bar");
        bar2.setAlignment(Pos.CENTER_LEFT);
        // все элементы строки растягиваются до высоты самого высокого: поле адреса не ниже кнопок
        for (Node n : bar2.getChildren()) if (n instanceof Region r) r.setMaxHeight(Double.MAX_VALUE);

        // ---- запрос ----
        addTab(reqTabs, reqTabBtns, "params", "Параметры", cParams, () -> { reqTab = "params"; renderReqTab(); });
        addTab(reqTabs, reqTabBtns, "headers", "Заголовки", cHeaders, () -> { reqTab = "headers"; renderReqTab(); });
        addTab(reqTabs, reqTabBtns, "body", "Тело", null, () -> { reqTab = "body"; renderReqTab(); });
        addTab(reqTabs, reqTabBtns, "auth", "Авторизация", null, () -> { reqTab = "auth"; renderReqTab(); });
        addTab(reqTabs, reqTabBtns, "opts", "Опции", null, () -> { reqTab = "opts"; renderReqTab(); });
        VBox.setVgrow(reqContent, Priority.ALWAYS);
        VBox reqPane = Ui.cls(new VBox(reqTabs, reqContent), "pane");

        // ---- ответ ----
        addTab(resTabs, resTabBtns, "body", "Ответ", null, () -> { resTab = "body"; renderRes(); });
        addTab(resTabs, resTabBtns, "headers", "Заголовки", cResHeaders, () -> { resTab = "headers"; renderRes(); });
        addTab(resTabs, resTabBtns, "raw", "Сырой", null, () -> { resTab = "raw"; renderRes(); });
        Region rsp = new Region();
        HBox.setHgrow(rsp, Priority.ALWAYS);
        copyRes.setTooltip(new Tooltip("Копировать содержимое текущей вкладки"));
        // статус, время и размер — отдельной строкой под вкладками, а не в шапке: так они не налезают на кнопки
        resMeta.setAlignment(Pos.CENTER_LEFT);
        resMeta.getChildren().addListener((javafx.collections.ListChangeListener<Node>) c -> {
            boolean has = !resMeta.getChildren().isEmpty();
            resMeta.setVisible(has);
            resMeta.setManaged(has);
        });
        resMeta.setVisible(false);
        resMeta.setManaged(false);
        debugBtn.setTooltip(new Tooltip("Трафик: что реально ушло на сервер и что пришло в ответ (последняя отправка)"));
        debugBtn.setMinWidth(Region.USE_PREF_SIZE);
        exportBtn.setTooltip(new Tooltip("Экспорт запроса в код: cURL, PowerShell, fetch, Python, Java, HTTP"));
        HBox resButtons = new HBox(6, debugBtn, copyRes);
        resButtons.setAlignment(Pos.CENTER_LEFT);
        resTabs.getChildren().addAll(rsp, resButtons);
        VBox.setVgrow(resContent, Priority.ALWAYS);
        VBox.setVgrow(resEditor.node(), Priority.ALWAYS);
        resBox.getChildren().addAll(resEditor.node(), resStatus);
        resEditor.area().selectionProperty().addListener((o, a, b) -> updResStatus());
        VBox resPane = Ui.cls(new VBox(resTabs, resMeta, resContent), "pane");

        reqRes.getItems().addAll(reqPane, resPane);
        reqRes.getStyleClass().add("req-res");
        VBox.setVgrow(reqRes, Priority.ALWAYS);
        VBox mainPane = Ui.cls(new VBox(crumbs, bar1, bar2, reqRes), "main");

        mainSplit.getItems().addAll(sidebar, mainPane);
        SplitPane.setResizableWithParent(sidebar, false);
        mainSplit.getStyleClass().add("main-split");
        app.setCenter(mainSplit);

        loadbar.getStyleClass().add("loadbar");
        loadbar.setMaxWidth(Double.MAX_VALUE);
        loadbar.setVisible(false);
        loadbar.setMouseTransparent(true);
        overlay.getChildren().addAll(app, loadbar, find);
        StackPane.setAlignment(loadbar, Pos.TOP_CENTER);
        StackPane.setAlignment(find, Pos.TOP_RIGHT);
        overlay.getStyleClass().add("root-pane");

        bodyEditor.area().getStyleClass().add("body-editor");
        resEditor.area().getStyleClass().add("res-view");
        bodyEditor.setOnChange(t -> {
            cur.body = t;
            scheduleDirty();
            find.refresh();
        });
        bodyEditor.area().caretPositionProperty().addListener((o, a, b) -> updEdStatus());
        bodyEditor.area().selectionProperty().addListener((o, a, b) -> updEdStatus());
        bodyEditor.area().textProperty().addListener((o, a, b) -> updEdStatus());

        wire();
    }

    private void addTab(HBox box, Map<String, Button> map, String id, String text, Label cnt, Runnable action) {
        Button b = Ui.button(text, "tab");
        b.setMinWidth(Region.USE_PREF_SIZE);
        if (cnt != null) { b.setGraphic(cnt); b.setContentDisplay(ContentDisplay.RIGHT); }
        b.setOnAction(e -> action.run());
        b.setFocusTraversable(false);
        map.put(id, b);
        box.getChildren().add(b);
    }

    private static void activate(Map<String, Button> map, String id) {
        map.forEach((k, b) -> { b.getStyleClass().remove("active"); if (k.equals(id)) b.getStyleClass().add("active"); });
    }

    /** Выпадающий список с подписями: значения — коды, показываются подписи. */
    static ComboBox<String> choice(Map<String, String> options) {
        ComboBox<String> c = new ComboBox<>();
        c.getItems().setAll(options.keySet());
        c.setConverter(new StringConverter<>() {
            public String toString(String k) { return k == null ? "" : options.getOrDefault(k, k); }
            public String fromString(String s) { return s; }
        });
        return c;
    }

    static Map<String, String> ordered(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    // ---------- события ----------
    private void wire() {
        urlField.textProperty().addListener((o, a, b) -> {
            if (suppress) return;
            cur.url = b;
            cur.params = Logic.paramsFromUrl(b);
            if (reqTab.equals("params")) renderReqTab();
            counts();
            scheduleDirty();
        });
        methodBox.valueProperty().addListener((o, a, b) -> { if (!suppress && b != null) { cur.method = b; scheduleDirty(); } });
        nameField.textProperty().addListener((o, a, b) -> { if (!suppress) { cur.name = b; scheduleDirty(); renderCrumbs(); } });
        sendBtn.setOnAction(e -> send());
        saveBtn.setOnAction(e -> save());
        cloneBtn.setOnAction(e -> cloneCurrent());
        exportBtn.setOnAction(e -> showCodeExport());
        debugBtn.setOnAction(e -> showDebug());
        envEdit.setOnAction(e -> {
            if (envDialog == null) envDialog = new EnvDialog(this);
            envDialog.show();
        });
        envSel.valueProperty().addListener((o, a, b) -> {
            if (suppress || b == null) return;
            state.activeEnv = b;
            persist();
            if (reqTab.equals("auth")) renderReqTab();
        });
        newReq.setOnAction(e -> openRequest(newBlank(), null));
        newFolder.setOnAction(e -> newFolder());
        importCurl.setOnAction(e -> showCurl());
        revertBtn.setOnAction(e -> revert());
        tabColl.setOnAction(e -> { side = "coll"; renderSide(); });
        tabHist.setOnAction(e -> { side = "hist"; renderSide(); });
        sideSearch.textProperty().addListener((o, a, b) -> {
            sideQuery = b;
            sideSearchClear.setVisible(!b.isEmpty());
            renderSide();
        });
        sideSearchClear.setOnAction(e -> { sideSearch.clear(); sideSearch.requestFocus(); });
        sideSearch.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                e.consume();
                if (!sideSearch.getText().isEmpty()) sideSearch.clear(); else sideList.requestFocus();
            } else if (e.getCode() == KeyCode.ENTER) { // Enter открывает первый найденный запрос
                e.consume();
                for (Row r : sideList.getItems()) {
                    if (r.node != null && !r.node.folder()) { clickRow(r); break; }
                }
            }
        });
        // Перетаскивание на пустое место списка: в конец корня
        sideList.setOnDragOver(e -> {
            if (side.equals("coll") && dragId != null) { e.acceptTransferModes(TransferMode.MOVE); sideList.getStyleClass().add("drop-end"); }
            e.consume();
        });
        sideList.setOnDragExited(e -> sideList.getStyleClass().remove("drop-end"));
        sideList.setOnDragDropped(e -> {
            sideList.getStyleClass().remove("drop-end");
            if (!side.equals("coll") || dragId == null) return;
            Logic.Loc src = Logic.locate(state, dragId);
            dragId = null;
            if (src == null) return;
            src.arr().remove(src.i());
            state.tree.add(src.node());
            persist();
            renderSide();
            e.setDropCompleted(true);
            e.consume();
        });
        collapseSide.setOnAction(e -> {
            ui.collapsed = !ui.collapsed;
            saveUi();
            applyUi();
            if (!ui.collapsed) Platform.runLater(this::applySideWidth);
        });
        settingsBtn.setOnAction(e -> showSettings());
        aboutBtn.setOnAction(e -> showAbout());
        copyRes.setOnAction(e -> {
            String t = resText();
            if (t == null) { toast("Бинарное тело нельзя скопировать"); return; }
            if (t.isEmpty()) { toast("Копировать нечего: ответа ещё нет"); return; }
            if (Ui.copy(t)) {
                copyRes.setText("Скопировано ✓");
                PauseTransition p = new PauseTransition(Duration.millis(1400));
                p.setOnFinished(x -> copyRes.setText("Копировать"));
                p.play();
            } else toast("Не удалось скопировать: выделите текст вручную");
        });
        // ширина панели и доля запроса/ответа запоминаются
        mainSplit.getDividers().addListener((javafx.collections.ListChangeListener<SplitPane.Divider>) c -> watchDividers());
        watchDividers();
        reqRes.getDividers().get(0).positionProperty().addListener((o, a, b) -> {
            if (suppress) return;
            ui.split = clampR(b.doubleValue());
            persist();
        });
        dirtyTimer.setOnFinished(e -> updateDirty());
    }

    private void watchDividers() {
        if (mainSplit.getDividers().isEmpty()) return;
        mainSplit.getDividers().get(0).positionProperty().addListener((o, a, b) -> {
            if (suppress || !layoutReady || ui.collapsed || !mainSplit.getItems().contains(sidebar)) return;
            double w = b.doubleValue() * mainSplit.getWidth();
            if (w > 0) { ui.sideW = (double) Math.max(180, Math.min(640, Math.round(w))); persist(); }
        });
    }

    private void installKeys(Scene scene) {
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            boolean ctrl = e.isShortcutDown(), plain = ctrl && !e.isShiftDown() && !e.isAltDown();
            // коды клавиш не зависят от раскладки: Ctrl+S работает и в русской
            if (ctrl && e.getCode() == KeyCode.ENTER) { e.consume(); send(); }
            else if (plain && e.getCode() == KeyCode.S) { e.consume(); save(); }
            else if (plain && e.getCode() == KeyCode.F) { e.consume(); openFind(); }
            else if (plain && e.getCode() == KeyCode.COMMA) { e.consume(); showSettings(); } // как в VS Code
            else if (plain && e.getCode() == KeyCode.K) {
                e.consume();
                if (ui.collapsed) { ui.collapsed = false; saveUi(); applyUi(); Platform.runLater(this::applySideWidth); }
                sideSearch.requestFocus();
                sideSearch.selectAll();
            } else if (find.isOpen() && e.getCode() == KeyCode.F3) { e.consume(); find.step(e.isShiftDown() ? -1 : 1); }
            else if (plain && (e.getCode() == KeyCode.EQUALS || e.getCode() == KeyCode.PLUS || e.getCode() == KeyCode.ADD)) { e.consume(); zoom(1); }
            else if (plain && (e.getCode() == KeyCode.MINUS || e.getCode() == KeyCode.SUBTRACT)) { e.consume(); zoom(-1); }
            else if (plain && (e.getCode() == KeyCode.DIGIT0 || e.getCode() == KeyCode.NUMPAD0)) { e.consume(); zoom(0); }
            else if (e.isAltDown() && !e.isShortcutDown() && !e.isShiftDown() && e.getCode() == KeyCode.Z) { e.consume(); ui.wrap = !ui.wrap; saveUi(); applyUi(); } // как в VS Code
        });
        // правки отмечаются с небольшой задержкой после любого ввода
        scene.addEventFilter(KeyEvent.KEY_RELEASED, e -> scheduleDirty());
        scene.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> scheduleDirty());
    }

    private void openFind() {
        String sel = null;
        Node f = stage.getScene().getFocusOwner();
        if (f == bodyEditor.area()) sel = bodyEditor.selectedText();
        else if (f == resEditor.area()) sel = resEditor.selectedText();
        else if (f instanceof javafx.scene.control.TextInputControl t) sel = t.getSelectedText();
        find.open(sel);
    }

    /** Где искать: тело запроса (если открыто) и показанный ответ. */
    private List<CodeEditor> findTargets() {
        List<CodeEditor> l = new ArrayList<>();
        if (reqTab.equals("body") && !"none".equals(cur.bodyType)) l.add(bodyEditor);
        if (resContent.getChildren().contains(resBox)) l.add(resEditor);
        return l;
    }

    private void zoom(int dir) {
        double fs = ui.fontSize == null ? 13 : ui.fontSize;
        fs = dir == 0 ? 13 : Math.max(9, Math.min(24, fs + dir));
        ui.fontSize = fs;
        saveUi();
        applyFont();
    }

    private void applyFont() {
        double fs = ui.fontSize == null ? 13 : ui.fontSize;
        overlay.setStyle("-fx-font-size: " + fs + "px;");
    }

    // ---------- вид ----------
    private void saveUi() { state.ui = ui; persist(); }

    private static double clampR(double r) { return Math.max(0.12, Math.min(0.88, r)); }

    private void applyUi() {
        suppress = true;
        try {
            boolean has = mainSplit.getItems().contains(sidebar);
            if (ui.collapsed && has) mainSplit.getItems().remove(sidebar);
            if (!ui.collapsed && !has) mainSplit.getItems().add(0, sidebar);
            collapseSide.setTooltip(new Tooltip(ui.collapsed ? "Показать панель запросов" : "Скрыть панель запросов"));
            Orientation o = ui.horiz ? Orientation.HORIZONTAL : Orientation.VERTICAL;
            if (reqRes.getOrientation() != o) reqRes.setOrientation(o);
            bodyEditor.setWrap(ui.wrap);
            resEditor.setWrap(ui.wrap);
            bodyEditor.setLineNumbers(ui.lines);
            resEditor.setLineNumbers(ui.lines && !resEditorNoLines);
            applyFont();
            Ui.setTheme(ui.light());
        } finally {
            suppress = false;
        }
        Platform.runLater(this::updateMinSize);
    }

    /**
     * Меньше, чем нужно содержимому, окно не сжимается: иначе строка запроса выезжает за край и кнопки окна пропадают.
     * Считается по минимальной ширине и высоте корня (панель запросов, строка адреса, шапка) и не меньше MIN_W x MIN_H.
     */
    private void updateMinSize() {
        if (chrome == null) return;
        javafx.scene.Parent root = chrome.root();
        double w = Math.max(MIN_W, Math.ceil(root.minWidth(-1)) + 40); // с запасом: часть кнопок появляется позже
        double h = Math.max(MIN_H, Math.ceil(root.minHeight(-1)));
        if (stage.getMinWidth() != w) stage.setMinWidth(w);
        if (stage.getMinHeight() != h) stage.setMinHeight(h);
    }

    /**
     * На весь экран левая панель сама становится шире (около пятой части окна, не больше 480), при возврате — прежней.
     * Сохранённая ширина ui.sideW при этом не меняется.
     */
    private Double sideBeforeMax; // ширина панели до разворачивания: на весь экран она растягивается сама, потом возвращается

    private void widenSideForMaximized(boolean on) {
        if (on && sideBeforeMax == null) sideBeforeMax = ui.sideW;
        // панель запросов не растёт вместе с окном (resizableWithParent = false), поэтому ждём, пока окно перерисуется по новому размеру
        PauseTransition wait = new PauseTransition(Duration.millis(180));
        wait.setOnFinished(e -> {
            double total = mainSplit.getWidth();
            if (!on && sideBeforeMax != null) { ui.sideW = sideBeforeMax; sideBeforeMax = null; persist(); }
            if (ui.collapsed || !mainSplit.getItems().contains(sidebar) || total <= 0) return;
            double w = on ? Math.max(ui.sideW, Math.min(480, Math.round(total * 0.2))) : ui.sideW;
            mainSplit.setDividerPosition(0, Math.max(180, Math.min(640, w)) / total);
            // перестановку делит SplitPane уже при следующей раскладке: пока она идёт, ширину в настройки не пишем
            suppress = true;
            Platform.runLater(() -> Platform.runLater(() -> suppress = false));
        });
        wait.play();
    }

    private void applySideWidth() {
        if (ui.collapsed || mainSplit.getWidth() <= 0) return;
        suppress = true;
        mainSplit.setDividerPosition(0, Math.max(180, Math.min(640, ui.sideW)) / mainSplit.getWidth());
        suppress = false;
    }

    private void applySplit() {
        suppress = true;
        reqRes.setDividerPosition(0, clampR(ui.split));
        suppress = false;
    }

    // ---------- окружения ----------
    private Env envById(String id) {
        return state.envs.stream().filter(e -> e.id.equals(id)).findFirst().orElse(null);
    }

    private Env activeEnv() { return envById(state.activeEnv); }

    void renderEnvSel() {
        suppress = true;
        try {
            envSel.getItems().setAll(state.envs.stream().map(e -> e.id).toList());
            envSel.setValue(null);
            envSel.setValue(state.activeEnv);
        } finally {
            suppress = false;
        }
        if (reqTab.equals("auth")) renderReqTab();
    }

    private static final Map<String, String> PLACEMENTS = Map.of("header", "заголовок", "query", "параметр URL", "cookie", "cookie", "placeholder", "подстановка {{access_token}}");

    private static String placeLabel(EnvAuth a) {
        String m = a.placement == null ? "header" : a.placement;
        if (m.equals("header")) return "заголовок " + (a.header.trim().isEmpty() ? "Authorization" : a.header.trim());
        if (m.equals("placeholder")) return "только подстановка {{access_token}} (добавьте её в нужное место запроса)";
        return PLACEMENTS.getOrDefault(m, m) + " «" + (a.paramName.trim().isEmpty() ? "access_token" : a.paramName.trim()) + "»";
    }

    // ---------- левая панель ----------
    /** Строка списка: подпись раздела, черновик, узел дерева, запись истории или пустое сообщение. */
    private record Row(String kind, Item node, int depth, String text) {}

    private List<String> queryTokens() {
        List<String> t = new ArrayList<>();
        for (String s : sideQuery.trim().toLowerCase(Locale.ROOT).split("\\s+")) if (!s.isEmpty()) t.add(s);
        return t;
    }

    private static boolean matches(String hay, List<String> tokens) {
        for (String t : tokens) if (!hay.contains(t)) return false;
        return true;
    }

    private static String hay(Item n) {
        String body = n.body == null ? "" : n.body.length() > 20000 ? n.body.substring(0, 20000) : n.body;
        return (nz(n.name) + " " + nz(n.method) + " " + nz(n.url) + " " + body).toLowerCase(Locale.ROOT);
    }

    private static String nz(String s) { return s == null ? "" : s; }

    /** Папка показывается, если подошла сама (тогда целиком) или в ней есть подошедшие запросы. */
    private boolean collectVisible(List<Item> list, List<String> tokens, Set<String> out, boolean ancestorMatched) {
        boolean any = false;
        for (Item n : list) {
            if (n.folder()) {
                boolean self = matches(nz(n.name).toLowerCase(Locale.ROOT), tokens);
                boolean child = collectVisible(n.children, tokens, out, ancestorMatched || self);
                if (self || child || ancestorMatched) { out.add(n.id); any = true; }
            } else if (ancestorMatched || matches(hay(n), tokens)) { out.add(n.id); any = true; }
        }
        return any;
    }

    private int countVisibleReqs(List<Item> list) {
        int n = 0;
        for (Item x : list) n += x.folder() ? countVisibleReqs(x.children) : (visibleIds.contains(x.id) ? 1 : 0);
        return n;
    }

    /** Путь запроса над строкой названия: Коллекции › Папка › Запрос (для несохранённого — «Черновик»). */
    private void renderCrumbs() {
        List<String> path = new ArrayList<>();
        Logic.Loc loc = cur == null ? null : Logic.locate(state, cur.id);
        if (loc != null) {
            Deque<String> chain = new ArrayDeque<>();
            findPath(state.tree, cur.id, chain);
            path.addAll(chain);
        }
        crumbs.getChildren().clear();
        crumbs.getChildren().add(Ui.cls(new Label(loc != null ? "Коллекции" : "Черновик"), "crumb"));
        for (int i = 0; i < path.size(); i++) {
            crumbs.getChildren().add(Ui.cls(new Label("›"), "crumb-sep"));
            boolean last = i == path.size() - 1;
            String t = last && cur != null && notBlank(cur.name) ? cur.name : path.get(i);
            Label l = Ui.cls(new Label(t), last ? "crumb-last" : "crumb");
            l.setMinWidth(0);
            crumbs.getChildren().add(l);
        }
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }

    /** Названия узлов от корня коллекции до запроса с этим id (последний — сам запрос). */
    private static boolean findPath(List<Item> list, String id, Deque<String> out) {
        for (Item n : list) {
            String label = notBlank(n.name) ? n.name : notBlank(n.url) ? n.url : "без названия";
            if (id.equals(n.id)) { out.addLast(label); return true; }
            if (n.folder() && n.children != null) {
                out.addLast(label);
                if (findPath(n.children, id, out)) return true;
                out.removeLast();
            }
        }
        return false;
    }

    void renderSide() {
        renderCrumbs();
        tabColl.getStyleClass().remove("primary");
        tabHist.getStyleClass().remove("primary");
        (side.equals("coll") ? tabColl : tabHist).getStyleClass().add("primary");
        newFolder.setVisible(side.equals("coll"));
        newFolder.setManaged(side.equals("coll"));
        curTokens = queryTokens();
        visibleIds = null;
        boolean showCount = !curTokens.isEmpty();
        List<Row> rows = new ArrayList<>();
        String countText = "";
        if (side.equals("coll")) {
            if (state.tree.isEmpty()) {
                showCount = false;
                addDraftRows(rows);
                rows.add(new Row("empty", null, 0, "Сохранённых запросов нет"));
            } else {
                boolean nothing = false;
                addDraftRows(rows);
                if (!curTokens.isEmpty()) {
                    visibleIds = new HashSet<>();
                    collectVisible(state.tree, curTokens, visibleIds, false);
                    countText = "Найдено: " + countVisibleReqs(state.tree);
                    if (visibleIds.isEmpty()) { rows.add(new Row("empty", null, 0, "Ничего не найдено")); nothing = true; }
                }
                if (!nothing) treeRows(state.tree, 0, rows);
            }
        } else {
            List<Item> hist = new ArrayList<>();
            for (Item r : state.history) if (curTokens.isEmpty() || matches(hay(r), curTokens)) hist.add(r);
            if (!curTokens.isEmpty()) countText = "Найдено: " + hist.size();
            if (state.history.isEmpty()) { showCount = false; rows.add(new Row("empty", null, 0, "История пуста")); }
            else if (hist.isEmpty()) rows.add(new Row("empty", null, 0, "Ничего не найдено"));
            else for (Item r : hist) rows.add(new Row("hist", r, 0, null));
        }
        sideCount.setText(countText);
        sideCount.setVisible(showCount);
        sideCount.setManaged(showCount);
        sideList.getItems().setAll(rows);
    }

    /** Раздел «Черновики»: новые запросы с правками, которых ещё нет в коллекции. */
    private void addDraftRows(List<Row> rows) {
        if (!curTokens.isEmpty()) return;
        List<Item> loose = new ArrayList<>();
        for (Item d : drafts.values()) if (Logic.locate(state, d.id) == null) loose.add(d);
        if (loose.isEmpty()) return;
        rows.add(new Row("label", null, 0, "Черновики"));
        for (Item d : loose) rows.add(new Row("draft", d, 0, null));
    }

    private void treeRows(List<Item> list, int depth, List<Row> out) {
        for (Item n : list) {
            if (visibleIds != null && !visibleIds.contains(n.id)) continue;
            out.add(new Row("node", n, depth, null));
            boolean open = visibleIds != null || Boolean.TRUE.equals(n.open); // при поиске найденное раскрыто
            if (n.folder() && open) treeRows(n.children, depth + 1, out);
        }
    }

    /** Есть ли у узла несохранённые правки: у запроса — свои (открытые или отложенные), у папки — у любого запроса внутри. */
    private boolean rowDirty(Item n) {
        if (n.folder()) {
            if (n.children == null) return false;
            for (Item c : n.children) if (rowDirty(c)) return true;
            return false;
        }
        return drafts.containsKey(n.id) || (dirtyState && n.id.equals(cur.id));
    }

    private void clickRow(Row r) {
        Item node = r.node;
        switch (r.kind) {
            case "hist" -> {
                Item next = Json.copy(node, Item.class).fix();
                next.id = Model.uid();
                next.name = "";
                next.type = null;
                openRequest(next, "history");
            }
            case "draft" -> {
                Item next = Json.copy(node, Item.class).fix();
                next.type = null;
                openRequest(next, null);
            }
            case "node" -> {
                if (node.folder()) {
                    if (visibleIds == null) { node.open = !Boolean.TRUE.equals(node.open); persist(); }
                    selFolder = node.id;
                    renderSide();
                    return;
                }
                Logic.Loc loc = Logic.locate(state, node.id);
                selFolder = loc != null && loc.parent() != null ? loc.parent().id : null;
                if (node.id.equals(cur.id)) return; // этот запрос уже открыт: правки остаются как есть
                Item next = Json.copy(node, Item.class).fix();
                next.type = null;
                openRequest(next, null);
            }
            default -> { }
        }
    }

    private void deleteRow(Row r) {
        Item node = r.node;
        switch (r.kind) {
            case "hist" -> { state.history.remove(node); persist(); renderSide(); }
            case "draft" -> {
                if (!Ui.confirm(stage, "Отбросить несохранённый запрос «" + reqLabel(node) + "»?", "Отбросить", "Отмена")) return;
                drafts.remove(node.id);
                draftBase.remove(node.id);
                renderSide();
            }
            case "node" -> {
                if (node.folder() && !node.children.isEmpty()
                        && !Ui.confirm(stage, "Удалить папку «" + node.name + "» вместе с содержимым (" + Logic.countReqs(node) + " запр.)?", "Удалить", "Отмена")) return;
                Logic.Loc loc = Logic.locate(state, node.id);
                if (loc == null) return;
                loc.arr().remove(loc.i());
                dropDrafts(node);
                if (node.id.equals(selFolder)) selFolder = null;
                persist();
                renderSide();
            }
            default -> { }
        }
    }

    /** Удалённый из коллекции запрос (или всё в удалённой папке) больше не держит отложенных правок. */
    private void dropDrafts(Item node) {
        drafts.remove(node.id);
        draftBase.remove(node.id);
        if (node.folder() && node.children != null) for (Item c : node.children) dropDrafts(c);
    }

    private void duplicate(Item node) {
        Logic.Loc loc = Logic.locate(state, node.id);
        if (loc == null) return;
        Item copy = Logic.cloneNode(loc.node());
        loc.arr().add(loc.i() + 1, copy);
        persist();
        startRename(copy); // сразу даём переименовать копию
    }

    private void startRename(Item node) {
        renamingId = node.id;
        renderSide();
        for (int i = 0; i < sideList.getItems().size(); i++) {
            if (sideList.getItems().get(i).node == node) { sideList.scrollTo(Math.max(0, i - 3)); break; }
        }
    }

    private void finishRename(Item node, String value, boolean ok) {
        if (renamingId == null) return;
        renamingId = null;
        String v = value == null ? "" : value.trim();
        if (ok && !v.isEmpty()) {
            node.name = v;
            if (node.id.equals(cur.id)) {
                cur.name = v;
                suppress = true;
                nameField.setText(v);
                suppress = false;
            }
            persist();
        }
        renderSide();
        sideList.requestFocus();
    }

    private void moveNode(String srcId, String targetId, String zone) {
        Logic.Loc src = Logic.locate(state, srcId);
        if (src == null || srcId.equals(targetId)) return;
        if (src.node().folder() && Logic.containsId(src.node(), targetId)) { toast("Нельзя переместить папку внутрь самой себя"); return; }
        src.arr().remove(src.i());
        Logic.Loc dst = Logic.locate(state, targetId);
        if (dst == null) state.tree.add(src.node());
        else if (zone.equals("into")) { dst.node().children.add(src.node()); dst.node().open = true; }
        else dst.arr().add(zone.equals("before") ? dst.i() : dst.i() + 1, src.node());
        persist();
        renderSide();
    }

    private void newFolder() {
        Item f = Item.folder("Новая папка");
        Logic.Loc sel = selFolder == null ? null : Logic.locate(state, selFolder);
        if (sel != null && sel.node().folder()) { sel.node().children.add(f); sel.node().open = true; } else state.tree.add(f);
        selFolder = f.id;
        side = "coll";
        persist();
        startRename(f);
    }

    /** Ячейка списка слева. */
    private final class SideCell extends ListCell<Row> {
        private String zone;

        SideCell() {
            setPrefWidth(0); // ячейка не шире списка: длинные названия обрезаются, а не прокручиваются вбок
            setOnMouseClicked(e -> {
                Row r = getItem();
                if (r == null || r.node == null || e.getButton() != MouseButton.PRIMARY) return;
                if (renamingId != null && renamingId.equals(r.node.id)) return;
                if (e.getClickCount() == 2 && r.kind.equals("node")) { startRename(r.node); return; }
                if (e.getClickCount() == 1) clickRow(r);
            });
            setOnDragDetected(e -> {
                Row r = getItem();
                if (r == null || !"node".equals(r.kind) || visibleIds != null || renamingId != null) return; // в отфильтрованном списке порядок не меняем
                dragId = r.node.id;
                var db = startDragAndDrop(TransferMode.MOVE);
                ClipboardContent c = new ClipboardContent();
                c.putString(r.node.id);
                db.setContent(c);
                e.consume();
            });
            setOnDragOver(this::dragOver);
            setOnDragExited(e -> clearDrop());
            setOnDragDropped(e -> {
                Row r = getItem();
                String z = zone;
                clearDrop();
                if (dragId == null || r == null || !"node".equals(r.kind)) return;
                String id = dragId;
                dragId = null;
                moveNode(id, r.node.id, z == null ? "after" : z);
                e.setDropCompleted(true);
                e.consume();
            });
            setOnDragDone(e -> { dragId = null; clearDrop(); });
        }

        private void dragOver(DragEvent e) {
            Row r = getItem();
            if (dragId == null || r == null || !"node".equals(r.kind) || dragId.equals(r.node.id)) return;
            e.acceptTransferModes(TransferMode.MOVE);
            double y = e.getY() / Math.max(1, getHeight());
            String z = r.node.folder() ? (y < 0.25 ? "before" : y > 0.75 ? "after" : "into") : (y < 0.5 ? "before" : "after");
            if (!z.equals(zone)) {
                clearDrop();
                zone = z;
                getStyleClass().add("drop-" + z);
            }
            e.consume();
        }

        private void clearDrop() {
            zone = null;
            getStyleClass().removeAll("drop-before", "drop-after", "drop-into");
        }

        @Override protected void updateItem(Row r, boolean empty) {
            super.updateItem(r, empty);
            getStyleClass().removeAll("item", "folder", "active", "sel", "dirty", "sect-label", "empty-row");
            setText(null);
            setGraphic(null);
            setTooltip(null);
            if (empty || r == null) return;
            switch (r.kind) {
                case "label" -> { getStyleClass().add("sect-label"); setText(r.text.toUpperCase(Locale.ROOT)); }
                case "empty" -> { getStyleClass().add("empty-row"); setText(r.text); }
                default -> buildItem(r);
            }
        }

        private void buildItem(Row r) {
            Item n = r.node;
            boolean folder = n.folder();
            getStyleClass().add("item");
            if (folder) getStyleClass().add("folder");
            if (!folder && n.id.equals(cur.id)) getStyleClass().add("active");
            if (folder && n.id.equals(selFolder)) getStyleClass().add("sel");
            HBox box = new HBox(6);
            box.setAlignment(Pos.CENTER_LEFT);
            box.setPadding(new javafx.geometry.Insets(0, 0, 0, r.depth * 16));
            if (folder) {
                boolean open = visibleIds != null || Boolean.TRUE.equals(n.open);
                SVGPath chev = icon(ICON_CHEVRON, "icon-line");
                StackPane cw = Ui.cls(new StackPane(chev), "chev");
                if (open) cw.setRotate(90);
                box.getChildren().addAll(cw, Ui.cls(new StackPane(icon(ICON_FOLDER, "icon-folder")), "ficon"));
            } else {
                Label m = Ui.cls(new Label(n.method), "method", "m-" + n.method);
                m.setMinWidth(Region.USE_PREF_SIZE);
                box.getChildren().add(m);
            }
            Node nameNode;
            if (renamingId != null && renamingId.equals(n.id)) {
                TextField in = new TextField(nz(n.name));
                in.getStyleClass().add("rename");
                boolean[] done = {false};
                in.setOnAction(e -> { if (!done[0]) { done[0] = true; finishRename(n, in.getText(), true); } });
                in.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
                    if (e.getCode() == KeyCode.ESCAPE) { e.consume(); if (!done[0]) { done[0] = true; finishRename(n, null, false); } }
                });
                in.focusedProperty().addListener((o, a, b) -> { if (!b && !done[0]) { done[0] = true; finishRename(n, in.getText(), true); } });
                Platform.runLater(() -> { in.requestFocus(); in.selectAll(); });
                nameNode = in;
            } else if (!folder && visibleIds != null && !nz(n.name).isEmpty()) {
                VBox two = new VBox(marked(n.name, "name"), marked(nz(n.url), "sub"));
                nameNode = two;
            } else {
                String text = folder ? nz(n.name) : !nz(n.name).isEmpty() ? n.name : !nz(n.url).isEmpty() ? n.url : "без названия";
                nameNode = marked(text, "name");
            }
            HBox.setHgrow(nameNode, Priority.ALWAYS);
            if (nameNode instanceof Region reg) { reg.setMinWidth(0); reg.setMaxWidth(Double.MAX_VALUE); }
            box.getChildren().add(nameNode);
            if (rowDirty(n) && !r.kind.equals("hist")) {
                getStyleClass().add("dirty");
                box.getChildren().add(Ui.cls(new Label("●"), "dirty-dot"));
            }
            if (folder) box.getChildren().add(Ui.cls(new Label(String.valueOf(Logic.countReqs(n))), "cnt"));
            if (r.kind.equals("node")) {
                Button dup = actBtn(icon(ICON_COPY, "icon-line"), "Клонировать", () -> duplicate(n));
                Button ren = actBtn(icon(ICON_PENCIL, "icon-line"), "Переименовать (или двойной клик)", () -> startRename(n));
                box.getChildren().addAll(dup, ren);
            }
            box.getChildren().add(actBtn(null, "Удалить", () -> deleteRow(r)));
            // сжимается только название
            for (Node c : box.getChildren()) if (c != nameNode && c instanceof Region reg) reg.setMinWidth(Region.USE_PREF_SIZE);
            box.setMinWidth(0);
            setGraphic(box);
            if (!folder) setTooltip(new Tooltip(n.method + " " + nz(n.url)));
        }

        private Button actBtn(Node graphic, String tip, Runnable action) {
            Button b = new Button(graphic == null ? "✕" : null, graphic);
            b.getStyleClass().addAll("act", graphic == null ? "x" : "ico");
            b.setTooltip(new Tooltip(tip));
            b.setFocusTraversable(false);
            b.setOnAction(e -> { e.consume(); action.run(); });
            b.addEventFilter(MouseEvent.MOUSE_CLICKED, MouseEvent::consume);
            return b;
        }
    }

    /** Текст с отмеченными словами поиска. */
    private HBox marked(String text, String cls) {
        HBox h = new HBox();
        h.getStyleClass().add(cls);
        h.setMinWidth(0);
        h.setAlignment(Pos.CENTER_LEFT);
        if (curTokens.isEmpty()) {
            Label l = new Label(text);
            l.setMinWidth(0);
            h.getChildren().add(l);
            return h;
        }
        StringBuilder re = new StringBuilder();
        for (String t : curTokens) { if (re.length() > 0) re.append('|'); re.append(Pattern.quote(t)); }
        Matcher m = Pattern.compile(re.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(text);
        int last = 0;
        while (m.find()) {
            if (m.end() == m.start()) continue;
            if (m.start() > last) { Label l = new Label(text.substring(last, m.start())); l.setMinWidth(Region.USE_PREF_SIZE); h.getChildren().add(l); }
            Label mk = Ui.cls(new Label(m.group()), "mark");
            mk.setMinWidth(Region.USE_PREF_SIZE);
            h.getChildren().add(mk);
            last = m.end();
        }
        if (last < text.length()) { Label l = new Label(text.substring(last)); l.setMinWidth(0); h.getChildren().add(l); }
        return h;
    }

    // ---------- вкладки запроса ----------
    private void counts() {
        long p = cur.params.stream().filter(r -> r.enabled() && !r.key().isEmpty()).count();
        long h = cur.headers.stream().filter(r -> r.enabled() && !r.key().isEmpty()).count();
        cParams.setText(p == 0 ? "" : String.valueOf(p));
        cHeaders.setText(h == 0 ? "" : String.valueOf(h));
    }

    private void syncUrlFromParams() {
        cur.url = Logic.urlFromParams(cur.url, cur.params);
        suppress = true;
        urlField.setText(cur.url);
        suppress = false;
    }

    private static ScrollPane scroll(Node n) {
        ScrollPane s = new ScrollPane(n);
        s.setFitToWidth(true);
        s.getStyleClass().add("plain-scroll");
        ScrollThumb.gap(s);
        return s;
    }

    private static HBox row(Node... nodes) {
        HBox h = Ui.cls(new HBox(8, nodes), "row");
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    private static Label muted(String t) { return Ui.cls(new Label(t), "muted"); }

    private void renderReqTab() {
        activate(reqTabBtns, reqTab);
        Node content;
        switch (reqTab) {
            case "params" -> content = scroll(new KvTable(cur.params, () -> { syncUrlFromParams(); counts(); scheduleDirty(); }, true));
            case "headers" -> content = scroll(new KvTable(cur.headers, () -> { counts(); scheduleDirty(); }, true));
            case "body" -> content = bodyTab();
            case "auth" -> content = authTab();
            default -> content = optsTab();
        }
        reqContent.getChildren().setAll(content);
        counts();
        find.refresh();
    }

    private Node bodyTab() {
        ComboBox<String> type = choice(ordered("none", "нет", "json", "JSON", "xml", "XML", "soap", "SOAP (text/xml)", "text", "текст", "form", "x-www-form-urlencoded"));
        type.setValue(cur.bodyType);
        type.valueProperty().addListener((o, a, b) -> { if (b != null) { cur.bodyType = b; scheduleDirty(); renderReqTab(); } });
        Button fmt = Ui.button("Форматировать", "small");
        HBox top = row(muted("Тип"), type, fmt);
        // кнопка растягивается до высоты соседнего списка
        fmt.setMaxHeight(Double.MAX_VALUE);
        type.setMaxHeight(Double.MAX_VALUE);
        bodyEditor.setOnFormat(fmt::fire); // Shift+Alt+F в поле — то же, что кнопка
        fmt.setOnAction(e -> {
            String p;
            if (Logic.isXml(cur)) {
                p = Pretty.xml(cur.body);
                if (p == null) { toast("Это не корректный XML"); return; }
            } else {
                p = Json.pretty(cur.body);
                if (p == null) { toast("Это не корректный JSON"); return; }
            }
            // замена через поле: отмена (Ctrl+Z) вернёт прежний текст
            bodyEditor.area().replaceText(0, bodyEditor.area().getLength(), p);
            bodyEditor.refresh();
        });
        VBox box = Ui.cls(new VBox(8, top), "col");
        if (!"none".equals(cur.bodyType)) {
            Highlight.Mode mode = "json".equals(cur.bodyType) ? Highlight.Mode.JSON : Logic.isXml(cur) ? Highlight.Mode.XML : Highlight.Mode.VARS;
            bodyEditor.setPlaceholder("form".equals(cur.bodyType) ? "a=1&b=2" : Logic.isXml(cur) ? "<root/>" : "{ }");
            if (!bodyEditor.getText().equals(cur.body)) bodyEditor.setText(cur.body);
            bodyEditor.setMode(mode, true); // {{переменные}} подсвечиваются во всех типах тела
            Node ed = bodyEditor.node();
            VBox.setVgrow(ed, Priority.ALWAYS);
            box.getChildren().addAll(ed, edStatus);
            updEdStatus();
        }
        return box;
    }

    /** Строка состояния: позиция курсора, число строк и символов, размер выделения. */
    private void updEdStatus() {
        var a = bodyEditor.area();
        int line = a.getCurrentParagraph() + 1, col = a.getCaretColumn() + 1;
        int sel = a.getSelection().getLength();
        edStatus.setText("Строка " + line + ", столбец " + col + " · строк: " + a.getParagraphs().size() + " · символов: " + a.getLength()
                + (sel > 0 ? " · выделено: " + sel : ""));
    }

    private Node authTab() {
        ComboBox<String> type = choice(ordered("none", "нет", "bearer", "Bearer-токен", "basic", "Basic", "env", "Токен окружения (авто)"));
        type.setValue(cur.auth.type);
        VBox body = new VBox(8);
        Runnable draw = () -> {
            body.getChildren().clear();
            switch (cur.auth.type) {
                case "bearer" -> {
                    TextField t = new TextField(cur.auth.token);
                    t.getStyleClass().add("mono");
                    HBox.setHgrow(t, Priority.ALWAYS);
                    t.textProperty().addListener((o, a, b) -> { cur.auth.token = b; scheduleDirty(); });
                    body.getChildren().add(row(muted("Токен"), t));
                }
                case "basic" -> {
                    TextField u = new TextField(cur.auth.user);
                    PasswordField p = new PasswordField();
                    p.setText(cur.auth.pass);
                    u.textProperty().addListener((o, a, b) -> { cur.auth.user = b; scheduleDirty(); });
                    p.textProperty().addListener((o, a, b) -> { cur.auth.pass = b; scheduleDirty(); });
                    body.getChildren().add(row(muted("Логин"), u, muted("Пароль"), p));
                }
                case "env" -> {
                    Env env = activeEnv();
                    boolean ok = env != null && Boolean.TRUE.equals(env.auth().enabled) && Logic.findReq(state, env.auth().loginReqId) != null;
                    body.getChildren().add(Ui.hint(ok
                            ? "Используется токен окружения «" + env.name + "»: он получается входом и обновляется автоматически. Передаётся: " + placeLabel(env.auth()) + "."
                            : "В окружении «" + (env == null ? "" : env.name) + "» автоавторизация не настроена. Откройте «Окружения» и заполните раздел «Автоматическая авторизация»."));
                }
                default -> { }
            }
        };
        type.valueProperty().addListener((o, a, b) -> { if (b != null) { cur.auth.type = b; scheduleDirty(); draw.run(); } });
        draw.run();
        return scroll(new VBox(10, row(muted("Тип"), type), body));
    }

    private Node optsTab() {
        double t = cur.opts.timeout == null ? 60 : cur.opts.timeout;
        TextField timeout = new TextField(t == Math.rint(t) ? String.valueOf((long) t) : String.valueOf(t));
        timeout.setPrefWidth(90);
        timeout.textProperty().addListener((o, a, b) -> {
            double v;
            try { v = Double.parseDouble(b.trim().replace(',', '.')); } catch (NumberFormatException e) { v = 0; }
            cur.opts.timeout = v > 0 ? Math.min(v, 600) : 60.0;
            scheduleDirty();
        });
        CheckBox insecure = new CheckBox("Не проверять TLS-сертификат (для тестовых стендов)");
        insecure.setSelected(Boolean.TRUE.equals(cur.opts.insecure));
        insecure.selectedProperty().addListener((o, a, b) -> { cur.opts.insecure = b; scheduleDirty(); });
        // транспортная подпись ШЭП: только для тела XML или SOAP, ключ задаётся в окружении
        CheckBox chk = new CheckBox("Транспортная подпись ШЭП");
        chk.setTooltip(new Tooltip("Тело подписывается перед отправкой (WS-Security, ГОСТ). Ключ задаётся в окружении."));
        chk.setSelected(Boolean.TRUE.equals(cur.sign));
        chk.selectedProperty().addListener((o, a, b) -> { cur.sign = b; scheduleDirty(); });
        Button prev = Ui.button("Показать подписанный", "small");
        prev.setTooltip(new Tooltip("Показать, что уйдёт на сервер после подписи"));
        prev.setOnAction(e -> showSigned());
        boolean xml = Logic.isXml(cur);
        chk.setDisable(!xml);
        prev.setDisable(!xml);
        VBox box = new VBox(10, row(muted("Таймаут, с"), timeout), insecure, row(chk, prev));
        if (!xml) box.getChildren().add(Ui.hint("Подпись доступна для тела типа «XML» или «SOAP» (вкладка «Тело»)."));
        return scroll(box);
    }

    // ---------- несохранённые изменения ----------
    // Правки не теряются при переключении: запрос, с которого ушли, остаётся изменённым (drafts) с точкой в списке.
    // О сохранении спрашиваем только при закрытии окна.
    private boolean isDirty() {
        Logic.Loc loc = Logic.locate(state, cur.id);
        String now = Logic.norm(cur);
        if (loc != null && !loc.node().folder()) return !now.equals(Logic.norm(loc.node()));
        return unsavedBase == null || !now.equals(unsavedBase);
    }

    private void scheduleDirty() { dirtyTimer.playFromStart(); }

    private void updateDirty() {
        dirtyTimer.stop();
        if (state == null) return;
        dirtyState = isDirty();
        Logic.Loc loc = Logic.locate(state, cur.id);
        dirtyBadge.setVisible(dirtyState);
        dirtyBadge.setManaged(dirtyState);
        boolean rev = dirtyState && loc != null && !loc.node().folder();
        revertBtn.setVisible(rev);
        revertBtn.setManaged(rev);
        saveBtn.getStyleClass().remove("dirty");
        if (dirtyState) saveBtn.getStyleClass().add("dirty");
        saveBtn.getGraphic().setVisible(dirtyState);
        saveBtn.getGraphic().setManaged(dirtyState);
        stage.setTitle((dirtyState ? "● " : "") + "GoNets");
        if (renamingId == null) sideList.refresh(); // при переименовании ячейка с полем ввода не перестраивается
    }

    /** Запрос вне коллекции считается изменённым, только если его правили после открытия. */
    private void markLoaded() {
        Logic.Loc loc = Logic.locate(state, cur.id);
        unsavedBase = loc != null ? null : Logic.norm(cur);
        updateDirty();
    }

    /** Откладывает правки открытого запроса, чтобы вернуться к ним позже; о сохранении при переключении не спрашиваем. */
    private void stash() {
        updateDirty();
        if (dirtyState) {
            Item d = Json.copy(cur, Item.class);
            d.type = null;
            drafts.put(cur.id, d);
            if (Logic.locate(state, cur.id) == null) draftBase.put(cur.id, unsavedBase);
            else draftBase.remove(cur.id);
        } else {
            drafts.remove(cur.id);
            draftBase.remove(cur.id);
        }
    }

    /**
     * Открыть другой запрос. Правки текущего откладываются (в списке у него точка), у открываемого возвращаются отложенные.
     * mode: imported (cURL) — новый запрос считается несохранённым сразу.
     */
    private void openRequest(Item next, String mode) {
        stash();
        Item draft = drafts.remove(next.id);
        boolean hadBase = draftBase.containsKey(next.id);
        String base = draftBase.remove(next.id);
        cur = (draft != null ? draft : next).fix();
        loadCur();
        if (draft != null && hadBase) { unsavedBase = base; updateDirty(); }
        if ("imported".equals(mode)) { unsavedBase = null; updateDirty(); }
    }

    /** Новый пустой запрос с таймаутом из настроек. */
    private Item newBlank() {
        Item b = Item.blankReq();
        b.opts.timeout = ui == null || ui.defaultTimeout == null ? 60.0 : ui.defaultTimeout;
        return b;
    }

    /** Окно «Настройки» (шестерёнка в шапке, Ctrl+,). Всё применяется сразу. */
    private void showSettings() {
        // ---- вид ----
        ComboBox<String> theme = choice(ordered("dark", "Тёмная", "light", "Светлая"));
        theme.setValue(ui.theme);
        theme.valueProperty().addListener((o, a, b) -> { if (b != null) { ui.theme = b; saveUi(); applyUi(); } });
        Spinner<Integer> font = new Spinner<>(9, 24, (int) Math.round(ui.fontSize == null ? 13 : ui.fontSize));
        font.setPrefWidth(90);
        font.valueProperty().addListener((o, a, b) -> { ui.fontSize = (double) b; saveUi(); applyFont(); });
        CheckBox lines = new CheckBox("Номера строк в теле запроса и ответе");
        lines.setSelected(ui.lines);
        lines.selectedProperty().addListener((o, a, b) -> { ui.lines = b; saveUi(); applyUi(); });
        CheckBox wrap = new CheckBox(Ui.k("Переносить длинные строки (Alt+Z)"));
        wrap.setSelected(ui.wrap);
        wrap.selectedProperty().addListener((o, a, b) -> { ui.wrap = b; saveUi(); applyUi(); });
        ComboBox<String> layout = choice(ordered("bottom", "Снизу", "right", "Справа"));
        layout.setValue(ui.horiz ? "right" : "bottom");
        layout.valueProperty().addListener((o, a, b) -> {
            if (b == null) return;
            ui.horiz = b.equals("right");
            saveUi();
            applyUi();
            Platform.runLater(this::applySplit);
        });
        CheckBox showSide = new CheckBox("Показывать панель запросов");
        showSide.setSelected(!ui.collapsed);
        showSide.selectedProperty().addListener((o, a, b) -> {
            ui.collapsed = !b;
            saveUi();
            applyUi();
            if (b) Platform.runLater(this::applySideWidth);
        });

        // ---- запросы ----
        double t = ui.defaultTimeout;
        TextField timeout = new TextField(t == Math.rint(t) ? String.valueOf((long) t) : String.valueOf(t));
        timeout.setPrefWidth(90);
        timeout.textProperty().addListener((o, a, b) -> {
            try {
                double v = Double.parseDouble(b.trim().replace(',', '.'));
                if (v > 0) { ui.defaultTimeout = Math.min(v, 600); saveUi(); }
            } catch (NumberFormatException ignored) { }
        });
        CheckBox restore = new CheckBox("Открывать при запуске последний открытый запрос");
        restore.setSelected(Boolean.TRUE.equals(ui.restoreLast));
        restore.selectedProperty().addListener((o, a, b) -> { ui.restoreLast = b; saveUi(); });

        // ---- история ----
        Spinner<Integer> hist = new Spinner<>(0, 1000, ui.historyLimit, 10);
        hist.setEditable(true);
        hist.setPrefWidth(100);
        hist.valueProperty().addListener((o, a, b) -> {
            if (b == null) return;
            ui.historyLimit = b;
            while (state.history.size() > b) state.history.remove(state.history.size() - 1);
            saveUi();
            if (side.equals("hist")) renderSide();
        });
        Button clearHist = Ui.button("Очистить историю", "small");
        clearHist.setOnAction(e -> {
            if (state.history.isEmpty()) { toast("История и так пуста"); return; }
            if (!Ui.confirm(stage, "Удалить всю историю (" + state.history.size() + " запр.)?", "Удалить", "Отмена")) return;
            state.history.clear();
            persist();
            renderSide();
            toast("История очищена");
        });

        // ---- данные ----
        TextField path = new TextField(store.file().toString());
        path.setEditable(false);
        path.getStyleClass().add("mono");
        Button openDir = Ui.button("Открыть папку", "small");
        openDir.setMinWidth(Region.USE_PREF_SIZE);
        openDir.setOnAction(e -> bg.submit(() -> {
            try { java.awt.Desktop.getDesktop().open(store.dir().toFile()); }
            catch (Exception ex) { Platform.runLater(() -> toast("Не удалось открыть папку: " + ex.getMessage())); }
        }));
        HBox pathRow = new HBox(6, path, openDir);
        HBox.setHgrow(path, Priority.ALWAYS);
        pathRow.setAlignment(Pos.CENTER_LEFT);

        GridPane view = settingsGrid();
        view.addRow(0, muted("Тема"), theme);
        view.addRow(1, muted("Размер шрифта"), font);
        view.addRow(2, muted("Ответ"), layout);
        GridPane req = settingsGrid();
        req.addRow(0, muted("Таймаут новых запросов, с"), timeout);
        GridPane histGrid = settingsGrid();
        HBox histRow = new HBox(10, hist, clearHist);
        histRow.setAlignment(Pos.CENTER_LEFT);
        histGrid.addRow(0, muted("Хранить последних отправок"), histRow);

        VBox keys = new VBox(4);
        keys.getChildren().add(keyGroup("Общие"));
        for (String[] k : new String[][]{{"Ctrl+Enter", "Отправить запрос"}, {"Ctrl+S", "Сохранить"}, {"Ctrl+F", "Поиск в теле и ответе"},
                {"Ctrl+K", "Поиск запросов"}, {"Ctrl+,", "Настройки"}, {"Ctrl+=  /  Ctrl+-  /  Ctrl+0", "Размер шрифта"}, {"Alt+Z", "Перенос строк"}})
            keys.getChildren().add(keyRow(k[0], k[1]));
        keys.getChildren().add(keyGroup("В теле запроса"));
        for (String[] k : new String[][]{{"Ctrl+X / Ctrl+C", "Без выделения — строка целиком"}, {"Ctrl+Shift+K", "Удалить строку"}, {"Alt+↑ / Alt+↓", "Переместить строку"},
                {"Shift+Alt+↑ / ↓", "Продублировать строку"}, {"Tab / Shift+Tab", "Отступ"}, {"Ctrl+L", "Выделить строку"}, {"Ctrl+D", "Следующее совпадение"},
                {"Ctrl+/", "Комментарий (XML)"}, {"Shift+Alt+F", "Форматировать"}})
            keys.getChildren().add(keyRow(k[0], k[1]));

        // ---- защита мастер-паролем ----
        Label protState = Ui.hint("");
        protState.setWrapText(true);
        Button protSet = Ui.button("", "small"), protRemove = Ui.button("Снять защиту", "small");
        Runnable[] protRefresh = new Runnable[1];
        protRefresh[0] = () -> {
            boolean on = store.isProtected();
            protState.setText(on ? "Файл данных зашифрован (AES-256, ключ из мастер-пароля). Пароль спрашивается при запуске."
                    : "Файл данных хранится открытым текстом: токены, пароли и значения секретных переменных видны любому, кто получит файл.");
            protSet.setText(on ? "Сменить пароль…" : "Включить защиту паролем…");
            protRemove.setVisible(on);
            protRemove.setManaged(on);
        };
        protRefresh[0].run();
        protSet.setOnAction(e -> PasswordDialogs.setOrChange(stage, store, () -> { protRefresh[0].run(); toast(store.isProtected() ? "Защита включена" : "Готово"); }));
        protRemove.setOnAction(e -> PasswordDialogs.remove(stage, store, () -> { protRefresh[0].run(); toast("Защита снята"); }));
        HBox protRow = new HBox(8, protSet, protRemove);
        protRow.setAlignment(Pos.CENTER_LEFT);

        VBox content = new VBox(10,
                sectionTitle("Вид"), view, lines, wrap, showSide,
                sectionTitle("Запросы"), req, restore,
                sectionTitle("История"), histGrid,
                sectionTitle("Защита данных"), protState, protRow,
                sectionTitle("Данные"), Ui.hint("Коллекции, окружения и история хранятся в файле:"), pathRow,
                sectionTitle("Горячие клавиши"), keys);
        Button reset = Ui.button("Сбросить вид");
        reset.setTooltip(new Tooltip("Размер шрифта, ширина панели, доли запроса и ответа, перенос и номера строк — как по умолчанию"));
        Button done = Ui.button("Готово", "primary");
        Stage st = Ui.dialog(stage, "Настройки", content, Ui.buttons(reset, done), 640, 700);
        reset.setOnAction(e -> {
            ui.fontSize = 13.0;
            ui.sideW = 270.0;
            ui.split = 0.5;
            ui.wrap = true;
            ui.lines = true;
            ui.horiz = false;
            ui.collapsed = false;
            saveUi();
            applyUi();
            Platform.runLater(() -> { applySideWidth(); applySplit(); });
            st.close();
            toast("Вид сброшен");
        });
        done.setOnAction(e -> st.close());
        st.show();
    }

    private static Label keyGroup(String t) {
        Label l = Ui.cls(new Label(t), "muted");
        l.setPadding(new javafx.geometry.Insets(6, 0, 2, 0));
        return l;
    }

    /** Строка списка горячих клавиш: сочетание «клавишами» слева, действие справа. */
    private static HBox keyRow(String combo, String what) {
        HBox keysBox = new HBox(4);
        keysBox.setAlignment(Pos.CENTER_LEFT);
        keysBox.setMinWidth(230);
        keysBox.setPrefWidth(230);
        for (String part : combo.split("\\s+/\\s+")) {
            if (part.isBlank()) continue;
            if (keysBox.getChildren().size() > 0) keysBox.getChildren().add(Ui.cls(new Label("/"), "muted"));
            for (String key : part.split("\\+(?=.)")) keysBox.getChildren().add(Ui.cls(new Label(Ui.keyName(key.trim())), "kbd"));
        }
        HBox r = new HBox(10, keysBox, new Label(what));
        r.setAlignment(Pos.CENTER_LEFT);
        return r;
    }

    /** Версия из метаданных сборки (pom.properties в jar); при запуске из исходников файла нет. */
    private static String appVersion() {
        try (var in = MainWindow.class.getResourceAsStream("/META-INF/maven/kz.gonets/gonets/pom.properties")) {
            if (in != null) {
                java.util.Properties p = new java.util.Properties();
                p.load(in);
                String v = p.getProperty("version");
                if (v != null && !v.isBlank()) return v;
            }
        } catch (Exception ignored) { }
        return "1.0.1";
    }

    /** Окно «О программе»: что это, версия, окружение и данные. */
    private void showAbout() {
        Label title = Ui.cls(new Label("GoNets"), "about-title");
        Label ver = Ui.cls(new Label("Версия " + appVersion()), "muted");
        javafx.scene.image.ImageView logo = new javafx.scene.image.ImageView(Ui.icon());
        logo.setFitWidth(56);
        logo.setFitHeight(56);
        logo.setSmooth(true);
        VBox head = new VBox(2, title, ver);
        head.setAlignment(Pos.CENTER_LEFT);
        HBox top = new HBox(14, logo, head);
        top.setAlignment(Pos.CENTER_LEFT);

        Label what = Ui.hint("Лёгкий клиент HTTP-запросов для разработки и отладки API: коллекции с папками, история отправок, "
                + "окружения с переменными и секретами, автоматическая авторизация по токену, транспортная подпись ШЭП "
                + "(SOAP, ГОСТ, WS-Security), просмотр того, что реально ушло на сервер, и экспорт запроса в cURL, PowerShell, fetch, Python и Java. "
                + "Работает без браузера и серверной части, запросы отправляются напрямую с вашего компьютера.");
        what.setWrapText(true);

        int reqs = 0, folders = 0;
        for (Item n : state.tree) { if (n.folder()) { folders++; reqs += Logic.countReqs(n); folders += countFolders(n); } else reqs++; }
        GridPane info = settingsGrid();
        int r = 0;
        info.addRow(r++, muted("Запросов в коллекции"), new Label(reqs + (folders > 0 ? " (папок: " + folders + ")" : "")));
        info.addRow(r++, muted("Окружений"), new Label(String.valueOf(state.envs.size())));
        info.addRow(r++, muted("Защита данных"), new Label(store.isProtected() ? "включена (мастер-пароль)" : "выключена"));
        info.addRow(r++, muted("Файл данных"), pathLabel(store.file().toString()));
        info.addRow(r++, muted("Java"), new Label(System.getProperty("java.version", "?") + " · " + System.getProperty("java.vendor", "")));
        info.addRow(r++, muted("JavaFX"), new Label(System.getProperty("javafx.runtime.version", "?")));
        info.addRow(r++, muted("Система"), new Label(System.getProperty("os.name", "?") + " " + System.getProperty("os.version", "") + " · " + System.getProperty("os.arch", "")));
        Runtime rt = Runtime.getRuntime();
        info.addRow(r++, muted("Память"), new Label((rt.totalMemory() - rt.freeMemory()) / 1048576 + " МБ занято из " + rt.maxMemory() / 1048576 + " МБ"));

        Label libs = Ui.hint("Построено на JavaFX, RichTextFX (редактор), Jackson (JSON), Apache WSS4J и XML Security (подпись), "
                + "Калкан (НУЦ РК, ГОСТ). Данные хранятся локально и по желанию шифруются мастер-паролем.");
        libs.setWrapText(true);

        Button copyInfo = Ui.button("Копировать сведения");
        Button openDir = Ui.button("Открыть папку данных");
        openDir.setOnAction(e -> bg.submit(() -> {
            try { java.awt.Desktop.getDesktop().open(store.dir().toFile()); }
            catch (Exception ex) { Platform.runLater(() -> toast("Не удалось открыть папку: " + ex.getMessage())); }
        }));
        HBox tools = new HBox(8, copyInfo, openDir);
        VBox content = new VBox(12, top, what, sectionTitle("Сведения"), info, libs);
        Button done = Ui.button("Закрыть", "primary");
        Stage st = Ui.dialog(stage, "О программе", content, Ui.buttons(tools, done), 600, 580);
        copyInfo.setOnAction(e -> {
            StringBuilder b = new StringBuilder("GoNets " + appVersion() + "\n");
            for (Node n : info.getChildren()) if (n instanceof Label l) b.append(l.getText()).append(GridPane.getColumnIndex(l) == null || GridPane.getColumnIndex(l) == 0 ? ": " : "\n");
            Ui.toast(st, Ui.copy(b.toString().trim()) ? "Скопировано" : "Не удалось скопировать");
        });
        done.setOnAction(e -> st.close());
        st.show();
    }

    private static int countFolders(Item f) {
        int n = 0;
        for (Item c : f.children) if (c.folder()) n += 1 + countFolders(c);
        return n;
    }

    private static Label pathLabel(String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.getStyleClass().add("mono");
        l.setMaxWidth(380);
        return l;
    }

    private static GridPane settingsGrid() {
        GridPane g = new GridPane();
        g.setHgap(12);
        g.setVgap(8);
        ColumnConstraints c1 = new ColumnConstraints();
        c1.setMinWidth(200);
        g.getColumnConstraints().add(c1);
        return g;
    }

    private static Label sectionTitle(String t) {
        Label l = Ui.cls(new Label(t), "sect-title");
        l.setPadding(new javafx.geometry.Insets(8, 0, 0, 0));
        return l;
    }

    private void loadCur() {
        cur.fix();
        if (Logic.locate(state, cur.id) != null && !cur.id.equals(ui.lastReqId)) { ui.lastReqId = cur.id; saveUi(); } // для «открывать последний запрос»
        suppress = true;
        try {
            methodBox.setValue(cur.method);
            urlField.setText(cur.url);
            nameField.setText(cur.name == null ? "" : cur.name);
        } finally {
            suppress = false;
        }
        if (!bodyEditor.getText().equals(cur.body)) bodyEditor.setText(cur.body);
        renderReqTab();
        renderSide();
        setResult(null);
        markLoaded();
    }

    private void save() {
        cur.name = nz(cur.name).trim();
        if (cur.name.isEmpty()) {
            String u = cur.url.replaceFirst("^https?://", "");
            cur.name = u.isEmpty() ? "Новый запрос" : u.substring(0, Math.min(60, u.length()));
        }
        suppress = true;
        nameField.setText(cur.name);
        suppress = false;
        Item copy = Json.copy(cur, Item.class);
        copy.type = "req";
        Logic.Loc loc = Logic.locate(state, cur.id);
        if (loc != null) loc.arr().set(loc.i(), copy);
        else {
            Logic.Loc folder = selFolder == null ? null : Logic.locate(state, selFolder);
            if (folder != null && folder.node().folder()) { folder.node().children.add(0, copy); folder.node().open = true; }
            else state.tree.add(0, copy);
        }
        persist();
        side = "coll";
        renderSide();
        updateDirty();
        toast("Сохранено");
    }

    /** Копия текущего запроса (с несохранёнными правками): ставится после оригинала и открывается. */
    private void cloneCurrent() {
        Item copy = Json.copy(cur, Item.class);
        copy.type = "req";
        copy.id = Model.uid();
        String base = nz(cur.name).trim();
        if (base.isEmpty()) {
            String u = cur.url.replaceFirst("^https?://", "");
            base = u.isEmpty() ? "Запрос" : u.substring(0, Math.min(60, u.length()));
        }
        copy.name = base + " (копия)";
        Logic.Loc loc = Logic.locate(state, cur.id);
        if (loc != null) loc.arr().add(loc.i() + 1, copy); else state.tree.add(0, copy);
        // правки уходят в копию; оригинал остаётся таким, каким был сохранён
        cur = Json.copy(copy, Item.class);
        cur.type = null;
        persist();
        side = "coll";
        loadCur();
        toast("Копия создана и сохранена");
    }

    private void revert() {
        Logic.Loc loc = Logic.locate(state, cur.id);
        if (loc == null || loc.node().folder()) return;
        String nm = !nz(cur.name).isEmpty() ? cur.name : !nz(cur.url).isEmpty() ? cur.url : "без названия";
        if (!Ui.confirm(stage, "Отменить несохранённые изменения запроса «" + nm + "»?", "Откатить", "Отмена")) return;
        Item next = Json.copy(loc.node(), Item.class).fix();
        next.type = null;
        cur = next;
        loadCur();
    }

    // ---------- отправка ----------
    private Http.Spec buildSpec() {
        return Logic.spec(state, cur, activeEnv(), true);
    }

    private void send() {
        if (sending) return;
        if (cur.url.trim().isEmpty()) { toast("Введите URL"); return; }
        if ("env".equals(cur.auth.type)) {
            Env env = activeEnv();
            if (env == null || !Boolean.TRUE.equals(env.auth().enabled) || Logic.tokenCfg(state, env) == null) {
                toast("В окружении не настроена автоавторизация: выберите запрос входа");
                return;
            }
        }
        if (Boolean.TRUE.equals(cur.sign) && Logic.isXml(cur) && Logic.signCfg(activeEnv()) == null) {
            toast("В окружении не указан файл ключа для транспортной подписи ШЭП");
            return;
        }
        kz.gonets.Gen gen = new kz.gonets.Gen();
        Env env = activeEnv();
        Http.Spec spec = Logic.spec(state, cur, env, true, gen);
        List<String> secrets = Logic.secretValues(env, gen); // значения с замком именно этой отправки
        Item snapshot = Json.copy(cur, Item.class);
        sending = true;
        sendBtn.setDisable(true);
        sendBtn.setText("Отправка…");
        background(() -> Tokens.send(spec), (res, err) -> {
            sending = false;
            sendBtn.setDisable(false);
            sendBtn.setText("Отправить");
            Http.Result r = res != null ? res : Http.Result.error(err == null ? "Неизвестная ошибка" : String.valueOf(err.getMessage()));
            r.secrets = new ArrayList<>(secrets);
            r.secrets.addAll(res == null ? List.of() : res.secrets); // токены автоавторизации
            r.requestName = snapshot.name;
            r.requestUrl = snapshot.url;
            setResult(r);
            state.history.removeIf(h -> nz(h.method).equals(snapshot.method) && nz(h.url).equals(snapshot.url));
            snapshot.id = Model.uid();
            snapshot.name = snapshot.url;
            snapshot.type = null;
            state.history.add(0, snapshot);
            while (state.history.size() > Math.max(0, ui.historyLimit)) state.history.remove(state.history.size() - 1);
            persist();
            if (side.equals("hist")) renderSide();
        });
    }

    /** Показывает, что реально уйдёт на сервер: тело после подписи. Подпись каждый раз новая (случайный Id тела). */
    private void showSigned() {
        kz.gonets.Gen gen = new kz.gonets.Gen();
        Env senv = activeEnv();
        Http.Spec spec = Logic.spec(state, cur, senv, true, gen);
        List<String> secrets = Logic.secretValues(senv, gen);
        if (spec.sign == null) {
            toast(Boolean.TRUE.equals(cur.sign) ? "В окружении не указан файл ключа для транспортной подписи ШЭП" : "Включите «Транспортная подпись ШЭП»");
            return;
        }
        // как при отправке: сначала подставляются токены окружения, потом тело подписывается; секретные значения скрываются галочкой
        record Signed(String xml, List<String> secrets, String tokenError) {}
        background(() -> {
            List<String> all = new ArrayList<>(secrets);
            String body = spec.body, tokenError = null;
            try { body = Tokens.fillBody(spec, all); } catch (Exception e) { tokenError = e.getMessage(); }
            return new Signed(Signer.sign(spec.sign, body), all, tokenError);
        }, (r, err) -> {
            if (err != null) { toast("Подпись: " + err.getMessage()); return; }
            String info = "Показано то, что уйдёт на сервер: переменные окружения и токены подставлены, тело подписано. Подпись каждый раз новая (случайный Id тела)."
                    + (r.tokenError() == null ? "" : "\nТокен окружения получить не удалось (" + r.tokenError() + "): {{access_token}} остался как есть.");
            textDialog("Подписанный запрос", info, r.xml(), r.secrets());
        });
    }

    // ---------- ответ ----------
    private boolean resEditorNoLines;

    private void setResult(Http.Result r) {
        lastRes = r;
        renderRes();
        Platform.runLater(this::updateMinSize);
    }

    private void renderRes() {
        activate(resTabBtns, resTab);
        resMeta.getChildren().clear();
        resEditor.area().getStyleClass().remove("err-text");
        if (lastRes == null) {
            cResHeaders.setText("");
            resContent.getChildren().setAll(Ui.cls(new Label(Ui.k("Нажмите «Отправить» (Ctrl+Enter)")), "empty"));
            find.refresh();
            return;
        }
        if (lastRes.error != null) {
            resMeta.getChildren().add(Ui.cls(new Label("Ошибка"), "status", "serr"));
            cResHeaders.setText("");
            showRes(lastRes.error, Highlight.Mode.PLAIN, false);
            resEditor.area().getStyleClass().add("err-text");
            return;
        }
        Label st = Ui.cls(new Label(lastRes.status + " " + lastRes.statusText), "status", "s" + String.valueOf(lastRes.status).charAt(0));
        resMeta.getChildren().addAll(meta("Статус", st), meta("Время", bold(lastRes.timeMs + " мс")), meta("Размер", bold(Pretty.size(lastRes.size))));
        if (lastRes.authAction != null) {
            HBox t = meta("Токен", bold(EnvDialog.AUTH_ACTION.getOrDefault(lastRes.authAction, lastRes.authAction)));
            Tooltip.install(t, new Tooltip("Токен действует до " + EnvDialog.fmtTime(lastRes.expiresAt)));
            resMeta.getChildren().add(t);
        }
        if (lastRes.signed) resMeta.getChildren().add(meta("Тело", bold("подписано")));
        if (lastRes.authError != null) {
            Label l = Ui.cls(new Label("Токен: ошибка обновления"), "serr");
            l.setTooltip(new Tooltip(lastRes.authError));
            resMeta.getChildren().add(l);
        }
        cResHeaders.setText(String.valueOf(lastRes.headers.size()));
        if (resTab.equals("headers")) {
            StringBuilder b = new StringBuilder();
            for (String[] h : lastRes.headers) { if (b.length() > 0) b.append('\n'); b.append(h[0]).append(": ").append(h[1]); }
            showRes(b.toString(), Highlight.Mode.HEADERS, false);
            return;
        }
        if (lastRes.binary) {
            resContent.getChildren().setAll(Ui.cls(new Label("Бинарный ответ (" + Pretty.size(lastRes.size) + ")"), "empty"));
            find.refresh();
            return;
        }
        String text = lastRes.body == null ? "" : lastRes.body;
        if (text.isEmpty()) {
            resContent.getChildren().setAll(Ui.cls(new Label("Пустое тело ответа"), "empty"));
            find.refresh();
            return;
        }
        if (resTab.equals("raw")) {
            String shown = breakLongLines(text);
            // куски одной длинной строки не нумеруем: иначе вместо «строки 1» видно сотни номеров
            showRes(shown, Highlight.Mode.PLAIN, text.length() > RES_BIG || shown.length() != text.length(), text);
            return;
        }
        Pretty.Body pb = Pretty.body(text);
        Highlight.Mode mode = pb.kind() == Pretty.Kind.JSON ? Highlight.Mode.JSON : pb.kind() == Pretty.Kind.XML ? Highlight.Mode.XML : Highlight.Mode.PLAIN;
        showRes(pb.text(), mode, pb.text().length() > RES_BIG);
    }

    /** Ответ в поле только для чтения; для очень больших текстов номера строк не показываем. */
    private void showRes(String text, Highlight.Mode mode, boolean big) { showRes(text, mode, big, null); }

    /** statSource — текст, по которому считаются строки и символы (если показ отличается от исходного, например для «Сырого»). */
    private void showRes(String text, Highlight.Mode mode, boolean big, String statSource) {
        resEditorNoLines = big;
        resEditor.setLineNumbers(ui.lines && !big);
        resEditor.setMode(mode, mode == Highlight.Mode.JSON);
        resEditor.setText(text);
        resStatSource = statSource == null ? text : statSource;
        updResStatus();
        resContent.getChildren().setAll(resBox);
        find.refresh();
    }

    /** Строка состояния ответа: число строк и символов (по исходному тексту) и размер выделения. */
    private void updResStatus() {
        String t = resStatSource == null ? "" : resStatSource;
        int lines = 1;
        for (int i = 0; i < t.length(); i++) if (t.charAt(i) == '\n') lines++;
        int sel = resEditor.area().getSelection().getLength();
        resStatus.setText("строк: " + lines + " · символов: " + t.length() + (sel > 0 ? " · выделено: " + sel : ""));
    }

    /**
     * Поле с одной огромной строкой (свёрнутый в строку JSON на мегабайты) считает раскладку очень долго и подвешивает окно.
     * Для показа такие строки режутся на куски; «Копировать» берёт исходный текст.
     */
    private static String breakLongLines(String text) {
        final int max = 3000;
        if (text.length() <= max) return text;
        StringBuilder b = new StringBuilder(text.length() + text.length() / max + 16);
        int run = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') run = 0;
            else if (++run > max) { b.append('\n'); run = 1; }
            b.append(c);
        }
        return b.toString();
    }

    private static Label bold(String t) { return Ui.cls(new Label(t), "b"); }

    private static HBox meta(String name, Label value) {
        HBox h = new HBox(4, Ui.cls(new Label(name), "muted"), value);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    /** Что копировать: то, что показано на текущей вкладке ответа (тело — отформатированное). null для бинарного тела. */
    private String resText() {
        if (lastRes == null) return "";
        if (lastRes.error != null) return lastRes.error;
        if (resTab.equals("headers")) {
            List<String> l = new ArrayList<>();
            for (String[] h : lastRes.headers) l.add(h[0] + ": " + h[1]);
            return String.join("\n", l);
        }
        if (lastRes.binary) return null;
        String text = lastRes.body == null ? "" : lastRes.body;
        return resTab.equals("raw") ? text : Pretty.body(text).text();
    }

    // ---------- окна ----------
    private void textDialog(String title, String info, String text) { textDialog(title, info, text, null); }

    /** secrets != null — в окне есть галочка «Скрыть секреты» (значения переменных с замком заменяются на ***). */
    private void textDialog(String title, String info, String text, List<String> secrets) {
        TextArea ta = new TextArea(text);
        ta.setEditable(false);
        ta.setWrapText(true);
        ta.getStyleClass().addAll("mono", "dlg-text");
        ta.setPrefRowCount(20);
        VBox.setVgrow(ta, Priority.ALWAYS);
        VBox content = new VBox(8);
        if (info != null) content.getChildren().add(Ui.hint(info));
        if (secrets != null) {
            CheckBox mask = new CheckBox("Скрыть секреты");
            mask.setTooltip(new Tooltip("Значения переменных окружения с замком заменяются на ***"));
            mask.selectedProperty().addListener((o, a, b) -> ta.setText(b ? Logic.maskValues(text, secrets) : text));
            content.getChildren().add(mask);
        }
        content.getChildren().add(ta);
        Button close = Ui.button("Закрыть");
        Button copy = Ui.button("Копировать", "primary");
        Stage st = Ui.dialog(stage, title, content, Ui.buttons(close, copy), 760, 560);
        close.setOnAction(e -> st.close());
        copy.setOnAction(e -> Ui.toast(st, Ui.copy(ta.getText()) ? "Скопировано" : "Не удалось скопировать"));
        st.show();
    }

    /**
     * «Трафик»: что реально ушло на сервер и что пришло, по снимку последней отправки.
     * Галочки только скрывают части текста — запрос заново не собирается, поэтому UUID, даты и токены не меняются.
     */
    private void showDebug() {
        Http.Result res = lastRes;
        CheckBox mask = new CheckBox("Скрыть секреты (значения переменных с замком)");
        mask.setSelected(true);
        CheckBox hideUrl = new CheckBox("Скрыть адрес запроса (URL и хост заменяются на ***)");
        CheckBox pretty = new CheckBox("Форматировать тело ответа (иначе — как пришло)");
        TextArea ta = new TextArea();
        ta.setEditable(false);
        ta.setWrapText(ui.wrap);
        ta.getStyleClass().addAll("mono", "dlg-text");
        ta.setPrefRowCount(20);
        Runnable fill = () -> ta.setText(Logic.debugReport(res, mask.isSelected(), hideUrl.isSelected(), pretty.isSelected()));
        fill.run();
        for (CheckBox c : List.of(mask, hideUrl, pretty)) c.selectedProperty().addListener((o, a, b) -> fill.run());
        VBox.setVgrow(ta, Priority.ALWAYS);
        Button close = Ui.button("Закрыть");
        Button copy = Ui.button("Копировать", "primary");
        Stage st = Ui.dialog(stage, "Трафик: что ушло и что пришло", new VBox(10, mask, hideUrl, pretty, ta), Ui.buttons(close, copy), 820, 620);
        close.setOnAction(e -> st.close());
        copy.setOnAction(e -> Ui.toast(st, Ui.copy(ta.getText()) ? "Скопировано" : "Выделите текст и скопируйте вручную"));
        st.show();
    }

    /**
     * «Экспорт»: текущий запрос как код (cURL, PowerShell, fetch, Python, Java, HTTP).
     * Значения генераторов ({{$uuid}} и т. п.) вычисляются один раз при открытии окна и не меняются при смене языка.
     */
    private void showCodeExport() {
        Env env = activeEnv();
        ComboBox<CodeExport.Lang> lang = new ComboBox<>();
        lang.getItems().setAll(CodeExport.Lang.values());
        lang.setConverter(new StringConverter<>() {
            public String toString(CodeExport.Lang l) { return l == null ? "" : l.title; }
            public CodeExport.Lang fromString(String s) { return null; }
        });
        CodeExport.Lang saved = CodeExport.Lang.CURL;
        try { if (ui.exportLang != null) saved = CodeExport.Lang.valueOf(ui.exportLang); } catch (IllegalArgumentException ignored) { }
        lang.setValue(saved);
        CheckBox substitute = new CheckBox("Подставить переменные окружения");
        substitute.setSelected(true);
        CheckBox mask = new CheckBox("Скрыть секреты");
        mask.setTooltip(new Tooltip("Значения переменных окружения с замком заменяются на ***"));
        // четыре варианта сборки готовятся по запросу и запоминаются: генераторы не пересчитываются при переключении
        Map<String, CodeExport.Prepared> cache = new HashMap<>();
        TextArea ta = new TextArea();
        ta.setEditable(false);
        ta.setWrapText(false);
        ta.getStyleClass().addAll("mono", "dlg-text");
        Runnable fill = () -> {
            String key = substitute.isSelected() + "/" + mask.isSelected();
            CodeExport.Prepared p = cache.computeIfAbsent(key, k -> CodeExport.prepare(state, cur, env, substitute.isSelected(), mask.isSelected()));
            ta.setText(CodeExport.render(lang.getValue(), p));
        };
        fill.run();
        lang.valueProperty().addListener((o, a, b) -> { if (b != null) { ui.exportLang = b.name(); saveUi(); fill.run(); } });
        substitute.selectedProperty().addListener((o, a, b) -> fill.run());
        mask.selectedProperty().addListener((o, a, b) -> fill.run());
        VBox.setVgrow(ta, Priority.ALWAYS);
        HBox opts = row(muted("Язык"), lang, substitute, mask);
        Button close = Ui.button("Закрыть");
        Button copy = Ui.button("Копировать", "primary");
        Stage st = Ui.dialog(stage, "Экспорт запроса в код", new VBox(10, opts, ta), Ui.buttons(close, copy), 860, 600);
        close.setOnAction(e -> st.close());
        copy.setOnAction(e -> Ui.toast(st, Ui.copy(ta.getText()) ? "Скопировано" : "Выделите текст и скопируйте вручную"));
        st.show();
    }

    private void showCurl() {
        TextArea ta = new TextArea();
        ta.setPromptText("curl -X POST https://… -H 'Content-Type: application/json' -d '{…}'");
        ta.setWrapText(true);
        ta.getStyleClass().addAll("mono", "dlg-text");
        ta.setPrefRowCount(8);
        Button cancel = Ui.button("Отмена");
        Button ok = Ui.button("Импортировать", "primary");
        VBox.setVgrow(ta, Priority.ALWAYS);
        Stage st = Ui.dialog(stage, "Импорт cURL", new VBox(ta), Ui.buttons(cancel, ok), 680, 340);
        cancel.setOnAction(e -> st.close());
        ok.setOnAction(e -> {
            String txt = ta.getText().trim();
            if (!txt.matches("(?s)^curl\\b.*")) { Ui.toast(st, "Ожидается команда curl"); return; }
            st.close();
            openRequest(Logic.parseCurl(txt), "imported");
        });
        st.show();
        Platform.runLater(ta::requestFocus);
    }
}
