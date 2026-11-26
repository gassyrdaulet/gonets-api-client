package kz.gonets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Шифрование data.json мастер-паролем: ключ из пароля через PBKDF2-HMAC-SHA256, шифр AES-256-GCM.
 * Работает одинаково на Windows, macOS и Linux, файл можно переносить между компьютерами.
 */
public final class Crypto {
    private Crypto() {}

    public static final String FORMAT = "gonets-enc-1";
    private static final int ITER = 210_000, SALT = 16, IV = 12;
    private static final SecureRandom RND = new SecureRandom();

    /** Ключ, выведенный из пароля, и соль (она хранится в файле рядом с шифртекстом). */
    public record Key(byte[] salt, int iter, SecretKey key) {}

    public static boolean isEncrypted(byte[] file) {
        try {
            JsonNode n = Json.M.readTree(file);
            return n != null && n.isObject() && FORMAT.equals(n.path("format").asText());
        } catch (Exception e) {
            return false;
        }
    }

    public static Key newKey(char[] password) {
        byte[] salt = new byte[SALT];
        RND.nextBytes(salt);
        return derive(password, salt, ITER);
    }

    /** Ключ по паролю и параметрам из заголовка файла. */
    public static Key keyFor(char[] password, byte[] file) {
        try {
            JsonNode n = Json.M.readTree(file);
            return derive(password, Base64.getDecoder().decode(n.path("salt").asText()), n.path("iter").asInt(ITER));
        } catch (Exception e) {
            throw new IllegalStateException("Повреждён заголовок защищённого файла", e);
        }
    }

    private static Key derive(char[] password, byte[] salt, int iter) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iter, 256);
            byte[] raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            spec.clearPassword();
            SecretKey k = new SecretKeySpec(raw, "AES");
            Arrays.fill(raw, (byte) 0);
            return new Key(salt, iter, k);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Ключ из пароля с той же солью и числом итераций, что у k (для проверки пароля). */
    public static Key sameSalt(char[] password, Key k) { return derive(password, k.salt(), k.iter()); }

    public static boolean sameKey(Key a, Key b) {
        return a != null && b != null && Arrays.equals(a.key().getEncoded(), b.key().getEncoded());
    }

    /** Шифрует текст; каждый раз новый IV. Результат — JSON-файл с заголовком. */
    public static byte[] encrypt(Key k, String plain) {
        try {
            byte[] iv = new byte[IV];
            RND.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, k.key(), new GCMParameterSpec(128, iv));
            byte[] data = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            ObjectNode o = Json.M.createObjectNode();
            o.put("format", FORMAT);
            o.put("kdf", "PBKDF2-HMAC-SHA256");
            o.put("iter", k.iter());
            o.put("salt", Base64.getEncoder().encodeToString(k.salt()));
            o.put("iv", Base64.getEncoder().encodeToString(iv));
            o.put("data", Base64.getEncoder().encodeToString(data));
            return Json.M.writerWithDefaultPrettyPrinter().writeValueAsBytes(o);
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось зашифровать: " + e.getMessage(), e);
        }
    }

    /** Расшифровывает файл; null — неверный пароль или файл изменён. */
    public static String decrypt(Key k, byte[] file) {
        try {
            JsonNode n = Json.M.readTree(file);
            byte[] iv = Base64.getDecoder().decode(n.path("iv").asText());
            byte[] data = Base64.getDecoder().decode(n.path("data").asText());
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, k.key(), new GCMParameterSpec(128, iv));
            return new String(c.doFinal(data), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
