package kz.gonets.ui;

import javafx.animation.PauseTransition;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/** Поиск по телу запроса и показанному ответу (Ctrl+F): все совпадения подсвечиваются, текущее ярче. */
public class FindBar extends HBox {
    private static final int MAX = 5000;

    private record Match(CodeEditor ed, int start, int end) {}

    private final TextField input = new TextField();
    private final Label count = new Label();
    private final ToggleButton caseBtn = new ToggleButton("Aa");
    private final Supplier<List<CodeEditor>> targets;
    private final PauseTransition refreshDelay = new PauseTransition(Duration.millis(120));
    private final List<CodeEditor> painted = new ArrayList<>();
    private List<Match> matches = new ArrayList<>();
    private int idx = -1;
    private boolean open;

    public FindBar(Supplier<List<CodeEditor>> targets) {
        this.targets = targets;
        getStyleClass().add("findbar");
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(4);
        setVisible(false);
        setMaxWidth(USE_PREF_SIZE);
        setMaxHeight(USE_PREF_SIZE);
        input.setPromptText("Найти в запросе и ответе");
        input.setPrefWidth(240);
        count.getStyleClass().add("find-count");
        count.setMinWidth(64);
        count.setAlignment(Pos.CENTER);
        caseBtn.setTooltip(new Tooltip("Учитывать регистр"));
        caseBtn.getStyleClass().add("flat");
        Button prev = Ui.button("↑", "flat");
        prev.setTooltip(new Tooltip(Ui.k("Предыдущее (Shift+Enter)")));
        Button next = Ui.button("↓", "flat");
        next.setTooltip(new Tooltip("Следующее (Enter)"));
        Button close = Ui.button("✕", "flat");
        close.setTooltip(new Tooltip("Закрыть (Esc)"));
        getChildren().addAll(input, count, caseBtn, prev, next, close);

        input.textProperty().addListener((o, a, b) -> run(false));
        input.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER) { step(e.isShiftDown() ? -1 : 1); e.consume(); }
            else if (e.getCode() == KeyCode.ESCAPE) { close(); e.consume(); }
        });
        prev.setOnAction(e -> step(-1));
        next.setOnAction(e -> step(1));
        close.setOnAction(e -> close());
        caseBtn.selectedProperty().addListener((o, a, b) -> run(false));
        refreshDelay.setOnFinished(e -> run(true));
    }

    public boolean isOpen() { return open; }

    /** Открывает поиск; выделенный текст (если короткий и в одну строку) становится запросом. */
    public void open(String selected) {
        open = true;
        setVisible(true);
        if (selected != null) {
            String s = selected.trim();
            if (!s.isEmpty() && s.length() < 100 && !s.contains("\n")) input.setText(s);
        }
        input.requestFocus();
        input.selectAll();
        run(false);
    }

    public void close() {
        open = false;
        setVisible(false);
        matches = new ArrayList<>();
        idx = -1;
        clearPainted();
        updateCount();
    }

    /** Содержимое изменилось: пересчитываем, не сдвигая текущий результат. */
    public void refresh() {
        if (open) refreshDelay.playFromStart();
    }

    public void step(int dir) {
        if (matches.isEmpty()) return;
        idx = (idx + dir + matches.size()) % matches.size();
        paint();
        updateCount();
        reveal();
    }

    private void clearPainted() {
        for (CodeEditor ed : painted) ed.clearFind();
        painted.clear();
    }

    private void run(boolean keep) {
        String q = input.getText();
        List<Match> found = new ArrayList<>();
        if (open && q != null && !q.isEmpty()) {
            boolean cs = caseBtn.isSelected();
            String needle = cs ? q : q.toLowerCase(Locale.ROOT);
            for (CodeEditor ed : targets.get()) {
                String text = ed.getText();
                String hay = text;
                if (!cs) {
                    String lo = text.toLowerCase(Locale.ROOT);
                    if (lo.length() == text.length()) hay = lo; // иначе смещения разъедутся: ищем с учётом регистра
                }
                for (int i = hay.indexOf(needle); i != -1 && found.size() < MAX; i = hay.indexOf(needle, i + needle.length())) {
                    found.add(new Match(ed, i, i + needle.length()));
                }
            }
        }
        matches = found;
        idx = matches.isEmpty() ? -1 : (keep && idx >= 0 && idx < matches.size() ? idx : 0);
        paint();
        updateCount();
        if (idx >= 0 && !keep) reveal();
    }

    private void paint() {
        clearPainted();
        List<CodeEditor> eds = new ArrayList<>();
        for (Match m : matches) if (!eds.contains(m.ed)) eds.add(m.ed);
        for (CodeEditor ed : eds) {
            List<int[]> r = new ArrayList<>();
            int cur = -1;
            for (int i = 0; i < matches.size(); i++) {
                Match m = matches.get(i);
                if (m.ed != ed) continue;
                if (i == idx) cur = r.size();
                r.add(new int[]{m.start, m.end});
            }
            ed.setFind(r, cur);
            painted.add(ed);
        }
    }

    private void updateCount() {
        String q = input.getText();
        count.setText(matches.isEmpty() ? (q == null || q.isEmpty() ? "" : "нет")
                : (idx + 1) + " / " + matches.size() + (matches.size() >= MAX ? "+" : ""));
    }

    private void reveal() {
        if (idx < 0 || idx >= matches.size()) return;
        Match m = matches.get(idx);
        m.ed.reveal(m.start, m.end);
        input.requestFocus();
    }
}
