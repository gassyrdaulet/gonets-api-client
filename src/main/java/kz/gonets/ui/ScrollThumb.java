package kz.gonets.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.control.ScrollBar;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

import java.util.function.Consumer;

/**
 * Мелочи вертикальных полос прокрутки.
 * <ul>
 *   <li>{@link #install}: минимальная длина ползунка. Когда текста очень много, ползунок JavaFX сжимается до пары пикселей;
 *       поверх него рисуется «таблетка» нужной длины (обычная раскладка не меняется, скругления не искажаются).</li>
 *   <li>{@link #gap}: пока полосы нет, справа остаётся пустое место её ширины, поэтому содержимое не прыгает по ширине,
 *       когда полоса появляется или исчезает.</li>
 * </ul>
 */
public final class ScrollThumb {
    private ScrollThumb() {}

    /** Вызывает action для каждой вертикальной полосы внутри node: полосы создаются скином уже после показа. */
    private static void forEachVbar(Node node, Consumer<ScrollBar> action) {
        Runnable go = () -> {
            for (Node n : node.lookupAll(".scroll-bar")) {
                if (n instanceof ScrollBar sb && sb.getOrientation() == Orientation.VERTICAL && sb.getProperties().putIfAbsent("gn-seen-" + action.hashCode(), true) == null) action.accept(sb);
            }
        };
        node.sceneProperty().addListener((o, a, b) -> { if (b != null) Platform.runLater(go); });
        if (node.getScene() != null) Platform.runLater(go);
        Platform.runLater(() -> Platform.runLater(go));
    }

    /** Ползунок не короче min пикселей. */
    public static void install(Node node, double min) {
        forEachVbar(node, sb -> attach(sb, min));
    }

    /** Добавляет node класс no-vbar, пока вертикальной полосы нет (в CSS на этот класс задан отступ справа). */
    public static void gap(Node node) {
        forEachVbar(node, sb -> {
            Runnable upd = () -> {
                node.getStyleClass().remove("no-vbar");
                if (!sb.isVisible()) node.getStyleClass().add("no-vbar");
            };
            sb.visibleProperty().addListener((o, a, b) -> upd.run());
            upd.run();
        });
    }

    private static void attach(ScrollBar sb, double min) {
        Platform.runLater(() -> {
            Node thumb = sb.lookup(".thumb");
            if (!(thumb instanceof StackPane pane)) return;
            Region pill = new Region();
            pill.getStyleClass().add("thumb-pill");
            pill.setManaged(false);
            pill.setVisible(false);
            pane.getChildren().add(pill);
            Runnable fix = () -> {
                double h = pane.getHeight();
                if (h <= 0 || h >= min) {
                    pill.setVisible(false);
                    pane.getStyleClass().remove("fat");
                    return;
                }
                Insets in = sb.getInsets();
                double top0 = in.getTop(), range = sb.getHeight() - in.getBottom() - top0;
                if (range <= min) { pill.setVisible(false); return; }
                // доля прокрутки берётся из значения полосы: ползунок двигается через translateY, и по нему считать нельзя
                double span = sb.getMax() - sb.getMin();
                double frac = span <= 0 ? 0 : Math.max(0, Math.min(1, (sb.getValue() - sb.getMin()) / span));
                double newTop = top0 + frac * (range - min);
                double cur = pane.getLayoutY() + pane.getTranslateY();
                pill.resizeRelocate(0, newTop - cur, pane.getWidth(), min);
                pill.setVisible(true);
                if (!pane.getStyleClass().contains("fat")) pane.getStyleClass().add("fat");
            };
            pane.layoutYProperty().addListener((o, a, b) -> fix.run());
            pane.translateYProperty().addListener((o, a, b) -> fix.run());
            sb.valueProperty().addListener((o, a, b) -> fix.run());
            sb.maxProperty().addListener((o, a, b) -> fix.run());
            sb.minProperty().addListener((o, a, b) -> fix.run());
            pane.heightProperty().addListener((o, a, b) -> fix.run());
            pane.widthProperty().addListener((o, a, b) -> fix.run());
            sb.heightProperty().addListener((o, a, b) -> fix.run());
            fix.run();
        });
    }
}
