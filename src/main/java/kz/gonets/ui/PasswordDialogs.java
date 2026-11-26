package kz.gonets.ui;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import kz.gonets.Store;

/** Окна мастер-пароля: ввод при запуске, включение, смена и снятие защиты. */
public final class PasswordDialogs {
    private PasswordDialogs() {}

    private static final int MIN_LEN = 6;

    private static PasswordField field(String prompt) {
        PasswordField f = new PasswordField();
        f.setPromptText(prompt);
        return f;
    }

    private static Label error() {
        Label l = Ui.cls(new Label(), "serr");
        l.setWrapText(true);
        l.setVisible(false);
        l.setManaged(false);
        return l;
    }

    private static void show(Label err, String msg) {
        err.setText(msg);
        err.setVisible(true);
        err.setManaged(true);
    }

    /** Запрос пароля при запуске. false — пользователь отказался (программу нужно закрыть). */
    public static boolean unlock(Store store) {
        PasswordField pw = field("Мастер-пароль");
        Label err = error();
        boolean[] ok = {false};
        Button exit = Ui.button("Выйти");
        Button enter = Ui.button("Открыть", "primary");
        VBox content = new VBox(10, Ui.hint("Данные защищены мастер-паролем. Введите его, чтобы открыть коллекции и окружения."), pw, err);
        Stage st = Ui.dialog(null, "GoNets", content, Ui.buttons(exit, enter), 440, 230);
        Runnable go = () -> {
            // вывод ключа занимает долю секунды: считаем не в потоке интерфейса
            enter.setDisable(true);
            char[] p = pw.getText().toCharArray();
            Thread t = new Thread(() -> {
                boolean good = store.unlock(p);
                Platform.runLater(() -> {
                    enter.setDisable(false);
                    if (good) { ok[0] = true; st.close(); }
                    else { show(err, "Неверный пароль"); pw.selectAll(); pw.requestFocus(); }
                });
            }, "gonets-unlock");
            t.setDaemon(true);
            t.start();
        };
        enter.setOnAction(e -> go.run());
        pw.setOnAction(e -> go.run());
        exit.setOnAction(e -> st.close());
        st.setOnShown(e -> pw.requestFocus());
        st.showAndWait();
        return ok[0];
    }

    /** Включение защиты (store ещё не защищён) или смена пароля (уже защищён и нужен текущий). */
    public static void setOrChange(Window owner, Store store, Runnable done) {
        boolean change = store.isProtected();
        PasswordField cur = field("Текущий пароль");
        PasswordField a = field("Новый пароль (не короче " + MIN_LEN + " символов)");
        PasswordField b = field("Повторите новый пароль");
        Label err = error();
        VBox content = new VBox(10,
                Ui.hint(change ? "Файл данных будет зашифрован новым паролем."
                        : "Файл данных (запросы, окружения, токены, история) будет храниться зашифрованным. "
                        + "Пароль нужно вводить при каждом запуске. Если вы его забудете, данные восстановить нельзя."));
        if (change) content.getChildren().add(cur);
        content.getChildren().addAll(a, b, err);
        Button cancel = Ui.button("Отмена");
        Button ok = Ui.button(change ? "Сменить пароль" : "Включить защиту", "primary");
        Stage st = Ui.dialog(owner, change ? "Смена пароля" : "Мастер-пароль", content, Ui.buttons(cancel, ok), 480, change ? 360 : 340);
        cancel.setOnAction(e -> st.close());
        ok.setOnAction(e -> {
            if (change && !store.verify(cur.getText().toCharArray())) { show(err, "Текущий пароль неверный"); return; }
            if (a.getText().length() < MIN_LEN) { show(err, "Пароль короче " + MIN_LEN + " символов"); return; }
            if (!a.getText().equals(b.getText())) { show(err, "Пароли не совпадают"); return; }
            char[] p = a.getText().toCharArray();
            ok.setDisable(true);
            Thread t = new Thread(() -> {
                store.setPassword(p);
                Platform.runLater(() -> { st.close(); if (done != null) done.run(); });
            }, "gonets-setpw");
            t.setDaemon(true);
            t.start();
        });
        st.show();
        Platform.runLater((change ? cur : a)::requestFocus);
    }

    /** Снятие защиты: нужен текущий пароль. */
    public static void remove(Window owner, Store store, Runnable done) {
        PasswordField cur = field("Текущий пароль");
        Label err = error();
        VBox content = new VBox(10, Ui.hint("Файл данных снова будет храниться открытым текстом."), cur, err);
        Button cancel = Ui.button("Отмена");
        Button ok = Ui.button("Снять защиту", "primary");
        Stage st = Ui.dialog(owner, "Снять защиту", content, Ui.buttons(cancel, ok), 460, 250);
        cancel.setOnAction(e -> st.close());
        ok.setOnAction(e -> {
            if (!store.verify(cur.getText().toCharArray())) { show(err, "Пароль неверный"); return; }
            store.removePassword();
            st.close();
            if (done != null) done.run();
        });
        st.show();
        Platform.runLater(cur::requestFocus);
    }
}
