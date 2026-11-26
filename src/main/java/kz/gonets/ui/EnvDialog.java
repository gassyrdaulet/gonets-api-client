package kz.gonets.ui;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.StringConverter;
import kz.gonets.Gen;
import kz.gonets.Logic;
import kz.gonets.Model.KV;
import kz.gonets.Model.Env;
import kz.gonets.Model.EnvAuth;
import kz.gonets.Model.EnvSign;
import kz.gonets.Signer;
import kz.gonets.Tokens;

import java.io.File;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Окно «Окружения»: переменные, автоматическая авторизация и транспортная подпись. */
class EnvDialog {
    private final MainWindow main;
    private final Stage stage;
    private String editId;
    private boolean filling;

    private final ComboBox<String> pick = new ComboBox<>();
    private final TextField name = new TextField();
    private final VBox varsBox = new VBox();
    // автоавторизация
    private final CheckBox auEnabled = new CheckBox("Включить для этого окружения");
    private final ComboBox<String> auLogin = new ComboBox<>();
    private final ComboBox<String> auRefresh = new ComboBox<>();
    private final TextField auAcc = new TextField(), auRef = new TextField(), auExp = new TextField();
    private final ComboBox<String> auPlace = MainWindow.choice(MainWindow.ordered(
            "header", "Заголовок", "query", "Параметр URL", "cookie", "Cookie", "placeholder", "Только подстановка {{access_token}}"));
    private final TextField auHeader = new TextField(), auPrefix = new TextField(), auParam = new TextField();
    private final Label auStatus = Ui.hint("");
    private final Label lHeader = new Label("Имя заголовка"), lPrefix = new Label("Префикс значения"), lParam = new Label("Имя параметра или cookie");
    // подпись
    private final TextField sgP12 = new TextField();
    private final PasswordField sgPass = new PasswordField();
    private final Label sgStatus = Ui.hint("");
    private Map<String, String> reqLabels = new LinkedHashMap<>();

    static final Map<String, String> AUTH_ACTION = Map.of(
            "cache", "из кэша", "refresh", "обновлён", "login", "получен входом",
            "refresh+retry", "обновлён после 401", "login+retry", "получен входом после 401");

    static String fmtTime(Long ms) {
        return ms == null ? "срок неизвестен" : DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(ms));
    }

    EnvDialog(MainWindow main) {
        this.main = main;

        pick.setConverter(conv(id -> {
            Env e = env(id);
            return e == null ? "" : e.name;
        }));
        pick.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(pick, Priority.ALWAYS);
        Button add = Ui.button("+ Новое", "small");
        Button del = Ui.button("Удалить", "small");
        HBox top = row(pick, add, del);

        name.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(name, Priority.ALWAYS);
        HBox nameRow = row(muted("Название"), name);

        // ---- генерируемые значения ----
        MenuButton gens = new MenuButton("+ Генератор");
        gens.getStyleClass().add("small");
        gens.setTooltip(new Tooltip("Добавить переменную с генерируемым значением (UUID, дата, время…)"));
        for (Map.Entry<String, String> g : Gen.HELP.entrySet()) {
            MenuItem mi = new MenuItem(g.getKey() + "  —  " + g.getValue());
            mi.setOnAction(e -> addGenerated(g.getKey()));
            gens.getItems().add(mi);
        }
        StringBuilder gh = new StringBuilder("Переменные подставляются в URL, заголовки и тело через {{имя}}. Глаз у переменной — секрет: при экспорте и в «Трафике» её значение заменяется на ***.\n\n"
                + "Генерируемые значения можно писать прямо в запросе или в значении переменной. "
                + "Прямо в запросе каждое вхождение даёт новое значение. Переменная (например, message_id = {{$uuid}}) "
                + "вычисляется один раз на отправку: во всех местах запроса будет одно и то же значение.\n");
        for (Map.Entry<String, String> g : Gen.HELP.entrySet()) gh.append('\n').append(g.getKey()).append(" — ").append(g.getValue());
        Label genHelp = Ui.hint(gh.toString());
        HBox varsHead = section("Переменные", genHelp);
        varsHead.getChildren().add(gens);
        varsHead.setStyle("-fx-padding: 4 0 0 0;");

        // ---- автоавторизация ----
        Label authHelp = Ui.hint("Токен получает обычный запрос из вашей коллекции (вход). Если токен обновляется отдельным запросом, выберите и его: "
                + "в нём доступны {{refresh_token}} и {{access_token}}. Токен хранится только в памяти приложения, обновляется перед истечением "
                + "(срок из exp в JWT или из поля ответа) и после ответа 401. В запросе выберите авторизацию «Токен окружения». Токен передаётся "
                + "одним из четырёх способов: заголовком (по умолчанию Authorization: Bearer …), параметром URL, cookie или подстановкой "
                + "{{access_token}} в любое место запроса (URL, заголовки, тело). Пути к значениям в JSON ответа: token, data.access, "
                + "tokens[0].value, $['a.b'].token.");
        auAcc.setPromptText("accessToken или data.tokens[0].access");
        auRef.setPromptText("refreshToken");
        auExp.setPromptText("пусто: берётся exp из JWT");
        auPrefix.setPromptText("Bearer ");
        auParam.setPromptText("access_token");
        for (TextField f : List.of(auAcc, auRef, auExp, auHeader, auPrefix, auParam)) f.getStyleClass().add("mono");
        auLogin.setMaxWidth(Double.MAX_VALUE);
        auRefresh.setMaxWidth(Double.MAX_VALUE);
        auPlace.setMaxWidth(Double.MAX_VALUE);
        GridPane auGrid = grid();
        addRow(auGrid, 0, new Label("Запрос входа"), auLogin);
        addRow(auGrid, 1, new Label("Запрос обновления"), auRefresh);
        addRow(auGrid, 2, new Label("Путь к access-токену"), auAcc);
        addRow(auGrid, 3, new Label("Путь к refresh-токену"), auRef);
        addRow(auGrid, 4, new Label("Путь к сроку жизни, сек"), auExp);
        addRow(auGrid, 5, new Label("Как передавать токен"), auPlace);
        addRow(auGrid, 6, lHeader, auHeader);
        addRow(auGrid, 7, lPrefix, auPrefix);
        addRow(auGrid, 8, lParam, auParam);
        Button auTest = Ui.button("Проверить вход", "small");
        Button auClear = Ui.button("Сбросить токен", "small");
        HBox auButtons = row(auTest, auClear, auStatus);
        HBox.setHgrow(auStatus, Priority.ALWAYS);

        // ---- подпись ----
        Label signHelp = Ui.hint("Подписывает SOAP Body по WS-Security: ГОСТ 34.10-2015 и 34.11-2015 (512 бит), "
                + "Exclusive C14N, сертификат в KeyInfo. Подпись делается прямо в приложении на библиотеках Калкан. Чтобы подписывать запрос, "
                + "выберите тип тела «XML» или «SOAP» и включите «Транспортная подпись ШЭП». Файл ключа читается с диска и никуда не копируется, "
                + "пароль хранится в data.json, как остальные пароли окружения. В путях и пароле можно использовать переменные {{имя}}.");
        sgP12.setPromptText("C:\\keys\\GOST512_….p12");
        sgP12.getStyleClass().add("mono");
        Button sgPick = Ui.button("Выбрать…");
        sgPick.setMinWidth(Region.USE_PREF_SIZE);
        sgPick.setMaxHeight(Double.MAX_VALUE); // высота как у поля рядом
        sgPick.setTooltip(new Tooltip("Выбрать файл ключа в окне"));
        HBox p12Row = new HBox(6, sgP12, sgPick);
        HBox.setHgrow(sgP12, Priority.ALWAYS);
        GridPane sgGrid = grid();
        addRow(sgGrid, 0, new Label("Файл ключа (.p12)"), p12Row);
        addRow(sgGrid, 1, new Label("Пароль"), sgPass);
        Button sgTest = Ui.button("Проверить ключ", "small");
        HBox sgButtons = row(sgTest, sgStatus);
        HBox.setHgrow(sgStatus, Priority.ALWAYS);

        VBox content = new VBox(10,
                top, nameRow, varsHead, genHelp, varsBox,
                section("Автоматическая авторизация", authHelp), authHelp, auEnabled, auGrid, auButtons,
                section("Транспортная подпись ШЭП (SOAP, ГОСТ)", signHelp), signHelp, sgGrid, sgButtons);
        Button done = Ui.button("Готово");
        done.setDefaultButton(false);
        stage = Ui.dialog(main.stage(), "Окружения", content, Ui.buttons(done), 720, 760);
        done.setOnAction(e -> stage.close());
        stage.setOnHidden(e -> main.renderEnvSel());

        // ---- события ----
        pick.valueProperty().addListener((o, a, b) -> { if (!filling && b != null) { editId = b; fill(); } });
        name.textProperty().addListener((o, a, b) -> {
            if (filling) return;
            Env e = env(editId);
            if (e == null) return;
            e.name = b;
            main.persist();
            filling = true;
            int i = pick.getItems().indexOf(editId);
            if (i >= 0) pick.getItems().set(i, editId); // обновить подпись в списке
            pick.setValue(null);
            pick.setValue(editId);
            filling = false;
        });
        add.setOnAction(e -> {
            Env n = new Env();
            n.id = kz.gonets.Model.uid();
            n.name = "Новое окружение";
            n.auth = new EnvAuth();
            main.state().envs.add(n);
            editId = n.id;
            main.persist();
            fill();
        });
        del.setOnAction(e -> {
            List<Env> envs = main.state().envs;
            if (envs.size() <= 1) { Ui.toast(stage, "Должно остаться хотя бы одно окружение"); return; }
            envs.removeIf(x -> x.id.equals(editId));
            if (envs.stream().noneMatch(x -> x.id.equals(main.state().activeEnv))) main.state().activeEnv = envs.get(0).id;
            editId = envs.get(0).id;
            main.persist();
            fill();
        });

        bindAuth(auEnabled, (a, v) -> a.enabled = v);
        bindCombo(auLogin, (a, v) -> a.loginReqId = v);
        bindCombo(auRefresh, (a, v) -> a.refreshReqId = v);
        bindText(auAcc, (a, v) -> a.accessPath = v);
        bindText(auRef, (a, v) -> a.refreshPath = v);
        bindText(auExp, (a, v) -> a.expiresPath = v);
        bindCombo(auPlace, (a, v) -> a.placement = v);
        bindText(auHeader, (a, v) -> a.header = v);
        bindText(auPrefix, (a, v) -> a.prefix = v);
        bindText(auParam, (a, v) -> a.paramName = v);
        auPlace.valueProperty().addListener((o, a, b) -> syncPlacement());

        bindSign(sgP12, (s, v) -> s.p12 = v);
        bindSign(sgPass, (s, v) -> s.password = v);

        auTest.setOnAction(e -> testLogin());
        auClear.setOnAction(e -> {
            Tokens.clear(editId);
            status(auStatus, "Токен сброшен, при следующем запросе будет получен заново", null);
        });
        sgPick.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Выберите файл ключа");
            fc.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("Ключи (.p12, .pfx)", "*.p12", "*.pfx"),
                    new FileChooser.ExtensionFilter("Все файлы", "*.*"));
            File cur = new File(sgP12.getText().trim());
            if (cur.getParentFile() != null && cur.getParentFile().isDirectory()) fc.setInitialDirectory(cur.getParentFile());
            File f = fc.showOpenDialog(stage);
            if (f != null) sgP12.setText(f.getAbsolutePath());
        });
        sgTest.setOnAction(e -> testKey());
    }

    void show() {
        editId = main.state().activeEnv;
        fill();
        stage.show();
        stage.toFront();
    }

    /** Новая переменная с генератором: ставится перед пустой строкой, фокус — в поле имени. */
    private void addGenerated(String generator) {
        Env env = env(editId);
        if (env == null) return;
        // имя по генератору: {{$uuid}} → uuid, а если занято — uuid_2, uuid_3…
        String base = generator.replaceAll("[{}$]", "").replaceAll(":.*", "").toLowerCase(java.util.Locale.ROOT);
        String key = base;
        for (int n = 2; ; n++) {
            String k = key;
            if (env.vars.stream().noneMatch(v -> v.key().equals(k))) break;
            key = base + "_" + n;
        }
        KV row = new KV(key, generator);
        int at = env.vars.size();
        if (at > 0 && env.vars.get(at - 1).key().isEmpty() && env.vars.get(at - 1).val().isEmpty()) at--;
        env.vars.add(at, row);
        main.persist();
        varsBox.getChildren().setAll(new KvTable(env.vars, main::persist, true, "переменная", "значение", true));
        Ui.toast(stage, "Добавлена переменная {{" + key + "}}");
    }

    private Env env(String id) {
        return main.state().envs.stream().filter(x -> x.id.equals(id)).findFirst().orElse(null);
    }

    private void fill() {
        filling = true;
        try {
            List<Env> envs = main.state().envs;
            if (env(editId) == null) editId = envs.get(0).id;
            pick.getItems().setAll(envs.stream().map(x -> x.id).toList());
            pick.setValue(editId);
            Env env = env(editId);
            name.setText(env.name);
            varsBox.getChildren().setAll(new KvTable(env.vars, main::persist, true, "переменная", "значение", true));

            EnvAuth a = env.auth();
            reqLabels = new LinkedHashMap<>();
            reqLabels.put("", "");
            for (Logic.Flat f : Logic.flatRequests(main.state())) reqLabels.put(f.id(), f.label());
            setOptions(auLogin, "— не выбран —", a.loginReqId);
            setOptions(auRefresh, "— нет (каждый раз входить заново) —", a.refreshReqId);
            auEnabled.setSelected(Boolean.TRUE.equals(a.enabled));
            auAcc.setText(a.accessPath);
            auRef.setText(a.refreshPath);
            auExp.setText(a.expiresPath);
            auPlace.setValue(a.placement);
            auHeader.setText(a.header);
            auPrefix.setText(a.prefix == null ? "" : a.prefix);
            auParam.setText(a.paramName);
            syncPlacement();
            status(auStatus, "", null);

            EnvSign s = env.sign();
            sgP12.setText(s.p12);
            sgPass.setText(s.password);
            status(sgStatus, "", null);
        } finally {
            filling = false;
        }
    }

    private void setOptions(ComboBox<String> box, String emptyLabel, String value) {
        Map<String, String> labels = new LinkedHashMap<>(reqLabels);
        labels.put("", emptyLabel);
        box.setConverter(conv(id -> labels.getOrDefault(id, "")));
        box.getItems().setAll(labels.keySet());
        box.setValue(labels.containsKey(value) ? value : "");
    }

    /** Показываем только те поля, которые нужны выбранному способу передачи токена. */
    private void syncPlacement() {
        String m = auPlace.getValue() == null ? "header" : auPlace.getValue();
        boolean header = m.equals("header"), param = m.equals("query") || m.equals("cookie");
        for (Node n : List.of(lHeader, auHeader, lPrefix, auPrefix)) { n.setVisible(header); n.setManaged(header); }
        for (Node n : List.of(lParam, auParam)) { n.setVisible(param); n.setManaged(param); }
    }

    private void testLogin() {
        Env env = env(editId);
        Tokens.Cfg cfg = Logic.tokenCfg(main.state(), env);
        if (cfg == null) { status(auStatus, "Выберите запрос входа", "serr"); return; }
        status(auStatus, "Запрос…", null);
        main.background(() -> Tokens.tokenFor(cfg, true), (r, err) -> {
            if (err != null) status(auStatus, err.getMessage(), "serr");
            else status(auStatus, "Токен получен (" + AUTH_ACTION.getOrDefault(r.action(), r.action()) + "), действует до " + fmtTime(r.expiresAt())
                    + (r.hasRefresh() ? ", refresh-токен есть" : ", refresh-токена нет"), "s2");
        });
    }

    private static final Pattern CN = Pattern.compile("CN=([^,]+)");

    private void testKey() {
        Signer.Cfg cfg = Logic.signCfg(env(editId));
        if (cfg == null) { status(sgStatus, "Укажите файл ключа", "serr"); return; }
        status(sgStatus, "Проверка…", null);
        main.background(() -> Signer.info(cfg), (info, err) -> {
            if (err != null) { status(sgStatus, err.getMessage(), "serr"); return; }
            Matcher m = CN.matcher(info.get("subject"));
            String cn = m.find() ? m.group(1) : info.get("subject");
            Instant until = Instant.parse(info.get("notAfter"));
            boolean expired = until.isBefore(Instant.now());
            String date = DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(ZoneId.systemDefault()).format(until);
            status(sgStatus, "Ключ открывается: " + cn + ", сертификат " + (expired ? "ИСТЁК " : "действует до ") + date, expired ? "serr" : "s2");
        });
    }

    private void status(Label l, String text, String cls) {
        Platform.runLater(() -> {
            l.setText(text);
            l.getStyleClass().removeAll("serr", "s2");
            if (cls != null) l.getStyleClass().add(cls);
        });
    }

    // ---- привязка полей к окружению ----
    private void bindAuth(CheckBox cb, BiConsumer<EnvAuth, Boolean> set) {
        cb.selectedProperty().addListener((o, a, b) -> {
            if (filling) return;
            Env e = env(editId);
            if (e == null) return;
            set.accept(e.auth(), b);
            main.persist();
        });
    }

    private void bindText(TextField f, BiConsumer<EnvAuth, String> set) {
        f.textProperty().addListener((o, a, b) -> {
            if (filling) return;
            Env e = env(editId);
            if (e == null) return;
            set.accept(e.auth(), b);
            main.persist();
        });
    }

    private void bindCombo(ComboBox<String> c, BiConsumer<EnvAuth, String> set) {
        c.valueProperty().addListener((o, a, b) -> {
            if (filling || b == null) return;
            Env e = env(editId);
            if (e == null) return;
            set.accept(e.auth(), b);
            main.persist();
        });
    }

    private void bindSign(TextField f, BiConsumer<EnvSign, String> set) {
        f.textProperty().addListener((o, a, b) -> {
            if (filling) return;
            Env e = env(editId);
            if (e == null) return;
            set.accept(e.sign(), b);
            main.persist();
        });
    }

    // ---- разметка ----
    private static StringConverter<String> conv(Function<String, String> f) {
        return new StringConverter<>() {
            public String toString(String id) { return id == null ? "" : f.apply(id); }
            public String fromString(String s) { return s; }
        };
    }

    private static HBox row(Node... nodes) {
        HBox h = new HBox(8, nodes);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    private static Label muted(String t) {
        Label l = new Label(t);
        l.getStyleClass().add("muted");
        return l;
    }

    private static GridPane grid() {
        GridPane g = new GridPane();
        g.setHgap(12);
        g.setVgap(8);
        ColumnConstraints c1 = new ColumnConstraints();
        ColumnConstraints c2 = new ColumnConstraints();
        c2.setHgrow(Priority.ALWAYS);
        c2.setFillWidth(true);
        g.getColumnConstraints().addAll(c1, c2);
        return g;
    }

    private static void addRow(GridPane g, int r, Label l, Node n) {
        l.getStyleClass().add("muted");
        if (n instanceof javafx.scene.control.Control c) c.setMaxWidth(Double.MAX_VALUE);
        g.add(l, 0, r);
        g.add(n, 1, r);
    }

    /** Заголовок раздела с кнопкой «?», которая показывает и скрывает подсказку. */
    private static HBox section(String title, Label help) {
        Label h = new Label(title);
        h.getStyleClass().add("sect-title");
        Button q = Ui.button("?", "help-btn");
        q.setTooltip(new Tooltip("Как это работает"));
        help.getStyleClass().add("help-body");
        help.setVisible(false);
        help.setManaged(false);
        q.setOnAction(e -> {
            boolean show = !help.isVisible();
            help.setVisible(show);
            help.setManaged(show);
            q.getStyleClass().remove("on");
            if (show) q.getStyleClass().add("on");
        });
        HBox box = row(h, q);
        box.getStyleClass().add("sect-head");
        return box;
    }
}
