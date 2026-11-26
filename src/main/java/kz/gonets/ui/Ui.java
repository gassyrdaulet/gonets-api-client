package kz.gonets.ui;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Popup;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.util.Duration;


/** Общие мелочи интерфейса: всплывающие сообщения, подтверждения, окна, буфер обмена. */
public final class Ui {
    private Ui() {}

    public static final String CSS = buildCss();

    private static String first(java.util.Set<String> have, String... want) {
        for (String w : want) if (have.contains(w)) return w;
        return null;
    }

    /**
     * Таблица стилей. Шрифты в ней Windows-овские (Segoe UI, Consolas); на macOS и Linux их нет,
     * поэтому подставляем первые найденные в системе, а стили пишем во временный файл.
     */
    private static String buildCss() {
        java.net.URL res = Ui.class.getResource("/kz/gonets/app.css");
        try {
            java.util.Set<String> have = new java.util.HashSet<>(javafx.scene.text.Font.getFamilies());
            String ui = first(have, "Segoe UI", "SF Pro Text", "Helvetica Neue", "Ubuntu", "Cantarell", "Noto Sans", "DejaVu Sans");
            String mono = first(have, "Consolas", "Cascadia Mono", "Menlo", "Monaco", "DejaVu Sans Mono", "Liberation Mono", "Courier New");
            if ("Segoe UI".equals(ui) && "Consolas".equals(mono)) return res.toExternalForm();
            String css = new String(res.openStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (mono != null) css = css.replace("\"Consolas\", \"Cascadia Mono\", \"Courier New\", monospace", "\"" + mono + "\"").replace("\"Consolas\", monospace", "\"" + mono + "\"");
            if (ui != null) css = css.replace("\"Segoe UI\", \"System\"", "\"" + ui + "\"");
            java.nio.file.Path f = java.nio.file.Files.createTempFile("gonets-", ".css");
            f.toFile().deleteOnExit();
            java.nio.file.Files.writeString(f, css);
            return f.toUri().toString();
        } catch (Exception e) {
            return res.toExternalForm();
        }
    }
    private static Image icon;

    public static Image icon() {
        if (icon == null) icon = new Image(Ui.class.getResourceAsStream("/kz/gonets/icon.png"));
        return icon;
    }

    /** macOS: системная рамка окна, ⌘ вместо Ctrl в подсказках. */
    public static final boolean MAC = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac");

    /** Сочетание клавиш для показа: на macOS Ctrl+Shift+K → ⌘⇧K. */
    public static String k(String s) {
        return MAC ? s.replace("Ctrl+", "⌘").replace("Shift+", "⇧").replace("Alt+", "⌥") : s;
    }

    /** Название одной клавиши для плашки в списке сочетаний. */
    public static String keyName(String t) {
        if (!MAC) return t;
        return switch (t) { case "Ctrl" -> "⌘"; case "Alt" -> "⌥"; case "Shift" -> "⇧"; default -> t; };
    }

    private static boolean light;

    /** Светлая или тёмная тема: класс light на корне каждой сцены (переменные цветов — в app.css, .root.light). */
    public static void setTheme(boolean on) {
        light = on;
        for (Window w : new java.util.ArrayList<>(Window.getWindows())) {
            if (w.getScene() == null) continue;
            applyTheme(w.getScene().getRoot());
            // открытые окна (модалки) заново читают таблицу стилей: иначе часть цветов остаётся от прошлой темы
            var sheets = w.getScene().getStylesheets();
            if (sheets.remove(CSS)) sheets.add(CSS);
        }
    }

    static void applyTheme(javafx.scene.Parent root) {
        if (root == null) return;
        root.getStyleClass().remove("light");
        if (light) root.getStyleClass().add("light");
    }

    public static void toast(Window owner, String msg) {
        if (owner == null) return;
        Label l = new Label(msg);
        l.getStyleClass().add("toast");
        l.setWrapText(true);
        // у всплывающего окна своя сцена без класса темы: цвета задаём прямо
        l.setStyle(light ? "-fx-background-color: #2a2a2d; -fx-text-fill: #f2f2f4;" : "-fx-background-color: #e4e4e6; -fx-text-fill: #0e0e0f;");
        l.setMaxWidth(560);
        l.getStylesheets().add(CSS);
        Popup p = new Popup();
        p.getContent().add(l);
        p.setAutoFix(true);
        p.show(owner);
        l.applyCss();
        l.layout();
        double w = l.prefWidth(-1);
        p.setX(owner.getX() + (owner.getWidth() - Math.min(w, 560)) / 2);
        p.setY(owner.getY() + owner.getHeight() - 70);
        FadeTransition fade = new FadeTransition(Duration.millis(250), l);
        fade.setFromValue(1);
        fade.setToValue(0);
        SequentialTransition seq = new SequentialTransition(new PauseTransition(Duration.millis(1800)), fade);
        seq.setOnFinished(e -> p.hide());
        seq.play();
    }

    public static boolean copy(String text) {
        try {
            ClipboardContent c = new ClipboardContent();
            c.putString(text);
            return Clipboard.getSystemClipboard().setContent(c);
        } catch (Exception e) {
            return false;
        }
    }

    /** Вопрос с двумя кнопками; true, если выбрано действие. */
    public static boolean confirm(Window owner, String message, String yes, String no) {
        return ask(owner, message, null, yes, no) == 0;
    }

    /**
     * Вопрос с тремя кнопками (например, «Сохранить», «Не сохранять», «Отмена»).
     * Возвращает номер нажатой кнопки; закрытие окна или Esc — последняя (отмена).
     */
    public static int choose(Window owner, String message, String detail, String first, String second, String cancel) {
        return ask(owner, message, detail, first, second, cancel);
    }

    /**
     * Вопрос в окне с такой же шапкой, как у главного окна. Первая кнопка — основное действие (Enter),
     * последняя — отмена (Esc, крестик). Возвращает номер нажатой кнопки.
     */
    public static int ask(Window owner, String message, String detail, String... buttons) {
        int cancel = buttons.length - 1;
        int[] result = {cancel};
        Stage st = new Stage();
        Label msg = cls(new Label(message), "ask-message");
        msg.setWrapText(true);
        VBox text = new VBox(6, msg);
        if (detail != null && !detail.isBlank()) text.getChildren().add(hint(detail));
        HBox row = new HBox(8);
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        row.getChildren().add(sp);
        for (int i = 0; i < buttons.length; i++) {
            int n = i;
            Button b = button(buttons[i]);
            if (i == 0) { b.getStyleClass().add("primary"); b.setDefaultButton(true); }
            b.setOnAction(e -> { result[0] = n; st.close(); });
            row.getChildren().add(b);
        }
        VBox body = cls(new VBox(18, text, row), "ask-body");
        body.setPadding(new Insets(18, 18, 16, 18));
        body.setPrefWidth(460);
        setupStage(st, owner, "GoNets", body, false, () -> st.close());
        st.sizeToScene();
        st.showAndWait();
        return result[0];
    }

    /** Модальное окно со своей шапкой (иконка, название, крестик); Esc закрывает. Содержимое прокручивается. */
    public static Stage dialog(Window owner, String title, Node content, Node footer, double width, double height) {
        Stage st = new Stage();
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        // окно с одним большим полем (экспорт, подписанный XML): поле растягивается на всю высоту
        if (content instanceof VBox v && v.getChildren().stream().anyMatch(n -> VBox.getVgrow(n) == Priority.ALWAYS)) scroll.setFitToHeight(true);
        scroll.getStyleClass().add("dlg-scroll");
        ScrollThumb.gap(scroll);
        if (content instanceof Region r) r.setPadding(new Insets(14, 16, 14, 16));
        BorderPane root = new BorderPane(scroll);
        if (footer != null) {
            // нижний блок с кнопками отделён линией и своим фоном, чтобы не сливался с содержимым
            footer.getStyleClass().add("dlg-footer");
            root.setBottom(footer);
        }
        root.getStyleClass().add("dialog-root");
        root.setPrefSize(width, height);
        setupStage(st, owner, title, root, true, st::close);
        return st;
    }

    /** Общее для модальных окон: без системной рамки, своя шапка, стили, размер шрифта как в главном окне, Esc закрывает. */
    private static void setupStage(Stage st, Window owner, String title, Region content, boolean resizable, Runnable onClose) {
        st.initOwner(owner);
        st.initModality(Modality.WINDOW_MODAL);
        st.initStyle(MAC ? StageStyle.DECORATED : StageStyle.UNDECORATED);
        st.setTitle(title);
        st.getIcons().add(icon());
        // минимум только у окон с изменяемым размером; у вопросов «Да / Нет» окно ровно по содержимому (иначе внизу пустая зона)
        if (resizable) {
            st.setMinWidth(Math.min(content.getPrefWidth() > 0 ? content.getPrefWidth() : 420, 420));
            st.setMinHeight(Math.min(content.getPrefHeight() > 0 ? content.getPrefHeight() : 240, 280));
        }
        WindowChrome chrome = WindowChrome.dialog(st, content, title, onClose, resizable);
        Scene sc = new Scene(chrome.root());
        // фон сцены по теме: иначе при любом расхождении размеров видна белая зона
        sc.setFill(light ? javafx.scene.paint.Color.web("#e6e7e9") : javafx.scene.paint.Color.web("#0e0e0f"));
        sc.getStylesheets().add(CSS);
        sc.addEventFilter(KeyEvent.KEY_PRESSED, e -> { if (e.getCode() == KeyCode.ESCAPE) { onClose.run(); e.consume(); } });
        st.setScene(sc);
        applyTheme(sc.getRoot());
        // размер шрифта (Ctrl+= / Ctrl+-) берём у главного окна
        if (owner != null && owner.getScene() != null) {
            Node rp = owner.getScene().getRoot().lookup(".root-pane");
            String fs = rp != null ? rp.getStyle() : owner.getScene().getRoot().getStyle();
            if (fs != null && !fs.isEmpty()) chrome.root().setStyle(fs);
        }
    }

    public static HBox buttons(Node... nodes) {
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        HBox b = new HBox(8);
        b.getChildren().add(sp);
        b.getChildren().addAll(nodes);
        b.setAlignment(Pos.CENTER_RIGHT);
        return b;
    }

    public static Button button(String text, String... classes) {
        Button b = new Button(text);
        b.getStyleClass().addAll(classes);
        return b;
    }

    public static VBox vbox(double spacing, Node... nodes) {
        VBox v = new VBox(spacing, nodes);
        return v;
    }

    public static Label hint(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("hint");
        l.setWrapText(true);
        return l;
    }

    public static <T extends Parent> T cls(T node, String... classes) {
        node.getStyleClass().addAll(classes);
        return node;
    }
}
