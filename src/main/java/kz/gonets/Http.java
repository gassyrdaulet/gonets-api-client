package kz.gonets;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import java.net.ConnectException;
import java.net.Socket;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/** Отправка HTTP-запросов. Запросы уходят из самого приложения, поэтому CORS не мешает. */
public final class Http {
    private Http() {}

    /** Что отправить. Поля открытые: спецификация входа и обновления входит в ключ кэша токенов (через JSON). */
    public static class Spec {
        public String method = "GET";
        public String url = "";
        public LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        public String body;
        public long timeoutMs = 60000;
        public boolean insecure;
        public Tokens.Cfg tokenAuth;
        public Signer.Cfg sign;

        public Spec copy() {
            Spec s = new Spec();
            s.method = method; s.url = url; s.headers = new LinkedHashMap<>(headers); s.body = body;
            s.timeoutMs = timeoutMs; s.insecure = insecure; s.tokenAuth = tokenAuth; s.sign = sign;
            return s;
        }
    }

    /** Ответ (или ошибка отправки). */
    public static class Result {
        public String error;
        public int status;
        public String statusText = "";
        public List<String[]> headers = new ArrayList<>();
        public String body;
        public boolean binary;
        public long size;
        public long timeMs;
        // автоматическая авторизация и подпись
        public String authAction;
        public Long expiresAt;
        public String authError;
        public boolean signed;
        // для окна «Отладка»: снимок обмена, который потом не пересобирается
        /** Что реально ушло: адрес (как закодирован при отправке), заголовки и тело после переменных, токена и подписи. */
        public Spec sent;
        /** Когда ушёл запрос (мс с 1970 года); 0 — не отправлялся. */
        public long sentAt;
        /** Значения переменных с замком, подставленные в этот запрос: их можно скрыть в «Отладке». */
        public List<String> secrets = List.of();
        /** Название и адрес запроса, как они были записаны в момент отправки. */
        public String requestName, requestUrl;

        public static Result error(String msg) {
            Result r = new Result();
            r.error = msg;
            return r;
        }
    }

    private static final int MAX_BODY = 50 * 1024 * 1024;
    private static final Set<String> SKIP_HEADERS = Set.of("content-length", "connection", "expect", "upgrade", "keep-alive", "transfer-encoding", "te", "trailer", "http2-settings");
    private static final Pattern TEXTUAL = Pattern.compile("(^text/|json|xml|javascript|html|x-www-form-urlencoded|yaml|csv)", Pattern.CASE_INSENSITIVE);

    private static volatile HttpClient secure, insecure;

    private static HttpClient client(boolean noVerify) {
        if (noVerify) {
            if (insecure == null) synchronized (Http.class) { if (insecure == null) insecure = build(true); }
            return insecure;
        }
        if (secure == null) synchronized (Http.class) { if (secure == null) secure = build(false); }
        return secure;
    }

    private static HttpClient build(boolean noVerify) {
        HttpClient.Builder b = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER);
        if (noVerify) {
            try {
                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(null, new TrustManager[]{new TrustAll()}, null);
                b.sslContext(ctx);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        return b.build();
    }

    /**
     * Доверяет любому сертификату. Это X509ExtendedTrustManager: проверка имени хоста делается внутри менеджера доверия,
     * поэтому с ним отключается и она.
     */
    private static final class TrustAll extends X509ExtendedTrustManager {
        public void checkClientTrusted(X509Certificate[] c, String a) {}
        public void checkServerTrusted(X509Certificate[] c, String a) {}
        public void checkClientTrusted(X509Certificate[] c, String a, Socket s) {}
        public void checkServerTrusted(X509Certificate[] c, String a, Socket s) {}
        public void checkClientTrusted(X509Certificate[] c, String a, SSLEngine e) {}
        public void checkServerTrusted(X509Certificate[] c, String a, SSLEngine e) {}
        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }

    /**
     * Адрес, как его понимает браузер: пробелы, кириллица и прочие недопустимые символы кодируются,
     * уже закодированные последовательности %XX остаются как есть.
     */
    public static URI toUri(String url) {
        StringBuilder b = new StringBuilder(url.length() + 16);
        byte[] bytes = url.trim().getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i++) {
            int c = bytes[i] & 0xff;
            boolean ok = c > 0x20 && c < 0x7f && "\"<>\\^`{|}".indexOf(c) < 0;
            if (c == '%') ok = i + 2 < bytes.length && hex(bytes[i + 1]) && hex(bytes[i + 2]);
            if (c == '#' && b.indexOf("#") >= 0) ok = false; // второй # внутри фрагмента
            if (ok) b.append((char) c);
            else b.append('%').append(String.format("%02X", c));
        }
        return URI.create(b.toString());
    }

    private static boolean hex(byte x) {
        return (x >= '0' && x <= '9') || (x >= 'a' && x <= 'f') || (x >= 'A' && x <= 'F');
    }

    static boolean isTextual(String contentType) {
        if (contentType == null || contentType.isEmpty()) return true; // без Content-Type чаще всего это текст
        return TEXTUAL.matcher(contentType).find();
    }

    /** Выполняет запрос. Ошибки сети превращаются в исключение с понятным сообщением. */
    public static Result forward(Spec spec) throws Exception {
        URI uri;
        try {
            uri = toUri(spec.url);
        } catch (Exception e) {
            throw new IllegalArgumentException("Некорректный URL: " + spec.url);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (uri.getScheme() == null || uri.getHost() == null) {
            if (!scheme.isEmpty() && !scheme.equals("http") && !scheme.equals("https")) throw new IllegalArgumentException("Поддерживаются только http и https");
            throw new IllegalArgumentException("Некорректный URL: " + spec.url);
        }
        if (!scheme.equals("http") && !scheme.equals("https")) throw new IllegalArgumentException("Поддерживаются только http и https");

        long timeout = spec.timeoutMs > 0 ? spec.timeoutMs : 60000;
        HttpRequest.Builder rb = HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(timeout));
        for (Map.Entry<String, String> h : spec.headers.entrySet()) {
            String name = h.getKey().trim();
            if (name.isEmpty() || SKIP_HEADERS.contains(name.toLowerCase(Locale.ROOT))) continue; // длину и соединение ведёт сам клиент
            try {
                rb.header(name, h.getValue() == null ? "" : h.getValue());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Некорректный заголовок «" + name + "»: " + e.getMessage());
            }
        }
        String method = spec.method == null || spec.method.isBlank() ? "GET" : spec.method.trim().toUpperCase(Locale.ROOT);
        HttpRequest.BodyPublisher pub = spec.body != null
                ? HttpRequest.BodyPublishers.ofByteArray(spec.body.getBytes(StandardCharsets.UTF_8))
                : HttpRequest.BodyPublishers.noBody();
        rb.method(method, pub);

        long started = System.nanoTime();
        long sentAt = System.currentTimeMillis();
        CompletableFuture<HttpResponse<byte[]>> fut = client(spec.insecure).sendAsync(rb.build(), HttpResponse.BodyHandlers.ofByteArray());
        HttpResponse<byte[]> resp;
        try {
            resp = fut.get(timeout + 2000, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            fut.cancel(true);
            throw new SendError("Таймаут запроса", sentCopy(spec, uri), sentAt);
        } catch (ExecutionException e) {
            throw new SendError(describe(e.getCause() == null ? e : e.getCause(), uri), sentCopy(spec, uri), sentAt);
        } catch (InterruptedException e) {
            fut.cancel(true);
            throw new SendError("Запрос прерван", sentCopy(spec, uri), sentAt);
        }
        byte[] buf = resp.body() == null ? new byte[0] : resp.body();
        Result r = new Result();
        r.sent = sentCopy(spec, uri);
        r.sentAt = sentAt;
        r.status = resp.statusCode();
        r.statusText = reason(r.status);
        resp.headers().map().forEach((k, vs) -> vs.forEach(v -> r.headers.add(new String[]{k, v})));
        r.size = buf.length;
        r.timeMs = Math.round((System.nanoTime() - started) / 1e6);
        String ct = resp.headers().firstValue("content-type").orElse("");
        if (isTextual(ct) || buf.length == 0) {
            r.body = buf.length > MAX_BODY * 2 ? new String(buf, 0, MAX_BODY * 2, StandardCharsets.UTF_8) : new String(buf, StandardCharsets.UTF_8);
        } else {
            r.binary = true;
        }
        return r;
    }

    /** Ошибка после отправки (таймаут, обрыв): несёт снимок того, что ушло, для окна «Отладка». */
    public static final class SendError extends IllegalStateException {
        public final transient Spec sent;
        public final long sentAt;

        SendError(String msg, Spec sent, long sentAt) {
            super(msg);
            this.sent = sent;
            this.sentAt = sentAt;
        }
    }

    /** Снимок отправленного: адрес в закодированном виде и только те заголовки, что реально ставятся (без служебных). */
    static Spec sentCopy(Spec spec, URI uri) {
        Spec s = new Spec();
        s.method = spec.method == null || spec.method.isBlank() ? "GET" : spec.method.trim().toUpperCase(Locale.ROOT);
        s.url = uri.toString();
        for (Map.Entry<String, String> h : spec.headers.entrySet()) {
            String name = h.getKey().trim();
            if (!name.isEmpty() && !SKIP_HEADERS.contains(name.toLowerCase(Locale.ROOT))) s.headers.put(name, h.getValue() == null ? "" : h.getValue());
        }
        s.body = spec.body;
        s.timeoutMs = spec.timeoutMs;
        s.insecure = spec.insecure;
        return s;
    }

    /** Сообщение об ошибке сети по-человечески. */
    static String describe(Throwable e, URI uri) {
        Throwable c = e;
        while (c.getMessage() == null && c.getCause() != null && c.getCause() != c) c = c.getCause();
        String host = uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof HttpConnectTimeoutException) return "Таймаут подключения к " + host;
            if (t instanceof HttpTimeoutException) return "Таймаут запроса";
            if (t instanceof UnresolvedAddressException || t instanceof UnknownHostException) return "Адрес не найден: " + uri.getHost();
            if (t instanceof ConnectException) return "Не удалось подключиться к " + host + (t.getMessage() != null ? " (" + t.getMessage() + ")" : "");
            if (t instanceof SSLHandshakeException || t instanceof SSLException) {
                return "TLS: " + t.getMessage() + ". Для тестового стенда можно отключить проверку сертификата в опциях запроса.";
            }
        }
        String msg = c.getMessage();
        return msg == null || msg.isBlank() ? c.getClass().getSimpleName() : msg;
    }

    public static String basic(String user, String pass) {
        return Base64.getEncoder().encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
    }

    private static final Map<Integer, String> REASONS = Map.ofEntries(
            Map.entry(100, "Continue"), Map.entry(101, "Switching Protocols"), Map.entry(200, "OK"), Map.entry(201, "Created"),
            Map.entry(202, "Accepted"), Map.entry(203, "Non-Authoritative Information"), Map.entry(204, "No Content"),
            Map.entry(205, "Reset Content"), Map.entry(206, "Partial Content"), Map.entry(207, "Multi-Status"),
            Map.entry(300, "Multiple Choices"), Map.entry(301, "Moved Permanently"), Map.entry(302, "Found"),
            Map.entry(303, "See Other"), Map.entry(304, "Not Modified"), Map.entry(307, "Temporary Redirect"),
            Map.entry(308, "Permanent Redirect"), Map.entry(400, "Bad Request"), Map.entry(401, "Unauthorized"),
            Map.entry(402, "Payment Required"), Map.entry(403, "Forbidden"), Map.entry(404, "Not Found"),
            Map.entry(405, "Method Not Allowed"), Map.entry(406, "Not Acceptable"), Map.entry(407, "Proxy Authentication Required"),
            Map.entry(408, "Request Timeout"), Map.entry(409, "Conflict"), Map.entry(410, "Gone"), Map.entry(411, "Length Required"),
            Map.entry(412, "Precondition Failed"), Map.entry(413, "Payload Too Large"), Map.entry(414, "URI Too Long"),
            Map.entry(415, "Unsupported Media Type"), Map.entry(416, "Range Not Satisfiable"), Map.entry(417, "Expectation Failed"),
            Map.entry(418, "I'm a Teapot"), Map.entry(422, "Unprocessable Entity"), Map.entry(423, "Locked"),
            Map.entry(425, "Too Early"), Map.entry(426, "Upgrade Required"), Map.entry(428, "Precondition Required"),
            Map.entry(429, "Too Many Requests"), Map.entry(431, "Request Header Fields Too Large"),
            Map.entry(451, "Unavailable For Legal Reasons"), Map.entry(500, "Internal Server Error"),
            Map.entry(501, "Not Implemented"), Map.entry(502, "Bad Gateway"), Map.entry(503, "Service Unavailable"),
            Map.entry(504, "Gateway Timeout"), Map.entry(505, "HTTP Version Not Supported"), Map.entry(507, "Insufficient Storage"),
            Map.entry(511, "Network Authentication Required"));

    static String reason(int status) {
        return REASONS.getOrDefault(status, "");
    }
}
