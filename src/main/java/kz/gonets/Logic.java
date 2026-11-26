package kz.gonets;

import kz.gonets.Model.Env;
import kz.gonets.Model.EnvAuth;
import kz.gonets.Model.EnvSign;
import kz.gonets.Model.Item;
import kz.gonets.Model.KV;
import kz.gonets.Model.State;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Логика без интерфейса: переменные, сборка запроса, поиск в дереве, cURL, экспорт. */
public final class Logic {
    private Logic() {}

    // ---------- переменные ----------
    /** {{имя}} — переменная окружения; {{$генератор}} или {{$генератор:аргумент}} — генерируемое значение (см. {@link Gen}). */
    private static final Pattern VAR = Pattern.compile("\\{\\{\\s*(?:\\$([A-Za-z]+)(?::([^}]*))?|([^}\\s]+))\\s*\\}\\}");

    public static Map<String, String> vars(Env env) {
        Map<String, String> m = new LinkedHashMap<>();
        if (env != null && env.vars != null) for (KV v : env.vars) if (v.enabled() && !v.key().isEmpty()) m.put(v.key(), v.val());
        return m;
    }

    /** Подстановка с отдельными генерируемыми значениями (для одиночных полей вроде пути к ключу). */
    public static String subst(String s, Env env) {
        return subst(s, env, new Gen());
    }

    /** Подстановка в рамках одного запроса: переменная окружения с генератором получает одно значение на весь запрос. */
    public static String subst(String s, Env env, Gen gen) {
        if (s == null) return "";
        Map<String, String> m = vars(env);
        Matcher mt = VAR.matcher(s);
        StringBuilder b = new StringBuilder();
        while (mt.find()) {
            String out;
            if (mt.group(1) != null) { // генератор прямо в запросе: каждое вхождение — новое значение
                String g = gen.keep ? null : Gen.generate(mt.group(1), mt.group(2));
                out = g == null ? mt.group() : g;
            } else {
                String name = mt.group(3);
                if (!m.containsKey(name)) out = mt.group();
                else {
                    out = gen.envValues.get(name);
                    if (out == null) {
                        // в значении переменной могут быть генераторы (но не другие переменные: так нет зацикливания)
                        out = expandGenerators(m.get(name), gen);
                        gen.envValues.put(name, out);
                    }
                }
            }
            mt.appendReplacement(b, Matcher.quoteReplacement(out));
        }
        mt.appendTail(b);
        return b.toString();
    }

    private static String expandGenerators(String v, Gen gen) {
        if (v == null || gen.keep || !v.contains("{{")) return v == null ? "" : v;
        Matcher mt = VAR.matcher(v);
        StringBuilder b = new StringBuilder();
        while (mt.find()) {
            String g = mt.group(1) != null ? Gen.generate(mt.group(1), mt.group(2)) : null;
            mt.appendReplacement(b, Matcher.quoteReplacement(g == null ? mt.group() : g));
        }
        mt.appendTail(b);
        return b.toString();
    }

    // ---------- дерево ----------
    /** Положение узла: сам узел, список, в котором он лежит, индекс и родительская папка. */
    public record Loc(Item node, List<Item> arr, int i, Item parent) {}

    public static Loc locate(State st, String id) {
        return id == null ? null : locate(st.tree, id, null);
    }

    private static Loc locate(List<Item> arr, String id, Item parent) {
        for (int i = 0; i < arr.size(); i++) {
            Item n = arr.get(i);
            if (id.equals(n.id)) return new Loc(n, arr, i, parent);
            if (n.folder() && n.children != null) {
                Loc f = locate(n.children, id, n);
                if (f != null) return f;
            }
        }
        return null;
    }

    public static Item findReq(State st, String id) {
        if (id == null || id.isEmpty()) return null;
        Loc l = locate(st, id);
        return l != null && !l.node.folder() ? l.node : null;
    }

    public static boolean containsId(Item folder, String id) {
        for (Item c : folder.children) if (id.equals(c.id) || (c.folder() && containsId(c, id))) return true;
        return false;
    }

    public static int countReqs(Item folder) {
        int n = 0;
        for (Item c : folder.children) n += c.folder() ? countReqs(c) : 1;
        return n;
    }

    public record Flat(String id, String label) {}

    /** Запросы коллекции списком (с путём по папкам) для выбора в окружении. */
    public static List<Flat> flatRequests(State st) {
        List<Flat> out = new ArrayList<>();
        flat(st.tree, "", out);
        return out;
    }

    private static void flat(List<Item> list, String path, List<Flat> out) {
        for (Item n : list) {
            if (n.folder()) flat(n.children, path + n.name + " / ", out);
            else out.add(new Flat(n.id, path + (notEmpty(n.name) ? n.name : notEmpty(n.url) ? n.url : "без названия")));
        }
    }

    static boolean notEmpty(String s) { return s != null && !s.isEmpty(); }

    /** Глубокая копия узла с новыми id (для папки копируется всё содержимое). */
    public static Item cloneNode(Item node) {
        Item c = Json.copy(node, Item.class);
        reid(c);
        c.name = (notEmpty(c.name) ? c.name : notEmpty(c.url) ? c.url : "Запрос") + " (копия)";
        return c;
    }

    private static void reid(Item n) {
        n.id = Model.uid();
        if (n.folder()) n.children.forEach(Logic::reid);
    }

    // ---------- автоавторизация и подпись окружения ----------
    /** Настройки для получения токена: вход и обновление — обычные запросы из коллекции. null, если вход не выбран. */
    public static Tokens.Cfg tokenCfg(State st, Env env) {
        EnvAuth a = env.auth();
        Item login = findReq(st, a.loginReqId);
        if (login == null) return null;
        Item refresh = findReq(st, a.refreshReqId);
        Tokens.Cfg c = new Tokens.Cfg();
        c.envId = env.id;
        c.login = spec(st, login, env, false);
        c.refresh = refresh != null ? spec(st, refresh, env, false) : null;
        // ключ кэша токенов: те же запросы, но генераторы не раскрыты, иначе каждый новый UUID в запросе входа сбрасывал бы кэш
        Gen keep = new Gen(true);
        c.key = Json.stable(List.of(spec(st, login, env, false, keep), refresh != null ? spec(st, refresh, env, false, keep) : "", a.accessPath, a.refreshPath, a.expiresPath));
        c.accessPath = a.accessPath.trim().isEmpty() ? "accessToken" : a.accessPath.trim();
        c.refreshPath = a.refreshPath.trim().isEmpty() ? "refreshToken" : a.refreshPath.trim();
        c.expiresPath = a.expiresPath.trim();
        c.placement = a.placement;
        c.header = a.header.trim().isEmpty() ? "Authorization" : a.header.trim();
        c.prefix = a.prefix == null ? "Bearer " : a.prefix;
        c.paramName = a.paramName.trim().isEmpty() ? "access_token" : a.paramName.trim();
        return c;
    }

    /** Настройки транспортной подписи окружения; null, если ключ не указан. */
    public static Signer.Cfg signCfg(Env env) {
        if (env == null) return null;
        EnvSign s = env.sign();
        if (s.p12.trim().isEmpty()) return null;
        Signer.Cfg c = new Signer.Cfg();
        c.p12 = subst(s.p12, env).trim();
        c.password = subst(s.password, env);
        // алиас не задаётся, берётся последний ключ в файле
        c.alias = "";
        return c;
    }

    public static boolean isXml(Item r) {
        return "xml".equals(r.bodyType) || "soap".equals(r.bodyType);
    }

    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "json", "application/json", "xml", "application/xml; charset=utf-8",
            "soap", "text/xml; charset=utf-8", "form", "application/x-www-form-urlencoded");

    /** Собирает запрос для отправки (подставляет переменные окружения env). */
    public static Http.Spec spec(State st, Item req, Env env, boolean withTokenAuth) {
        return spec(st, req, env, withTokenAuth, new Gen());
    }

    /** gen — генерируемые значения этого запроса (одно значение переменной с генератором на весь запрос). */
    public static Http.Spec spec(State st, Item req, Env env, boolean withTokenAuth, Gen gen) {
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        for (KV h : req.headers) if (h.enabled() && !h.key().isEmpty()) headers.put(subst(h.k, env, gen), subst(h.v, env, gen));
        java.util.function.Predicate<String> has = n -> headers.keySet().stream().anyMatch(k -> k.equalsIgnoreCase(n));
        String body = null;
        if (!"none".equals(req.bodyType) && !"HEAD".equals(req.method)) {
            body = subst(req.body, env, gen);
            if (!has.test("content-type")) headers.put("Content-Type", CONTENT_TYPES.getOrDefault(req.bodyType, "text/plain"));
        }
        if ("bearer".equals(req.auth.type) && !req.auth.token.isEmpty() && !has.test("authorization")) headers.put("Authorization", "Bearer " + subst(req.auth.token, env, gen));
        if ("basic".equals(req.auth.type) && !has.test("authorization")) headers.put("Authorization", "Basic " + Http.basic(subst(req.auth.user, env, gen), subst(req.auth.pass, env, gen)));
        if (!has.test("user-agent")) headers.put("User-Agent", "gonets-api-client/1.0");
        Http.Spec s = new Http.Spec();
        s.method = req.method;
        s.url = subst(req.url.trim(), env, gen);
        s.headers = headers;
        s.body = body;
        s.timeoutMs = Math.round((req.opts.timeout == null || req.opts.timeout <= 0 ? 60 : req.opts.timeout) * 1000);
        s.insecure = Boolean.TRUE.equals(req.opts.insecure);
        if (withTokenAuth && "env".equals(req.auth.type) && env != null) {
            Tokens.Cfg cfg = tokenCfg(st, env);
            if (cfg != null && (!"header".equals(cfg.placement) || !has.test(cfg.header))) s.tokenAuth = cfg;
        }
        if (Boolean.TRUE.equals(req.sign) && isXml(req) && body != null && env != null) {
            Signer.Cfg sc = signCfg(env);
            if (sc != null) s.sign = sc;
        }
        return s;
    }

    // ---------- параметры и URL ----------
    private static String enc(String s) {
        // как encodeURIComponent: пробел как %20, а не +
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20").replace("%21", "!").replace("%27", "'")
                .replace("%28", "(").replace("%29", ")").replace("%7E", "~");
    }

    private static String dec(String s) {
        try { return URLDecoder.decode(s, StandardCharsets.UTF_8); } catch (Exception e) { return s; }
    }

    public static String urlFromParams(String url, List<KV> params) {
        String base = url.split("\\?", 2)[0];
        List<String> q = new ArrayList<>();
        for (KV p : params) if (p.enabled() && !p.key().isEmpty()) q.add(enc(p.k) + "=" + enc(p.val()));
        return base + (q.isEmpty() ? "" : "?" + String.join("&", q));
    }

    public static List<KV> paramsFromUrl(String url) {
        List<KV> rows = new ArrayList<>();
        int qi = url.indexOf('?');
        if (qi >= 0) {
            for (String pair : url.substring(qi + 1).split("&")) {
                if (pair.isEmpty()) continue;
                int eq = pair.indexOf('=');
                String k = eq >= 0 ? pair.substring(0, eq) : pair, v = eq >= 0 ? pair.substring(eq + 1) : "";
                rows.add(new KV(dec(k), dec(v)));
            }
        }
        rows.add(new KV());
        return rows;
    }

    // ---------- сравнение (несохранённые изменения) ----------
    /** Нормальная форма запроса: без типа и пустых строк таблиц, порядок ключей не важен. */
    public static String norm(Item r) {
        Item c = Json.copy(r, Item.class);
        c.type = null;
        c.sign = Boolean.TRUE.equals(r.sign);
        c.params = filled(r.params);
        c.headers = filled(r.headers);
        if (c.opts != null && c.opts.timeout != null) c.opts.timeout = (double) c.opts.timeout; // 60 и 60.0 одинаковы
        return Json.stable(c);
    }

    private static List<KV> filled(List<KV> rows) {
        List<KV> out = new ArrayList<>();
        if (rows != null) for (KV x : rows) if (!x.key().isEmpty() || !x.val().isEmpty()) {
            KV k = new KV(x.key(), x.val());
            k.on = x.enabled();
            out.add(k);
        }
        return out;
    }

    // ---------- перенос старой авторизации ----------
    /** Старая настройка (URL, логин и пароль прямо в окружении) переносится в обычные запросы папки «Авторизация». */
    public static void migrateLegacyAuth(State st, Env env, String baseUrl, String username, String password, String appName, String refreshUrl) {
        setVar(env, "base_url", baseUrl);
        setVar(env, "username", username);
        setVar(env, "password", password);
        Item folder = st.tree.stream().filter(n -> n.folder() && "Авторизация".equals(n.name)).findFirst().orElse(null);
        if (folder == null) { folder = Item.folder("Авторизация"); st.tree.add(folder); }
        StringBuilder lb = new StringBuilder("{\n  \"username\": \"{{username}}\",\n  \"password\": \"{{password}}\"");
        if (notEmpty(appName)) lb.append(",\n  \"appName\": ").append(Json.quote(appName));
        lb.append("\n}");
        Item login = Item.blankReq();
        login.type = "req"; login.name = "Вход (" + env.name + ")"; login.method = "POST"; login.url = "{{base_url}}/login";
        login.bodyType = "json"; login.body = lb.toString();
        Item refresh = Item.blankReq();
        refresh.type = "req"; refresh.name = "Обновление токена (" + env.name + ")"; refresh.method = "POST";
        refresh.url = notEmpty(refreshUrl) ? refreshUrl : "{{base_url}}/token/refresh";
        refresh.headers = new ArrayList<>(List.of(new KV("Authorization", "Bearer {{refresh_token}}"), new KV()));
        folder.children.add(login);
        folder.children.add(refresh);
        EnvAuth a = env.auth();
        a.enabled = true; a.loginReqId = login.id; a.refreshReqId = refresh.id; a.accessPath = "accessToken"; a.refreshPath = "refreshToken";
        a.header = "Authorization"; a.prefix = "Bearer ";
    }

    private static void setVar(Env env, String k, String v) {
        KV row = env.vars.stream().filter(x -> k.equals(x.k)).findFirst().orElse(null);
        if (row == null) {
            row = new KV(k, "");
            KV last = env.vars.isEmpty() ? null : env.vars.get(env.vars.size() - 1);
            if (last != null && last.key().isEmpty() && last.val().isEmpty()) env.vars.add(env.vars.size() - 1, row);
            else env.vars.add(row);
        }
        if (notEmpty(v) && !notEmpty(row.v)) row.v = v;
    }

    // ---------- cURL ----------
    private static final Pattern CURL_TOKEN = Pattern.compile("'([^']*)'|\"((?:\\\\.|[^\"\\\\])*)\"|(\\S+)");

    public static Item parseCurl(String src) {
        List<String> tokens = new ArrayList<>();
        Matcher m = CURL_TOKEN.matcher(src.replaceAll("\\\\\\r?\\n", " "));
        while (m.find()) tokens.add(m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2).replaceAll("\\\\(.)", "$1") : m.group(3));
        Item r = Item.blankReq();
        r.headers = new ArrayList<>();
        String data = null;
        for (int i = 1; i < tokens.size(); i++) {
            String t = tokens.get(i);
            String next = i + 1 < tokens.size() ? tokens.get(i + 1) : null;
            switch (t) {
                case "-X", "--request" -> { r.method = (next == null ? "GET" : next).toUpperCase(); i++; }
                case "-H", "--header" -> {
                    String h = next == null ? "" : next; i++;
                    int p = h.indexOf(':');
                    if (p > 0) r.headers.add(new KV(h.substring(0, p).trim(), h.substring(p + 1).trim()));
                }
                case "-d", "--data", "--data-raw", "--data-binary", "--data-urlencode" -> { data = next == null ? "" : next; i++; }
                case "-u", "--user" -> {
                    String u = next == null ? "" : next; i++;
                    int p = u.indexOf(':');
                    r.auth.type = "basic";
                    r.auth.user = p >= 0 ? u.substring(0, p) : u;
                    r.auth.pass = p >= 0 ? u.substring(p + 1) : "";
                }
                case "-k", "--insecure" -> r.opts.insecure = true;
                default -> { if (!t.startsWith("-") && r.url.isEmpty()) r.url = t; }
            }
        }
        if (data != null) {
            r.body = data;
            r.bodyType = data.matches("(?s)^\\s*[\\[{].*") ? "json" : data.matches("(?s)^\\s*<.*") ? "xml" : "text";
            if ("GET".equals(r.method)) r.method = "POST";
        }
        r.headers.add(new KV());
        r.name = r.url;
        r.params = paramsFromUrl(r.url);
        return r;
    }

    // ---------- экспорт ----------
    /** Как токен окружения попадёт в запрос (для экспорта). */
    static String[] tokenNote(Tokens.Cfg t) {
        String m = t.placement == null ? "header" : t.placement;
        return switch (m) {
            case "header" -> new String[]{t.header, (t.prefix == null ? "Bearer " : t.prefix) + "*** (токен окружения)"};
            case "cookie" -> new String[]{"Cookie", t.paramName + "=*** (токен окружения)"};
            case "query" -> new String[]{"(токен окружения)", "параметр URL «" + t.paramName + "»"};
            default -> new String[]{"(токен окружения)", "подстановка {{access_token}}"};
        };
    }

    /**
     * Отчёт для окна «Отладка»: только то, что реально ушло и пришло (снимок в Result), без пересборки запроса.
     * mask — скрыть секреты (значения переменных окружения с замком);
     * hideUrl — адрес и хост заменяются на ***; pretty — тело ответа отформатировать (иначе как пришло).
     */
    public static String debugReport(Http.Result res, boolean mask, boolean hideUrl, boolean pretty) {
        if (res == null) return "Запрос ещё не отправлялся. Нажмите «Отправить» (Ctrl+Enter), и здесь появится то, что ушло на сервер и что пришло в ответ.";
        java.util.function.Function<Map<String, String>, String> hdrMap = h -> {
            List<String> l = new ArrayList<>();
            h.forEach((k, v) -> l.add(k + ": " + v));
            return String.join("\n", l);
        };
        java.time.format.DateTimeFormatter at = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss.SSS").withZone(java.time.ZoneId.systemDefault());
        List<String> out = new ArrayList<>();
        Http.Spec s = res.sent;
        if (s == null) {
            out.add("### Запрос не отправлен");
            out.add("Ошибка: " + res.error);
        } else {
            out.add("### Отправлено" + (res.sentAt > 0 ? " · " + at.format(java.time.Instant.ofEpochMilli(res.sentAt)) : "")
                    + (res.requestName != null && !res.requestName.isBlank() ? " · " + res.requestName.trim() : ""));
            out.add(s.method + " " + s.url);
            if (!s.headers.isEmpty()) out.add(hdrMap.apply(s.headers));
            out.add("(Host, Content-Length и Connection добавляет HTTP-клиент)");
            if (res.signed) out.add("(тело подписано транспортной подписью ШЭП: ниже — уже подписанный XML)");
            if (res.authAction != null) out.add("(токен окружения: " + authActionText(res.authAction) + ")");
            if (s.insecure) out.add("(проверка TLS-сертификата отключена)");
            if (s.body != null && !s.body.isEmpty()) { out.add(""); out.add(s.body); }
            out.add("");
            if (res.error != null) {
                out.add("### Ответ не получен");
                out.add("Ошибка: " + res.error);
            } else {
                out.add("### Получено · " + res.status + " " + res.statusText + " · " + res.timeMs + " мс · " + Pretty.size(res.size));
                List<String> rh = new ArrayList<>();
                for (String[] h : res.headers) rh.add(h[0] + ": " + h[1]);
                out.add(String.join("\n", rh));
                if (res.authError != null) out.add("(повторный вход после 401 не удался: " + res.authError + ")");
                out.add("");
                if (res.binary) out.add("(бинарное тело, " + Pretty.size(res.size) + ")");
                else {
                    String b = res.body == null ? "" : res.body;
                    if (pretty) b = Pretty.body(b).text();
                    out.add(b.isEmpty() ? "(пустое тело)" : b);
                }
            }
        }
        String text = String.join("\n", out);
        if (mask) text = maskValues(text, res.secrets);
        if (hideUrl && s != null) {
            Item named = new Item();
            named.url = res.requestUrl;
            text = maskValues(text, addressValues(named, s));
        }
        return text;
    }

    private static String authActionText(String a) {
        return switch (a) {
            case "cache" -> "из кэша";
            case "refresh" -> "обновлён";
            case "login" -> "получен входом";
            case "refresh+retry" -> "обновлён после 401";
            case "login+retry" -> "получен входом после 401";
            default -> a;
        };
    }

    /** Всё, по чему адрес можно узнать: URL как введён и как отправлен, а также хост (с портом и без). */
    static List<String> addressValues(Item cur, Http.Spec spec) {
        List<String> out = new ArrayList<>();
        for (String u : new String[]{spec.url, cur == null ? null : cur.url}) {
            if (u == null || u.isBlank()) continue;
            out.add(u.trim());
            String noQuery = u.trim().split("[?#]", 2)[0]; // название запроса часто содержит адрес без параметров
            if (!noQuery.isEmpty()) out.add(noQuery);
            try {
                java.net.URI uri = new java.net.URI(u.trim());
                if (uri.getHost() != null) {
                    out.add(uri.getHost());
                    if (uri.getPort() > 0) out.add(uri.getHost() + ":" + uri.getPort());
                }
            } catch (java.net.URISyntaxException ignored) {
                // адрес с {{переменной}} или пробелами: достаточно замены URL целиком
            }
        }
        return out;
    }

    /** Самое короткое значение, которое скрывается: «1» или «ok» заменили бы полтекста. */
    static final int MIN_SECRET = 3;

    /** Значения переменных окружения с замком, подставленные в этот запрос (gen — тот же, что при сборке запроса). */
    public static List<String> secretValues(Env env, Gen gen) {
        List<String> out = new ArrayList<>();
        if (env == null || env.vars == null) return out;
        for (KV v : env.vars) {
            if (!v.enabled() || !v.hidden() || v.key().isEmpty()) continue;
            // переменная попала в запрос: берём подставленное значение; иначе — как записана (если в ней нет генераторов)
            String val = gen != null ? gen.envValues.get(v.key()) : null;
            if (val == null && !v.val().contains("{{$")) val = v.val();
            if (val != null && val.length() >= MIN_SECRET) out.add(val);
        }
        return out;
    }

    /** Заменяет значения и их URL-кодированный вид на ***; длинные значения первыми, чтобы короткие не разрезали их. */
    public static String maskValues(String text, List<String> secrets) {
        if (secrets == null || secrets.isEmpty()) return text;
        List<String> all = new ArrayList<>();
        for (String s : secrets) {
            if (s == null || s.length() < MIN_SECRET) continue;
            all.add(s);
            String enc = URLEncoder.encode(s, StandardCharsets.UTF_8);
            if (!enc.equals(s)) all.add(enc);
            String enc20 = enc.replace("+", "%20");
            if (!enc20.equals(enc)) all.add(enc20);
        }
        all.sort((a, b) -> Integer.compare(b.length(), a.length()));
        for (String s : all) text = text.replace(s, "***");
        return text;
    }
}
