package kz.gonets;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Данные приложения в том же виде, что и data.json прежних версий: файл переносится между версиями как есть.
 * Поля открытые: их читает и пишет Jackson, пустые (null) в файл не попадают.
 */
public final class Model {
    private Model() {}

    private static final SecureRandom RND = new SecureRandom();
    private static final String ABC = "0123456789abcdefghijklmnopqrstuvwxyz";

    /** Короткий случайный идентификатор, как uid() в прежней странице. */
    public static String uid() {
        StringBuilder b = new StringBuilder(8);
        for (int i = 0; i < 8; i++) b.append(ABC.charAt(RND.nextInt(ABC.length())));
        return b.toString();
    }

    public static class State {
        /** Коллекция: папки и запросы. */
        public List<Item> tree = new ArrayList<>();
        /** Черновики: несохранённые правки по id запроса. */
        public Map<String, Item> drafts = new LinkedHashMap<>();
        public List<Item> history = new ArrayList<>();
        public List<Env> envs = new ArrayList<>();
        public String activeEnv = "default";
        public UiPrefs ui;
        /** Старый формат: плоский список запросов (переносится в tree при загрузке). */
        public List<Item> requests;
    }

    /** Узел коллекции: папка (type = folder) или запрос (type = req; у черновиков и истории типа может не быть). */
    public static class Item {
        public String type;
        public String id;
        public String name;
        // папка
        public Boolean open;
        public List<Item> children;
        // запрос
        public String method;
        public String url;
        public List<KV> params;
        public List<KV> headers;
        public String bodyType;
        public String body;
        public ReqAuth auth;
        public ReqOpts opts;
        public Boolean sign;

        public boolean folder() { return "folder".equals(type); }

        public static Item folder(String name) {
            Item f = new Item();
            f.type = "folder"; f.id = uid(); f.name = name; f.open = true; f.children = new ArrayList<>();
            return f;
        }

        public static Item blankReq() {
            Item r = new Item();
            r.id = uid(); r.name = ""; r.method = "GET"; r.url = "";
            r.params = new ArrayList<>(List.of(new KV())); r.headers = new ArrayList<>(List.of(new KV()));
            r.bodyType = "none"; r.body = "";
            r.auth = new ReqAuth(); r.opts = new ReqOpts(); r.sign = false;
            return r;
        }

        /** Заполняет недостающие поля (данные старых версий), чтобы интерфейсу не приходилось проверять null. */
        public Item fix() {
            if (folder()) {
                if (children == null) children = new ArrayList<>();
                if (name == null) name = "";
                if (open == null) open = false;
                children.forEach(Item::fix);
                return this;
            }
            if (id == null) id = uid();
            if (name == null) name = "";
            if (method == null || method.isEmpty()) method = "GET";
            if (url == null) url = "";
            if (params == null) params = new ArrayList<>();
            if (headers == null) headers = new ArrayList<>();
            if (bodyType == null) bodyType = "none";
            if (body == null) body = "";
            if (auth == null) auth = new ReqAuth();
            auth.fix();
            if (opts == null) opts = new ReqOpts();
            if (opts.timeout == null || opts.timeout <= 0) opts.timeout = 60.0;
            if (opts.insecure == null) opts.insecure = false;
            if (sign == null) sign = false;
            return this;
        }
    }

    public static class KV {
        public String k = "";
        public String v = "";
        public Boolean on = true;
        /** Только у переменных окружения: значение скрывается при экспорте. null — не секрет (в data.json не пишется). */
        public Boolean secret;

        public KV() {}
        public KV(String k, String v) { this.k = k; this.v = v; }

        public boolean enabled() { return on == null || on; }
        public boolean hidden() { return Boolean.TRUE.equals(secret); }
        public String key() { return k == null ? "" : k; }
        public String val() { return v == null ? "" : v; }
    }

    public static class ReqAuth {
        public String type = "none";
        public String token = "";
        public String user = "";
        public String pass = "";

        public void fix() {
            if (type == null) type = "none";
            if (token == null) token = "";
            if (user == null) user = "";
            if (pass == null) pass = "";
        }
    }

    public static class ReqOpts {
        public Double timeout = 60.0;
        public Boolean insecure = false;
    }

    public static class Env {
        public String id;
        public String name;
        public List<KV> vars = new ArrayList<>();
        public EnvAuth auth;
        public EnvSign sign;

        public EnvAuth auth() {
            if (auth == null) auth = new EnvAuth();
            auth.fix();
            return auth;
        }

        public EnvSign sign() {
            if (sign == null) sign = new EnvSign();
            sign.fix();
            return sign;
        }
    }

    /** Автоматическая авторизация окружения: вход и обновление токена — обычные запросы из коллекции. */
    public static class EnvAuth {
        public Boolean enabled = false;
        public String loginReqId = "";
        public String refreshReqId = "";
        public String accessPath = "accessToken";
        public String refreshPath = "refreshToken";
        public String expiresPath = "";
        public String placement = "header";
        public String header = "Authorization";
        public String prefix = "Bearer ";
        public String paramName = "access_token";
        // старый формат (адрес, логин и пароль прямо в окружении): переносится в запросы при загрузке
        public String loginUrl;
        public String refreshUrl;
        public String username;
        public String password;
        public String appName;
        public Boolean insecure;

        public void fix() {
            if (enabled == null) enabled = false;
            if (loginReqId == null) loginReqId = "";
            if (refreshReqId == null) refreshReqId = "";
            if (accessPath == null) accessPath = "accessToken";
            if (refreshPath == null) refreshPath = "refreshToken";
            if (expiresPath == null) expiresPath = "";
            if (placement == null || placement.isEmpty()) placement = "header";
            if (header == null) header = "Authorization";
            // prefix может быть пустой строкой намеренно (без «Bearer »)
            if (paramName == null) paramName = "access_token";
        }
    }

    /** Транспортная подпись окружения. Поле java осталось от версии с внешним помощником и больше не используется. */
    public static class EnvSign {
        public String p12 = "";
        public String password = "";
        public String alias = "";
        public String java;

        public void fix() {
            if (p12 == null) p12 = "";
            if (password == null) password = "";
            if (alias == null) alias = "";
        }
    }

    /** Настройки вида. Первые шесть полей совпадают с прежней страницей, остальные добавлены для окна приложения. */
    public static class UiPrefs {
        public Double sideW = 270.0;
        public Boolean collapsed = false;
        public Boolean horiz = false;
        public Double split = 0.5;
        public Boolean wrap = true;
        public Boolean lines = true;
        public Double winX;
        public Double winY;
        public Double winW;
        public Double winH;
        public Boolean maximized;
        public Double fontSize;
        public String exportLang;
        // окно «Настройки»
        /** dark или light. */
        public String theme = "dark";
        /** Таймаут новых запросов, секунды. */
        public Double defaultTimeout = 60.0;
        /** Сколько последних отправок хранить в истории. */
        public Integer historyLimit = 50;
        /** Открывать при запуске запрос, который был открыт последним. */
        public Boolean restoreLast = true;
        public String lastReqId;

        public void fix() {
            if (sideW == null) sideW = 270.0;
            if (collapsed == null) collapsed = false;
            if (horiz == null) horiz = false;
            if (split == null) split = 0.5;
            if (wrap == null) wrap = true;
            if (lines == null) lines = true;
            if (theme == null || !(theme.equals("dark") || theme.equals("light"))) theme = "dark";
            if (defaultTimeout == null || defaultTimeout <= 0) defaultTimeout = 60.0;
            if (historyLimit == null || historyLimit < 0) historyLimit = 50;
            if (restoreLast == null) restoreLast = true;
        }

        public boolean light() { return "light".equals(theme); }
    }
}
