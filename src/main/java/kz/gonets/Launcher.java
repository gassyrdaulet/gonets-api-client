package kz.gonets;

import javafx.application.Application;

/**
 * Точка входа. Отдельный класс, а не App: так JavaFX запускается и с обычного classpath (java -jar gonets.jar),
 * без модульного режима.
 */
public final class Launcher {
    private Launcher() {}

    public static void main(String[] args) {
        // Заголовок Host можно задать в запросе, как и раньше
        System.setProperty("jdk.httpclient.allowRestrictedHeaders", "host");
        if (!App.acquireSingleInstance()) return;
        Application.launch(App.class, args);
    }
}
