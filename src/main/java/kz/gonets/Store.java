package kz.gonets;

import javafx.animation.PauseTransition;
import javafx.util.Duration;
import kz.gonets.Model.State;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Хранение data.json. Файл тот же, что у прежней версии: %APPDATA%\Gonets\data.json на Windows,
 * ~/Library/Application Support/Gonets на macOS, ~/.config/Gonets на Linux
 * (или папка из GONETS_DATA_DIR / GONETS_USER_DATA).
 * Файл можно защитить мастер-паролем: тогда он хранится зашифрованным (см. Crypto).
 */
public final class Store {
    private final Path dir;
    private final Path file;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "gonets-save");
        t.setDaemon(true);
        return t;
    });
    private final PauseTransition debounce = new PauseTransition(Duration.millis(300));
    private State state;
    private Crypto.Key key; // не null — файл пишется зашифрованным

    public Store() {
        this.dir = dataDir();
        this.file = dir.resolve("data.json");
        debounce.setOnFinished(e -> writeAsync());
    }

    public static Path dataDir() {
        for (String var : new String[]{"GONETS_DATA_DIR", "GONETS_USER_DATA"}) {
            String v = System.getenv(var);
            if (v != null && !v.isBlank()) return Paths.get(v.trim());
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home");
        if (os.contains("mac")) return Paths.get(home, "Library", "Application Support", "Gonets");
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) return Paths.get(appData, "Gonets");
        String xdg = System.getenv("XDG_CONFIG_HOME");
        if (xdg != null && !xdg.isBlank()) return Paths.get(xdg.trim(), "Gonets");
        return Paths.get(home, ".config", "Gonets");
    }

    public Path dir() { return dir; }
    public Path file() { return file; }

    // ---------- защита мастер-паролем ----------

    /** Файл зашифрован, а пароль ещё не вводили. */
    public boolean needsPassword() {
        try {
            return key == null && Files.exists(file) && Crypto.isEncrypted(Files.readAllBytes(file));
        } catch (IOException e) {
            return false;
        }
    }

    /** Проверяет пароль по файлу и запоминает ключ. false — пароль неверный. */
    public boolean unlock(char[] password) {
        try {
            byte[] raw = Files.readAllBytes(file);
            Crypto.Key k = Crypto.keyFor(password, raw);
            if (Crypto.decrypt(k, raw) == null) return false;
            key = k;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isProtected() { return key != null; }

    /** Совпадает ли пароль с текущим (для смены и снятия защиты). */
    public boolean verify(char[] password) {
        return key != null && Crypto.sameKey(key, Crypto.sameSalt(password, key));
    }

    /** Включает защиту или меняет пароль; файл перезаписывается сразу. */
    public void setPassword(char[] password) {
        key = Crypto.newKey(password);
        writeNow();
    }

    /** Снимает защиту: файл снова пишется открытым текстом. */
    public void removePassword() {
        key = null;
        writeNow();
    }

    private void writeNow() {
        debounce.stop();
        if (state == null) return;
        byte[] bytes = encode(Json.write(state));
        writer.submit(() -> write(bytes));
    }

    private byte[] encode(String json) {
        return key == null ? json.getBytes(StandardCharsets.UTF_8) : Crypto.encrypt(key, json);
    }

    // ---------- чтение и запись ----------

    /** Читает data.json; если файла нет или он повреждён, начинает с пустых данных (повреждённый файл сохраняется рядом). */
    public State load() {
        State st = null;
        try {
            Files.createDirectories(dir);
            if (Files.exists(file)) {
                byte[] raw = Files.readAllBytes(file);
                if (Crypto.isEncrypted(raw)) {
                    String plain = key == null ? null : Crypto.decrypt(key, raw);
                    if (plain == null) throw new IllegalStateException("файл защищён паролем");
                    st = Json.M.readValue(plain, State.class);
                } else st = Json.M.readValue(raw, State.class);
            }
        } catch (Exception e) {
            System.err.println("GoNets: не удалось прочитать " + file + ": " + e.getMessage());
            try {
                Files.copy(file, dir.resolve("data.broken-" + System.currentTimeMillis() + ".json"), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) { }
        }
        state = st == null ? new State() : st;
        return state;
    }

    /** Отложенная запись: частые правки сливаются в одну запись через 300 мс. Вызывать из потока интерфейса. */
    public void persist() {
        debounce.playFromStart();
    }

    private void writeAsync() {
        byte[] bytes = encode(Json.write(state));
        writer.submit(() -> write(bytes));
    }

    /** Немедленная запись (при выходе). */
    public void flush() {
        debounce.stop();
        byte[] bytes = encode(Json.write(state));
        writer.submit(() -> write(bytes));
        writer.shutdown();
        try { writer.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
    }

    private void write(byte[] bytes) {
        // пишем во временный файл и переименовываем: сбой посреди записи не испортит data.json
        Path tmp = file.resolveSibling("data.json.tmp");
        try {
            Files.createDirectories(dir);
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            System.err.println("GoNets: не удалось сохранить " + file + ": " + e.getMessage());
        }
    }
}
