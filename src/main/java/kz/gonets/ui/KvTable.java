package kz.gonets.ui;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import kz.gonets.Model.KV;

import java.util.List;

/** Таблица «ключ — значение» с галочками; в конце всегда есть пустая строка для новой записи. */
public class KvTable extends VBox {
    private final List<KV> rows;
    private final Runnable onChange;
    private final boolean withCheck, withSecret;
    private final String keyHint, valueHint;

    // глаз: открытый — значение видно при экспорте, зачёркнутый — секрет, скрывается
    private static final String ICON_EYE = "M1.8 10s3.1-5.6 8.2-5.6S18.2 10 18.2 10s-3.1 5.6-8.2 5.6S1.8 10 1.8 10zM10 7.7a2.3 2.3 0 1 0 0 4.6a2.3 2.3 0 1 0 0-4.6z";
    private static final String ICON_EYE_OFF = ICON_EYE + "M3.6 16.4L16.4 3.6";

    public KvTable(List<KV> rows, Runnable onChange, boolean withCheck) {
        this(rows, onChange, withCheck, "ключ", "значение", false);
    }

    /** withSecret — глаз у каждой строки: значение скрывается при экспорте (для переменных окружения). */
    public KvTable(List<KV> rows, Runnable onChange, boolean withCheck, String keyHint, String valueHint, boolean withSecret) {
        this.rows = rows;
        this.onChange = onChange;
        this.withCheck = withCheck;
        this.withSecret = withSecret;
        this.keyHint = keyHint;
        this.valueHint = valueHint;
        getStyleClass().add("kv");
        setSpacing(4);
        draw();
    }

    private boolean lastFilled() {
        if (rows.isEmpty()) return true;
        KV last = rows.get(rows.size() - 1);
        return !last.key().isEmpty() || !last.val().isEmpty();
    }

    private void draw() {
        getChildren().clear();
        if (lastFilled()) rows.add(new KV());
        for (KV r : rows) getChildren().add(row(r));
    }

    private HBox row(KV r) {
        HBox box = new HBox(6);
        box.setAlignment(Pos.CENTER_LEFT);
        box.getStyleClass().add("kv-row");
        if (withCheck) {
            CheckBox cb = new CheckBox();
            cb.setSelected(r.enabled());
            cb.setTooltip(new Tooltip("Включено"));
            cb.selectedProperty().addListener((o, a, b) -> { r.on = b; onChange.run(); });
            box.getChildren().add(cb);
        }
        TextField k = new TextField(r.key());
        k.setPromptText(keyHint);
        k.getStyleClass().add("mono");
        TextField v = new TextField(r.val());
        v.setPromptText(valueHint);
        v.getStyleClass().add("mono");
        HBox.setHgrow(k, Priority.ALWAYS);
        HBox.setHgrow(v, Priority.ALWAYS);
        k.setPrefWidth(200);
        v.setPrefWidth(300);
        k.textProperty().addListener((o, a, b) -> { r.k = b; changed(); });
        v.textProperty().addListener((o, a, b) -> { r.v = b; changed(); });
        Button del = new Button("✕");
        del.getStyleClass().addAll("flat", "del");
        del.setTooltip(new Tooltip("Удалить"));
        del.setFocusTraversable(false);
        del.setMinWidth(USE_PREF_SIZE);
        del.setOnAction(e -> {
            rows.remove(r);
            draw();
            onChange.run();
        });
        box.getChildren().addAll(k, v);
        if (withSecret) {
            SVGPath lock = new SVGPath();
            lock.setContent(r.hidden() ? ICON_EYE_OFF : ICON_EYE);
            lock.getStyleClass().add("icon-line");
            ToggleButton sec = new ToggleButton(null, lock);
            sec.getStyleClass().addAll("flat", "secret-toggle");
            sec.setSelected(r.hidden());
            sec.setFocusTraversable(false);
            sec.setMinWidth(USE_PREF_SIZE);
            Runnable tip = () -> sec.setTooltip(new Tooltip(sec.isSelected()
                    ? "Секрет: значение скрывается при экспорте и в «Трафике» (нажмите, чтобы показывать)"
                    : "Скрывать значение при экспорте и в «Трафике»"));
            tip.run();
            sec.selectedProperty().addListener((o, a, b) -> {
                r.secret = b ? Boolean.TRUE : null;
                lock.setContent(b ? ICON_EYE_OFF : ICON_EYE);
                tip.run();
                onChange.run();
            });
            box.getChildren().add(sec);
        }
        box.getChildren().add(del);
        return box;
    }

    private void changed() {
        // Заполнили последнюю строку: сразу добавляем новую пустую, не перерисовывая таблицу (фокус остаётся на месте)
        if (lastFilled()) {
            KV n = new KV();
            rows.add(n);
            getChildren().add(row(n));
        }
        onChange.run();
    }
}
