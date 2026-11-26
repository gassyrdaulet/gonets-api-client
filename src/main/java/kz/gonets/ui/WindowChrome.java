package kz.gonets.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.SVGPath;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.util.List;

/**
 * Своя шапка главного окна вместо системной: иконка и название, имя текущего запроса по центру,
 * кнопки «свернуть», «развернуть», «закрыть». Окно без системной рамки, поэтому перетаскивание за шапку,
 * разворачивание (кнопкой и двойным щелчком) и изменение размера за края сделаны здесь.
 */
public final class WindowChrome {
    private static final double EDGE = 6;     // ширина краёв, за которые меняется размер
    private static final double CORNER = 12;  // углы чуть больше, чтобы в них было проще попасть

    private static final String ICON_MIN = "M4.5 10h11";
    private static final String ICON_MAX = "M5 5h10v10H5z";
    private static final String ICON_RESTORE = "M5 7.5h7.5V15H5zM7.5 7.5V5H15v7.5h-2.5";
    private static final String ICON_CLOSE = "M5.5 5.5l9 9M14.5 5.5l-9 9";

    private final Stage stage;
    private final StackPane root = new StackPane();
    private final Label subtitle = Ui.cls(new Label(), "win-subtitle");
    private final Label dirtyDot = Ui.cls(new Label("●"), "win-dirty");
    private final SVGPath maxIcon = svg(ICON_MAX);
    private final Button maxBtn;
    private final List<Region> handles;
    private boolean maximized;
    private Rectangle2D normal; // положение и размер до разворачивания
    private double dragDx, dragDy;

    /**
     * @param content    содержимое окна под шапкой
     * @param onClose    что делать по кнопке «закрыть» (проверка несохранённых правок и закрытие)
     * @param leftExtras кнопки слева, после названия (например, «скрыть панель»)
     */
    public WindowChrome(Stage stage, Node content, Runnable onClose, Node... leftExtras) {
        this(stage, content, onClose, "GoNets", false, true, leftExtras);
    }

    /**
     * Шапка модального окна: иконка, название окна и только кнопка «закрыть» (свернуть и развернуть не нужно).
     * resizable — можно ли тянуть за края (для вопросов «Да / Нет» не нужно).
     */
    public static WindowChrome dialog(Stage stage, Node content, String title, Runnable onClose, boolean resizable) {
        return new WindowChrome(stage, content, onClose, title, true, resizable);
    }

    private final boolean dialog;

    private WindowChrome(Stage stage, Node content, Runnable onClose, String title, boolean dialog, boolean resizable, Node... leftExtras) {
        this.stage = stage;
        this.dialog = dialog;

        boolean nat = Ui.MAC; // на macOS рамку, заголовок и кнопки окна рисует система
        ImageView icon = new ImageView(Ui.icon());
        icon.setFitWidth(dialog ? 16 : 18);
        icon.setFitHeight(dialog ? 16 : 18);
        icon.setSmooth(true);
        Label name = Ui.cls(new Label(title), "win-title");
        HBox left = new HBox(10, icon, name);
        if (nat) left.getChildren().clear(); // иконка и название — в системной рамке
        left.getChildren().addAll(leftExtras);
        left.setAlignment(Pos.CENTER_LEFT);
        left.setPadding(new Insets(0, 0, 0, 12));

        HBox center = new HBox(6, subtitle, dirtyDot);
        center.setAlignment(Pos.CENTER);
        center.setMouseTransparent(true);
        dirtyDot.setVisible(false);
        subtitle.setMinWidth(0);

        Button min = winButton(svg(ICON_MIN), "Свернуть", () -> stage.setIconified(true));
        maxBtn = winButton(maxIcon, "Развернуть", () -> setMaximized(!maximized));
        Button close = winButton(svg(ICON_CLOSE), dialog ? "Закрыть (Esc)" : "Закрыть", onClose);
        close.getStyleClass().add("win-close");
        HBox right = dialog ? new HBox(close) : new HBox(min, maxBtn, close);
        right.setAlignment(Pos.CENTER_RIGHT);

        BorderPane bar = new BorderPane(dialog ? null : center);
        bar.setLeft(left);
        bar.setRight(right);
        bar.getStyleClass().add("win-bar");
        if (dialog) bar.getStyleClass().add("win-bar-dialog");
        BorderPane.setAlignment(left, Pos.CENTER_LEFT);
        BorderPane.setAlignment(right, Pos.CENTER_RIGHT);

        // перетаскивание окна за шапку; если окно развёрнуто — сначала восстановить размер под курсором
        bar.setOnMousePressed(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            dragDx = e.getScreenX() - stage.getX();
            dragDy = e.getScreenY() - stage.getY();
        });
        bar.setOnMouseDragged(e -> {
            if (!e.isPrimaryButtonDown()) return;
            if (maximized) {
                double ratio = dragDx / stage.getWidth();
                setMaximized(false);
                dragDx = stage.getWidth() * ratio;
            }
            stage.setX(e.getScreenX() - dragDx);
            stage.setY(Math.max(screenOf().getMinY(), e.getScreenY() - dragDy));
        });
        bar.setOnMouseClicked(e -> {
            if (!dialog && e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) setMaximized(!maximized);
        });

        BorderPane frame = new BorderPane(content);
        if (nat) {
            // свой только ряд с кнопками главного окна; перетаскивание и размер — системные
            bar.setOnMousePressed(null);
            bar.setOnMouseDragged(null);
            bar.setOnMouseClicked(null);
            bar.setRight(null);
            if (!dialog) frame.setTop(bar);
        } else frame.setTop(bar);
        frame.getStyleClass().add("win-frame");

        handles = List.of(
                handle(Pos.TOP_CENTER, Cursor.N_RESIZE, false, true, false, false),
                handle(Pos.BOTTOM_CENTER, Cursor.S_RESIZE, false, false, false, true),
                handle(Pos.CENTER_LEFT, Cursor.W_RESIZE, true, false, false, false),
                handle(Pos.CENTER_RIGHT, Cursor.E_RESIZE, false, false, true, false),
                handle(Pos.TOP_LEFT, Cursor.NW_RESIZE, true, true, false, false),
                handle(Pos.TOP_RIGHT, Cursor.NE_RESIZE, false, true, true, false),
                handle(Pos.BOTTOM_LEFT, Cursor.SW_RESIZE, true, false, false, true),
                handle(Pos.BOTTOM_RIGHT, Cursor.SE_RESIZE, false, false, true, true));
        root.getChildren().add(frame);
        if (resizable && !nat) root.getChildren().addAll(handles);
        root.getStyleClass().add("win-root");
        if (dialog) root.getStyleClass().add("win-dialog");
    }

    public Parent root() { return root; }

    private java.util.function.Consumer<Boolean> onMaximized;

    /** Что делать после разворачивания на весь экран и возврата (например, подогнать ширину панелей). */
    public void setOnMaximized(java.util.function.Consumer<Boolean> c) { onMaximized = c; }

    private void fireMaximized(boolean on) { if (onMaximized != null) onMaximized.accept(on); }

    public boolean isMaximized() { return Ui.MAC ? stage.isMaximized() : maximized; }

    /** Положение и размер обычного (не развёрнутого) окна: их и сохраняем. */
    public Rectangle2D normalBounds() {
        return !Ui.MAC && maximized && normal != null ? normal : new Rectangle2D(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
    }

    /** Имя текущего запроса по центру шапки; dirty — отметка несохранённых правок. */
    public void setSubtitle(String text, boolean dirty) {
        subtitle.setText(text == null ? "" : text);
        dirtyDot.setVisible(dirty);
    }

    /** Разворачивает на рабочую область экрана (без панели задач) или восстанавливает прежний размер. */
    public void setMaximized(boolean on) {
        if (Ui.MAC) { stage.setMaximized(on); fireMaximized(on); return; }
        if (on == maximized) return;
        if (on) {
            normal = new Rectangle2D(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
            Rectangle2D s = screenOf();
            stage.setX(s.getMinX());
            stage.setY(s.getMinY());
            stage.setWidth(s.getWidth());
            stage.setHeight(s.getHeight());
        } else if (normal != null) {
            // не меньше минимального размера окна: иначе после возврата с полного экрана кнопки уезжают за край
            stage.setWidth(Math.max(stage.getMinWidth(), normal.getWidth()));
            stage.setHeight(Math.max(stage.getMinHeight(), normal.getHeight()));
            stage.setX(normal.getMinX());
            stage.setY(normal.getMinY());
        }
        maximized = on;
        maxIcon.setContent(on ? ICON_RESTORE : ICON_MAX);
        maxBtn.setTooltip(new Tooltip(on ? "Восстановить" : "Развернуть"));
        root.getStyleClass().remove("maximized");
        if (on) root.getStyleClass().add("maximized");
        for (Region h : handles) h.setVisible(!on);
        fireMaximized(on);
    }

    private Rectangle2D screenOf() {
        List<Screen> list = Screen.getScreensForRectangle(stage.getX() + stage.getWidth() / 2, stage.getY() + 10, 1, 1);
        return (list.isEmpty() ? Screen.getPrimary() : list.get(0)).getVisualBounds();
    }

    private static SVGPath svg(String path) {
        SVGPath p = new SVGPath();
        p.setContent(path);
        p.getStyleClass().add("win-icon");
        return p;
    }

    private static Button winButton(SVGPath icon, String tip, Runnable action) {
        Button b = new Button(null, icon);
        b.getStyleClass().add("win-btn");
        b.setTooltip(new Tooltip(tip));
        b.setFocusTraversable(false);
        b.setOnAction(e -> action.run());
        b.setOnMousePressed(e -> e.consume()); // не начинать перетаскивание окна с кнопки
        return b;
    }

    /** Невидимая полоса по краю окна: тянешь — меняется размер с этой стороны. */
    private Region handle(Pos pos, Cursor cursor, boolean west, boolean north, boolean east, boolean south) {
        Region r = new Region();
        r.setCursor(cursor);
        boolean corner = (west || east) && (north || south);
        double w = corner ? CORNER : (west || east) ? EDGE : Double.MAX_VALUE;
        double h = corner ? CORNER : (north || south) ? EDGE : Double.MAX_VALUE;
        r.setMaxSize(w, h);
        r.setPrefSize(corner || west || east ? w : 0, corner || north || south ? h : 0);
        StackPane.setAlignment(r, pos);
        double[] start = new double[6];
        r.setOnMousePressed(e -> {
            start[0] = e.getScreenX();
            start[1] = e.getScreenY();
            start[2] = stage.getX();
            start[3] = stage.getY();
            start[4] = stage.getWidth();
            start[5] = stage.getHeight();
            e.consume();
        });
        r.setOnMouseDragged(e -> {
            double dx = e.getScreenX() - start[0], dy = e.getScreenY() - start[1];
            double minW = Math.max(stage.getMinWidth(), 200), minH = Math.max(stage.getMinHeight(), 150);
            if (east) stage.setWidth(Math.max(minW, start[4] + dx));
            if (south) stage.setHeight(Math.max(minH, start[5] + dy));
            if (west) {
                double nw = Math.max(minW, start[4] - dx);
                stage.setX(start[2] + start[4] - nw);
                stage.setWidth(nw);
            }
            if (north) {
                double nh = Math.max(minH, start[5] - dy);
                stage.setY(start[3] + start[5] - nh);
                stage.setHeight(nh);
            }
            e.consume();
        });
        return r;
    }
}
