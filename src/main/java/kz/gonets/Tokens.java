package kz.gonets;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Автоматическая авторизация: вход, обновление токена и подстановка его в запрос.
 * Токены хранятся только в памяти процесса, по одному набору на окружение.
 */
public final class Tokens {
    private Tokens() {}

    /** Настройки окружения для получения токена (переменные уже подставлены). */
    public static class Cfg {
        public String envId;
        public Http.Spec login;
        public Http.Spec refresh;
        public String accessPath = "accessToken";
        public String refreshPath = "refreshToken";
        public String expiresPath = "";
        public String placement = "header";
        public String header = "Authorization";
        public String prefix = "Bearer ";
        public String paramName = "access_token";
        /** Ключ кэша (без сгенерированных значений); если не задан, считается по самим запросам. */
        public String key;
    }

    /** Результат: токен и откуда он взялся (cache, refresh, login). */
    public record Info(String token, String action, Long expiresAt, boolean hasRefresh) {}

    private static final class St {
        final String key;
        String access, refresh;
        Long accessExp, refreshExp;
        St(String key) { this.key = key; }
    }

    private record Got(String access, String refresh, Long exp) {}

    private static final Object LOCK = new Object();
    private static final Map<String, St> CACHE = new LinkedHashMap<>();
    private static final Map<String, CompletableFuture<Info>> INFLIGHT = new LinkedHashMap<>();

    public static void clear(String envId) {
        synchronized (LOCK) { CACHE.remove(envId); }
    }

    /** Срок действия из поля exp JWT, в миллисекундах; null, если это не JWT или срока нет. */
    static Long jwtExp(String token) {
        if (token == null) return null;
        String[] parts = token.split("\\.");
        if (parts.length < 2) return null;
        try {
            String p = parts[1].replace('-', '+').replace('_', '/').replaceAll("[^A-Za-z0-9+/]", "");
            while (p.length() % 4 != 0) p += "=";
            JsonNode n = Json.M.readTree(new String(Base64.getDecoder().decode(p), StandardCharsets.UTF_8));
            JsonNode exp = n == null ? null : n.get("exp");
            return exp != null && exp.isNumber() ? Math.round(exp.asDouble() * 1000) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static final Pattern PATH_RE = Pattern.compile("\\[(\\d+)\\]|\\[\\s*'((?:[^'\\\\]|\\\\.)*)'\\s*\\]|\\[\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*\\]|\\.?([^.\\[\\]]+)");

    /** Путь к значению в JSON: data.token, tokens[0].access, $.data['a.b'].token (ведущий $ необязателен). */
    static List<Object> parsePath(String p) {
        String s = p.trim().replaceFirst("^\\$\\.?", "");
        List<Object> out = new ArrayList<>();
        Matcher m = PATH_RE.matcher(s);
        while (m.find()) {
            if (m.group(1) != null) out.add(Integer.parseInt(m.group(1)));
            else if (m.group(2) != null) out.add(m.group(2).replaceAll("\\\\(.)", "$1"));
            else if (m.group(3) != null) out.add(m.group(3).replaceAll("\\\\(.)", "$1"));
            else out.add(m.group(4));
        }
        return out;
    }

    static JsonNode pick(JsonNode obj, String path) {
        JsonNode cur = obj;
        for (Object k : parsePath(path)) {
            if (cur == null || cur.isNull() || cur.isMissingNode()) return null;
            if (k instanceof Integer i) cur = cur.isArray() ? cur.get(i) : cur.get(String.valueOf(i));
            else {
                String key = (String) k;
                if (cur.isArray() && key.matches("\\d+")) cur = cur.get(Integer.parseInt(key));
                else cur = cur.isObject() ? cur.get(key) : null;
            }
        }
        return cur;
    }

    /** Токен берём только если по пути лежит непустая строка или число. */
    static String pickToken(JsonNode obj, String path) {
        JsonNode v = obj == null ? null : pick(obj, path);
        if (v == null) return null;
        if (v.isTextual()) return v.textValue().isEmpty() ? null : v.textValue();
        if (v.isNumber()) return v.asText();
        return null;
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*(refresh_token|access_token)\\s*\\}\\}");

    /** Подставляет {{refresh_token}} и {{access_token}}: их знает только приложение. */
    static Http.Spec fill(Http.Spec spec, Map<String, String> vals) {
        Http.Spec out = spec.copy();
        out.url = fillStr(spec.url, vals);
        out.body = fillStr(spec.body, vals);
        LinkedHashMap<String, String> h = new LinkedHashMap<>();
        spec.headers.forEach((k, v) -> h.put(k, fillStr(v, vals)));
        out.headers = h;
        return out;
    }

    private static String fillStr(String s, Map<String, String> vals) {
        if (s == null) return null;
        Matcher m = PLACEHOLDER.matcher(s);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            String v = vals.get(m.group(1));
            m.appendReplacement(b, Matcher.quoteReplacement(v == null ? "" : v));
        }
        m.appendTail(b);
        return b.toString();
    }

    private static String firstTruthy(JsonNode json, String... keys) {
        if (json == null || !json.isObject()) return null;
        for (String k : keys) {
            JsonNode v = json.get(k);
            if (v == null || v.isNull()) continue;
            if (v.isTextual() && v.textValue().isEmpty()) continue;
            if (v.isBoolean() && !v.booleanValue()) continue;
            if (v.isNumber() && v.asDouble() == 0) continue;
            return v.isTextual() ? v.textValue() : v.toString();
        }
        return null;
    }

    private static String cut(String s, int n) {
        return s.length() > n ? s.substring(0, n) : s;
    }

    /** Выполняет запрос входа или обновления, описанный в окружении как обычный запрос из коллекции. */
    private static Got request(Cfg cfg, boolean isRefresh, St st) throws Exception {
        String label = isRefresh ? "Обновление токена" : "Вход";
        Http.Spec base;
        if (isRefresh) {
            Map<String, String> vals = new LinkedHashMap<>();
            vals.put("refresh_token", st.refresh);
            vals.put("access_token", st.access);
            base = fill(cfg.refresh, vals);
        } else base = cfg.login.copy();
        if (base.timeoutMs <= 0) base.timeoutMs = 30000;
        Http.Result r;
        try {
            r = Http.forward(base);
        } catch (Exception e) {
            throw new IllegalStateException(label + ": " + e.getMessage());
        }
        JsonNode json = Json.parse(r.body == null ? "" : r.body);
        String accessPath = cfg.accessPath == null || cfg.accessPath.isBlank() ? "accessToken" : cfg.accessPath;
        String access = pickToken(json, accessPath);
        boolean ok = r.status >= 200 && r.status < 300;
        if (ok && access != null) {
            String refresh = pickToken(json, cfg.refreshPath == null || cfg.refreshPath.isBlank() ? "refreshToken" : cfg.refreshPath);
            if (refresh == null && isRefresh) refresh = st.refresh;
            Long exp = null;
            if (cfg.expiresPath != null && !cfg.expiresPath.isBlank()) { // срок жизни в секундах из ответа; иначе exp из JWT
                JsonNode v = pick(json, cfg.expiresPath);
                double sec = Double.NaN;
                if (v != null && v.isNumber()) sec = v.asDouble();
                else if (v != null && v.isTextual()) try { sec = Double.parseDouble(v.textValue().trim()); } catch (NumberFormatException ignored) { }
                if (Double.isFinite(sec) && sec > 0) exp = System.currentTimeMillis() + Math.round(sec * 1000);
            }
            if (exp == null) exp = jwtExp(access);
            return new Got(access, refresh, exp);
        }
        String why = firstTruthy(json, "message", "error", "status");
        if (ok) {
            // ответ успешный, но токена по пути нет: подсказываем, какие поля там есть (только имена, без значений)
            String keys = "";
            if (json != null && json.isObject()) {
                List<String> names = new ArrayList<>();
                Iterator<String> it = json.fieldNames();
                while (it.hasNext() && names.size() < 15) names.add(it.next());
                keys = String.join(", ", names);
            }
            throw new IllegalStateException(label + ": в ответе нет токена по пути «" + accessPath + "»"
                    + (keys.isEmpty() ? "" : " (поля верхнего уровня: " + keys + ")")
                    + (why != null ? "; сообщение сервера: " + cut(why, 200) : ""));
        }
        throw new IllegalStateException(label + ": HTTP " + r.status + (why != null ? " — " + cut(why, 200) : ""));
    }

    /** Токен для окружения: из кэша, обновлением или входом. Параллельные вызовы ждут одного входа. */
    public static Info tokenFor(Cfg cfg, boolean force) throws Exception {
        if (cfg == null || cfg.envId == null) throw new IllegalStateException("Не задано окружение для авторизации");
        if (cfg.login == null || cfg.login.url == null || cfg.login.url.isBlank()) throw new IllegalStateException("В окружении не выбран запрос входа");
        List<Object> keyParts = new ArrayList<>();
        keyParts.add(cfg.login); keyParts.add(cfg.refresh); keyParts.add(cfg.accessPath); keyParts.add(cfg.refreshPath); keyParts.add(cfg.expiresPath);
        String key = cfg.key != null ? cfg.key : Json.stable(keyParts);
        CompletableFuture<Info> fut;
        boolean owner = false;
        St st;
        synchronized (LOCK) {
            st = CACHE.get(cfg.envId);
            if (st == null || !st.key.equals(key)) { st = new St(key); CACHE.put(cfg.envId, st); }
            long now = System.currentTimeMillis();
            // запас в минуту, чтобы токен не истёк по дороге; если срок неизвестен, ждём ответа 401
            if (!force && st.access != null && (st.accessExp == null || st.accessExp - 60000 > now)) {
                return new Info(st.access, "cache", st.accessExp, st.refresh != null);
            }
            fut = INFLIGHT.get(cfg.envId);
            if (fut == null) {
                fut = new CompletableFuture<>();
                INFLIGHT.put(cfg.envId, fut);
                owner = true;
            }
        }
        if (owner) {
            try {
                long now = System.currentTimeMillis();
                Got res = null;
                String action = "login";
                if (st.refresh != null && cfg.refresh != null && cfg.refresh.url != null && !cfg.refresh.url.isBlank()
                        && (st.refreshExp == null || st.refreshExp > now + 5000)) {
                    try { res = request(cfg, true, st); action = "refresh"; } catch (Exception e) { res = null; }
                }
                if (res == null) { res = request(cfg, false, st); action = "login"; }
                Info info;
                synchronized (LOCK) {
                    st.access = res.access();
                    st.refresh = res.refresh();
                    st.accessExp = res.exp();
                    st.refreshExp = res.refresh() != null ? jwtExp(res.refresh()) : null;
                    info = new Info(st.access, action, st.accessExp, st.refresh != null);
                }
                fut.complete(info);
            } catch (Exception e) {
                fut.completeExceptionally(e);
            } finally {
                synchronized (LOCK) { INFLIGHT.remove(cfg.envId); }
            }
        }
        try {
            return fut.get();
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof Exception ex) throw ex;
            throw e;
        }
    }

    private static boolean hasHeader(Map<String, String> h, String name) {
        return h.keySet().stream().anyMatch(k -> k.equalsIgnoreCase(name));
    }

    /** Подставляет токен в запрос выбранным способом. {{access_token}} работает при любом способе. */
    static Http.Spec apply(Http.Spec spec, Cfg cfg, String token) {
        String mode = cfg.placement == null || cfg.placement.isEmpty() ? "header" : cfg.placement;
        Http.Spec out = fill(spec, Map.of("access_token", token));
        switch (mode) {
            case "header" -> out.headers.put(cfg.header == null || cfg.header.isBlank() ? "Authorization" : cfg.header,
                    (cfg.prefix == null ? "Bearer " : cfg.prefix) + token);
            case "query" -> out.url = setQueryParam(out.url, cfg.paramName == null || cfg.paramName.isBlank() ? "access_token" : cfg.paramName, token);
            case "cookie" -> {
                String pair = (cfg.paramName == null || cfg.paramName.isBlank() ? "access_token" : cfg.paramName) + "=" + token;
                String key = out.headers.keySet().stream().filter(k -> k.equalsIgnoreCase("cookie")).findFirst().orElse(null);
                if (key != null) out.headers.put(key, out.headers.get(key) + "; " + pair);
                else out.headers.put("Cookie", pair);
            }
            default -> { } // placeholder: кроме подстановки, ничего не добавляем
        }
        return out;
    }

    /**
     * Для просмотра подписанного запроса: подставляет в тело {{access_token}} и {{refresh_token}} (токен берётся как при отправке:
     * из кэша, обновлением или входом). Значения токенов добавляются в secrets, чтобы их можно было скрыть.
     */
    public static String fillBody(Http.Spec spec, List<String> secrets) throws Exception {
        Cfg ta = spec.tokenAuth;
        if (ta == null || spec.body == null) return spec.body;
        Info t = tokenFor(ta, false);
        String refresh;
        synchronized (LOCK) {
            St cached = CACHE.get(ta.envId);
            refresh = cached == null ? null : cached.refresh;
        }
        Map<String, String> vals = new LinkedHashMap<>();
        vals.put("access_token", t.token());
        vals.put("refresh_token", refresh == null ? "{{refresh_token}}" : refresh);
        secrets.add(t.token());
        if (refresh != null) secrets.add(refresh);
        return fillStr(spec.body, vals);
    }

    /** Заменяет параметр адреса или добавляет его (как URL.searchParams.set). */
    static String setQueryParam(String url, String name, String value) {
        int hash = url.indexOf('#');
        String frag = hash >= 0 ? url.substring(hash) : "";
        String rest = hash >= 0 ? url.substring(0, hash) : url;
        int q = rest.indexOf('?');
        String base = q >= 0 ? rest.substring(0, q) : rest;
        String query = q >= 0 ? rest.substring(q + 1) : "";
        String enc = URLEncoder.encode(name, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8);
        List<String> parts = new ArrayList<>();
        boolean set = false;
        for (String p : query.split("&")) {
            if (p.isEmpty()) continue;
            String k = p.contains("=") ? p.substring(0, p.indexOf('=')) : p;
            String dk;
            try { dk = java.net.URLDecoder.decode(k, StandardCharsets.UTF_8); } catch (Exception e) { dk = k; }
            if (dk.equals(name)) {
                if (!set) { parts.add(enc); set = true; }
            } else parts.add(p);
        }
        if (!set) parts.add(enc);
        return base + "?" + String.join("&", parts) + frag;
    }

    /**
     * Отправка запроса с автоматической авторизацией и подписью: при ответе 401 токен обновляется и запрос повторяется один раз.
     * Заголовок, заданный в самом запросе, важнее автоматического токена.
     */
    public static Http.Result send(Http.Spec spec) {
        try {
            Cfg ta = spec.tokenAuth;
            String place = ta == null || ta.placement == null || ta.placement.isEmpty() ? "header" : ta.placement;
            if (ta != null && !("header".equals(place) && hasHeader(spec.headers, ta.header == null || ta.header.isBlank() ? "Authorization" : ta.header))) {
                Info t;
                try {
                    t = tokenFor(ta, false);
                } catch (Exception e) {
                    return Http.Result.error("Авторизация: " + e.getMessage());
                }
                Http.Result out = Http.forward(Signer.withSignature(apply(spec, ta, t.token())));
                String action = t.action();
                Long exp = t.expiresAt();
                if (out.status == 401) { // токен мог быть отозван раньше срока: обновляем и повторяем один раз
                    try {
                        t = tokenFor(ta, true);
                        out = Http.forward(Signer.withSignature(apply(spec, ta, t.token())));
                        action = t.action() + "+retry";
                        exp = t.expiresAt();
                    } catch (Exception e) {
                        out.authError = e.getMessage();
                    }
                }
                out.authAction = action;
                out.expiresAt = exp;
                // токены (доступа и обновления) в «Трафике» скрываются вместе с остальными секретами
                List<String> tk = new ArrayList<>();
                tk.add(t.token());
                synchronized (LOCK) {
                    St cached = CACHE.get(ta.envId);
                    if (cached != null && cached.refresh != null) tk.add(cached.refresh);
                }
                out.secrets = tk;
                out.signed = spec.sign != null;
                return out;
            }
            Http.Result plain = Http.forward(Signer.withSignature(spec));
            plain.signed = spec.sign != null;
            return plain;
        } catch (Exception e) {
            Http.Result r = Http.Result.error(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            if (e instanceof Http.SendError se) { // запрос ушёл, но ответа нет: в «Отладке» будет видно, что именно ушло
                r.sent = se.sent;
                r.sentAt = se.sentAt;
                r.signed = spec.sign != null;
            }
            return r;
        }
    }
}
