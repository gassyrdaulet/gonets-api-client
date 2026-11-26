package kz.gonets;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Экспорт запроса в код: cURL, PowerShell, fetch, Python, Java, «сырой» HTTP. */
public final class CodeExport {
    private CodeExport() {}

    public enum Lang {
        CURL("cURL (bash)"),
        POWERSHELL("PowerShell"),
        FETCH("JavaScript (fetch)"),
        PYTHON("Python (requests)"),
        JAVA("Java (HttpClient)"),
        HTTP("HTTP (как есть)");

        public final String title;
        Lang(String title) { this.title = title; }
    }

    /** Заголовки, которые ставит сам клиент: в код их не пишем. */
    private static final Set<String> CLIENT_HEADERS = Set.of("host", "content-length", "connection", "expect", "upgrade", "transfer-encoding");
    private static final String OUR_UA = "gonets-api-client/1.0";

    /** Запрос для экспорта и пометки о том, чего в коде нет (подпись, токен). */
    public record Prepared(Http.Spec spec, List<String> notes) {}

    /**
     * Собирает запрос для экспорта.
     * substitute — подставить переменные окружения и генераторы (иначе {{имя}} остаются как есть);
     * mask — скрыть секреты (значения переменных окружения с замком) звёздочками.
     */
    public static Prepared prepare(Model.State st, Model.Item req, Model.Env env, boolean substitute, boolean mask) {
        Gen gen = substitute ? new Gen() : new Gen(true);
        Http.Spec s = Logic.spec(st, req, substitute ? env : null, false, gen);
        List<String> notes = new ArrayList<>();
        // токен окружения: настоящий живёт только в памяти приложения, в коде — заглушка
        if ("env".equals(req.auth.type) && env != null) {
            Tokens.Cfg cfg = Logic.tokenCfg(st, env);
            if (cfg != null) {
                boolean headerSet = "header".equals(cfg.placement) && s.headers.keySet().stream().anyMatch(k -> k.equalsIgnoreCase(cfg.header));
                if (!headerSet) s = Tokens.apply(s, cfg, "<access_token>");
                notes.add("<access_token> — токен окружения «" + env.name + "»: получите его запросом входа");
            }
        }
        if (Boolean.TRUE.equals(req.sign) && Logic.isXml(req) && Logic.signCfg(env) != null) {
            notes.add("тело должно быть подписано транспортной подписью ШЭП (WS-Security, ГОСТ); здесь оно без подписи");
        }
        if (OUR_UA.equals(s.headers.get("User-Agent"))) s.headers.remove("User-Agent");
        if (mask) {
            List<String> secrets = Logic.secretValues(env, gen);
            s.url = Logic.maskValues(s.url, secrets);
            if (s.body != null) s.body = Logic.maskValues(s.body, secrets);
            LinkedHashMap<String, String> h2 = new LinkedHashMap<>();
            s.headers.forEach((k, v) -> h2.put(k, Logic.maskValues(v, secrets)));
            s.headers = h2;
        }
        return new Prepared(s, notes);
    }

    public static String render(Lang lang, Prepared p) {
        return switch (lang) {
            case CURL -> curl(p);
            case POWERSHELL -> powershell(p);
            case FETCH -> fetch(p);
            case PYTHON -> python(p);
            case JAVA -> java(p);
            case HTTP -> http(p);
        };
    }

    private static String method(Http.Spec s) {
        return s.method == null || s.method.isBlank() ? "GET" : s.method.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean hasBody(Http.Spec s) { return s.body != null && !s.body.isEmpty(); }

    private static Map<String, String> headers(Http.Spec s) {
        LinkedHashMap<String, String> h = new LinkedHashMap<>();
        s.headers.forEach((k, v) -> { if (!CLIENT_HEADERS.contains(k.toLowerCase(Locale.ROOT))) h.put(k, v); });
        return h;
    }

    private static String header(Http.Spec s, String name) {
        for (Map.Entry<String, String> e : s.headers.entrySet()) if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
        return null;
    }

    private static void comments(StringBuilder b, Prepared p, String prefix) {
        for (String n : p.notes()) b.append(prefix).append(n).append('\n');
        if (p.spec().insecure) b.append(prefix).append("проверка TLS-сертификата отключена").append('\n');
    }

    // ---------- cURL ----------
    private static String sq(String s) { return "'" + s.replace("'", "'\\''") + "'"; }

    private static String curl(Prepared p) {
        Http.Spec s = p.spec();
        StringBuilder b = new StringBuilder();
        comments(b, p, "# ");
        String m = method(s);
        List<String> parts = new ArrayList<>();
        String first = "curl";
        if (m.equals("HEAD")) first += " --head";
        else if (!(m.equals("GET") && !hasBody(s)) && !(m.equals("POST") && hasBody(s))) first += " -X " + m;
        first += " " + sq(s.url);
        parts.add(first);
        if (s.insecure) parts.add("-k");
        headers(s).forEach((k, v) -> parts.add("-H " + sq(k + ": " + v)));
        if (hasBody(s)) parts.add("--data-raw " + sq(s.body));
        b.append(String.join(" \\\n  ", parts));
        return b.toString();
    }

    // ---------- PowerShell ----------
    private static String psq(String s) { return "'" + s.replace("'", "''") + "'"; }

    private static String powershell(Prepared p) {
        Http.Spec s = p.spec();
        StringBuilder b = new StringBuilder();
        comments(b, p, "# ");
        String ct = header(s, "Content-Type"), ua = header(s, "User-Agent");
        Map<String, String> h = headers(s);
        h.keySet().removeIf(k -> k.equalsIgnoreCase("Content-Type") || k.equalsIgnoreCase("User-Agent"));
        if (!h.isEmpty()) {
            b.append("$headers = @{\n");
            h.forEach((k, v) -> b.append("    ").append(psq(k)).append(" = ").append(psq(v)).append('\n'));
            b.append("}\n");
        }
        if (hasBody(s)) b.append("$body = @'\n").append(s.body).append("\n'@\n");
        String m = method(s);
        b.append("$response = Invoke-WebRequest -Uri ").append(psq(s.url))
                .append(" -Method ").append(m.charAt(0)).append(m.substring(1).toLowerCase(Locale.ROOT));
        if (!h.isEmpty()) b.append(" -Headers $headers");
        if (ct != null) b.append(" -ContentType ").append(psq(ct));
        if (ua != null) b.append(" -UserAgent ").append(psq(ua));
        if (hasBody(s)) b.append(" -Body ([System.Text.Encoding]::UTF8.GetBytes($body))");
        if (s.insecure) b.append(" -SkipCertificateCheck");
        b.append(" -UseBasicParsing\n");
        if (s.insecure) b.append("# -SkipCertificateCheck есть в PowerShell 7 и новее\n");
        b.append("$response.StatusCode\n$response.Content");
        return b.toString();
    }

    // ---------- JavaScript (fetch) ----------
    private static String fetch(Prepared p) {
        Http.Spec s = p.spec();
        StringBuilder b = new StringBuilder();
        comments(b, p, "// ");
        if (s.insecure) b.append("// в Node.js для этого: NODE_TLS_REJECT_UNAUTHORIZED=0\n");
        b.append("const response = await fetch(").append(Json.quote(s.url)).append(", {\n");
        b.append("  method: ").append(Json.quote(method(s))).append(",\n");
        Map<String, String> h = headers(s);
        if (!h.isEmpty()) {
            b.append("  headers: {\n");
            List<String> rows = new ArrayList<>();
            h.forEach((k, v) -> rows.add("    " + Json.quote(k) + ": " + Json.quote(v)));
            b.append(String.join(",\n", rows)).append("\n  },\n");
        }
        if (hasBody(s)) {
            String ct = header(s, "Content-Type");
            String pretty = ct != null && ct.toLowerCase(Locale.ROOT).contains("json") ? Json.pretty(s.body) : null;
            if (pretty != null) b.append("  body: JSON.stringify(").append(pretty.replace("\n", "\n  ")).append("),\n");
            else b.append("  body: ").append(Json.quote(s.body)).append(",\n");
        }
        b.append("});\n");
        b.append("console.log(response.status);\nconsole.log(await response.text());");
        return b.toString();
    }

    // ---------- Python (requests) ----------
    private static String python(Prepared p) {
        Http.Spec s = p.spec();
        StringBuilder b = new StringBuilder("import requests\n\n");
        comments(b, p, "# ");
        b.append("url = ").append(Json.quote(s.url)).append('\n');
        Map<String, String> h = headers(s);
        if (!h.isEmpty()) {
            b.append("headers = {\n");
            h.forEach((k, v) -> b.append("    ").append(Json.quote(k)).append(": ").append(Json.quote(v)).append(",\n"));
            b.append("}\n");
        }
        if (hasBody(s)) b.append("payload = ").append(Json.quote(s.body)).append('\n');
        b.append("\nresponse = requests.request(").append(Json.quote(method(s))).append(", url");
        if (!h.isEmpty()) b.append(", headers=headers");
        if (hasBody(s)) b.append(", data=payload.encode(\"utf-8\")");
        if (s.insecure) b.append(", verify=False");
        b.append(")\nprint(response.status_code)\nprint(response.text)");
        return b.toString();
    }

    // ---------- Java (HttpClient) ----------
    private static String java(Prepared p) {
        Http.Spec s = p.spec();
        StringBuilder b = new StringBuilder();
        b.append("import java.net.URI;\nimport java.net.http.HttpClient;\nimport java.net.http.HttpRequest;\nimport java.net.http.HttpResponse;\n\n");
        comments(b, p, "// ");
        if (s.insecure) b.append("// без проверки сертификата нужен свой SSLContext: HttpClient.newBuilder().sslContext(...)\n");
        b.append("HttpClient client = HttpClient.newHttpClient();\n");
        b.append("HttpRequest request = HttpRequest.newBuilder(URI.create(").append(Json.quote(s.url)).append("))\n");
        headers(s).forEach((k, v) -> b.append("        .header(").append(Json.quote(k)).append(", ").append(Json.quote(v)).append(")\n"));
        String pub = hasBody(s) ? "HttpRequest.BodyPublishers.ofString(" + Json.quote(s.body) + ")" : "HttpRequest.BodyPublishers.noBody()";
        b.append("        .method(").append(Json.quote(method(s))).append(", ").append(pub).append(")\n");
        b.append("        .build();\n");
        b.append("HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());\n");
        b.append("System.out.println(response.statusCode());\nSystem.out.println(response.body());");
        return b.toString();
    }

    // ---------- HTTP ----------
    private static String http(Prepared p) {
        Http.Spec s = p.spec();
        StringBuilder b = new StringBuilder();
        comments(b, p, "# ");
        String target = s.url, host = null;
        try {
            URI u = Http.toUri(s.url);
            if (u.getHost() != null) {
                host = u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
                String path = u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath();
                target = path + (u.getRawQuery() != null ? "?" + u.getRawQuery() : "");
            }
        } catch (Exception ignored) {
            // адрес с {{переменной}}: оставляем как есть
        }
        b.append(method(s)).append(' ').append(target).append(" HTTP/1.1\n");
        if (host != null) b.append("Host: ").append(host).append('\n');
        headers(s).forEach((k, v) -> b.append(k).append(": ").append(v).append('\n'));
        if (hasBody(s)) b.append('\n').append(s.body);
        return b.toString().stripTrailing();
    }
}
