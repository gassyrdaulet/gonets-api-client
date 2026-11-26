package kz.gonets.ui;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;
import kz.gonets.Highlight;
import kz.gonets.Highlight.Range;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Поле с подсветкой синтаксиса, номерами строк и отметками поиска (CodeArea из RichTextFX).
 * Короткий текст красится сразу, средний с задержкой, очень большой не красится (как в прежней странице).
 */
public class CodeEditor {
    private final CodeArea area = new CodeArea();
    private final StackPane root;
    private final Label note = new Label();
    private final PauseTransition delay = new PauseTransition(Duration.millis(200));
    private final int liveLimit, bigLimit;
    private Highlight.Mode mode = Highlight.Mode.PLAIN;
    private boolean withVars;
    private List<Range> syntax = List.of();
    private List<Range> find = List.of();
    private boolean lineNumbers = true;
    private boolean silent;
    private Consumer<String> onChange;

    /**
     * @param editable  можно ли редактировать
     * @param liveLimit до какой длины подсветка обновляется сразу
     * @param bigLimit  с какой длины подсветка выключается
     */
    public CodeEditor(boolean editable, int liveLimit, int bigLimit) {
        this.liveLimit = liveLimit;
        this.bigLimit = bigLimit;
        area.setEditable(editable);
        area.getStyleClass().add("code");
        area.setWrapText(true);
        area.setParagraphGraphicFactory(LineNumberFactory.get(area));
        VirtualizedScrollPane<CodeArea> scroll = new VirtualizedScrollPane<>(area);
        scroll.getStyleClass().add("code-scroll");
        ScrollThumb.install(scroll, 44);
        ScrollThumb.gap(scroll);
        note.getStyleClass().add("hl-note");
        note.setVisible(false);
        note.setMouseTransparent(true);
        root = new StackPane(scroll, note);
        StackPane.setAlignment(note, Pos.BOTTOM_RIGHT);
        root.getStyleClass().add("code-box");
        delay.setOnFinished(e -> applySyntax());
        // Подсветку пересчитываем по событию об уже применённой правке. В слушателе textProperty правка ещё не завершена,
        // и при замене всего текста (кнопка «Форматировать») новые стили затирались пустыми.
        area.plainTextChanges().subscribe(ch -> {
            if (silent) return;
            find = List.of();
            schedule();
            // замена целого куска (форматирование, вставка): повторяем окраску после того, как правка полностью применена
            if (ch.getInserted().length() > 1) Platform.runLater(this::applySyntax);
        });
        area.textProperty().addListener((o, a, b) -> {
            if (!silent && onChange != null) onChange.accept(b);
        });
        // сочетания клавиш как в VS Code (Ctrl+X строки, Alt+↑↓, Ctrl+D, Tab для блока и т. д.); Tab не уводит фокус
        EditorKeys.install(area, editable, () -> mode == Highlight.Mode.XML, () -> { if (onFormat != null) onFormat.run(); });
    }

    private Runnable onFormat;

    /** Что делать по Shift+Alt+F (форматировать). */
    public void setOnFormat(Runnable r) { onFormat = r; }

    public Node node() { return root; }
    public CodeArea area() { return area; }

    public void setOnChange(Consumer<String> c) { onChange = c; }

    public void setPlaceholder(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("placeholder");
        area.setPlaceholder(l);
    }

    /** Режим подсветки; vars — подсвечивать {{переменные}} поверх. */
    public void setMode(Highlight.Mode mode, boolean vars) {
        this.mode = mode;
        this.withVars = vars;
        applySyntax();
    }

    public String getText() { return area.getText(); }

    /** Новый текст без вызова onChange; история отмены сбрасывается. */
    public void setText(String text) {
        silent = true;
        try {
            area.replaceText(text == null ? "" : text);
            area.getUndoManager().forgetHistory();
            area.moveTo(0);
            area.requestFollowCaret();
        } finally {
            silent = false;
        }
        find = List.of();
        applySyntax();
        if (area.getLength() > 0) area.showParagraphAtTop(0);
    }

    public void setWrap(boolean wrap) { area.setWrapText(wrap); }

    public void setLineNumbers(boolean on) {
        if (on == lineNumbers) return;
        lineNumbers = on;
        area.setParagraphGraphicFactory(on ? LineNumberFactory.get(area) : null);
    }

    public boolean isBig() { return area.getLength() > bigLimit; }

    private void schedule() {
        delay.stop();
        int len = area.getLength();
        if (len > bigLimit || len <= liveLimit) { applySyntax(); return; }
        // средний текст: пока ждём, подсветка устаревшая, но поле не тормозит на каждое нажатие
        delay.playFromStart();
    }

    /** Короткий текст красится сразу; длинный — в фоновом потоке и кусками, чтобы окно не замирало. */
    private static final int SYNC_LIMIT = 30000, CHUNK = 25000;
    private static final java.util.concurrent.ExecutorService BG = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "highlight");
        t.setDaemon(true);
        return t;
    });
    private int gen; // номер последней задачи: устаревшие результаты отбрасываются

    private void applySyntax() {
        String t = area.getText();
        boolean big = t.length() > bigLimit;
        note.setVisible(big);
        if (big) note.setText("Подсветка выключена: текст больше " + (bigLimit / 1000) + " тыс. символов");
        int g = ++gen;
        if (big) {
            syntax = List.of();
            varsLayer = List.of();
            paint();
            return;
        }
        if (t.length() <= SYNC_LIMIT) {
            syntax = Highlight.ranges(t, mode);
            // переменные — отдельный слой поверх синтаксиса (внутри строк JSON тоже)
            varsLayer = withVars ? Highlight.vars(t) : List.of();
            paint();
            return;
        }
        Highlight.Mode m = mode;
        boolean vars = withVars;
        BG.submit(() -> {
            try {
                List<Range> syn = Highlight.sorted(Highlight.ranges(t, m));
                List<Range> var = vars ? Highlight.sorted(Highlight.vars(t)) : List.of();
                Platform.runLater(() -> {
                    if (g != gen || area.getLength() != t.length()) return;
                    syntax = syn;
                    varsLayer = var;
                    paint();
                });
            } catch (RuntimeException | StackOverflowError ex) {
                // подсветка не обязательна: без неё текст остаётся читаемым
            }
        });
    }

    private List<Range> varsLayer = List.of();

    private void paint() {
        int len = area.getLength();
        if (len <= SYNC_LIMIT) {
            area.setStyleSpans(0, Highlight.spans(len, syntax, varsLayer, find));
            return;
        }
        int g = ++gen;
        List<Range> syn = Highlight.sorted(syntax), var = Highlight.sorted(varsLayer), fnd = Highlight.sorted(find);
        paintChunk(g, 0, len, syn, var, fnd);
    }

    /** Красит по куску за кадр: текст сразу доступен, подсветка дорисовывается сверху вниз. */
    private void paintChunk(int g, int from, int len, List<Range> syn, List<Range> var, List<Range> fnd) {
        if (g != gen || area.getLength() != len || from >= len) return;
        int to = Math.min(len, from + CHUNK);
        area.setStyleSpans(from, Highlight.window(from, to, syn, var, fnd));
        if (to < len) Platform.runLater(() -> paintChunk(g, to, len, syn, var, fnd));
    }

    /** Перекрасить заново (например, после замены всего текста кнопкой «Форматировать»). */
    public void refresh() { applySyntax(); }

    /** Отметки поиска: все совпадения и текущее. */
    public void setFind(List<int[]> matches, int current) {
        List<Range> r = new ArrayList<>(matches.size());
        for (int i = 0; i < matches.size(); i++) {
            int[] m = matches.get(i);
            r.add(new Range(m[0], m[1], i == current ? "find-cur" : "find"));
        }
        find = r;
        paint();
    }

    public void clearFind() {
        if (find.isEmpty()) return;
        find = List.of();
        paint();
    }

    /** Прокручивает к отрезку и ставит туда курсор. */
    public void reveal(int start, int end) {
        area.moveTo(Math.min(end, area.getLength()));
        area.requestFollowCaret();
        int par = area.offsetToPosition(start, org.fxmisc.richtext.model.TwoDimensional.Bias.Forward).getMajor();
        area.showParagraphAtCenter(par);
    }

    public String selectedText() { return area.getSelectedText(); }

    public void requestFocus() { area.requestFocus(); }
}
