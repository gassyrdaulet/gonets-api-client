package kz.gonets;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import kz.gonets.ui.MainWindow;
import kz.gonets.ui.Ui;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** GoNets: приложение на JavaFX. Одна копия: повторный запуск возвращает фокус открытому окну. */
public class App extends Application {
    private static FileLock lock;
    private static ServerSocket signal;
    private Stage stage;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            e.printStackTrace();
            if (Platform.isFxApplicationThread() && stage.isShowing()) Ui.toast(stage, "Ошибка: " + e);
        });
        Store store = new Store();
        if (store.needsPassword()) {
            // пока окно пароля закрывается, других окон нет: без этого JavaFX решил бы, что приложение закончено
            Platform.setImplicitExit(false);
            if (!kz.gonets.ui.PasswordDialogs.unlock(store)) {
                Platform.exit();
                System.exit(0);
                return;
            }
            Platform.setImplicitExit(true);
        }
        listenForSecondInstance();
        new MainWindow(stage, store).show();
        System.out.println("GoNets: данные " + store.file());
    }

    @Override
    public void stop() {
        try { if (signal != null) signal.close(); } catch (IOException ignored) { }
        Platform.exit();
        System.exit(0);
    }

    /**
     * Захватывает блокировку в папке данных. Если приложение уже запущено, просит его показать окно и возвращает false.
     */
    static boolean acquireSingleInstance() {
        Path dir = Store.dataDir();
        try {
            Files.createDirectories(dir);
            FileChannel ch = FileChannel.open(dir.resolve("instance.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            lock = ch.tryLock();
            if (lock != null) return true;
        } catch (Exception e) {
            return true; // блокировку не получить (например, папка только для чтения): запускаемся как есть
        }
        try {
            int port = Integer.parseInt(Files.readString(dir.resolve("instance.port")).trim());
            try (Socket s = new Socket(InetAddress.getLoopbackAddress(), port); OutputStream out = s.getOutputStream()) {
                out.write("show\n".getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) { }
        return false;
    }

    private void listenForSecondInstance() {
        if (lock == null) return;
        try {
            signal = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
            Files.writeString(Store.dataDir().resolve("instance.port"), String.valueOf(signal.getLocalPort()));
            Thread t = new Thread(() -> {
                while (!signal.isClosed()) {
                    try (Socket s = signal.accept(); InputStream in = s.getInputStream()) {
                        byte[] buf = in.readNBytes(16);
                        if (new String(buf, StandardCharsets.UTF_8).startsWith("show")) Platform.runLater(this::bringToFront);
                    } catch (IOException ignored) { }
                }
            }, "gonets-instance");
            t.setDaemon(true);
            t.start();
        } catch (IOException ignored) { }
    }

    private void bringToFront() {
        if (stage == null) return;
        if (stage.isIconified()) stage.setIconified(false);
        stage.show();
        stage.toFront();
        stage.requestFocus();
    }
}
