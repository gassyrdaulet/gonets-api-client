package kz.gonets;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.Iterator;
import java.util.Map;

/** Jackson и форматирование JSON так же, как JSON.stringify(x, null, 2) в браузере. */
public final class Json {
    private Json() {}

    public static final ObjectMapper M = JsonMapper.builder()
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .build();

    /** Для сравнения запросов: свойства по алфавиту, так что порядок полей не влияет. */
    private static final ObjectMapper SORTED = JsonMapper.builder()
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .build();

    /** Строгий разбор тела (как JSON.parse): лишнее после значения считается ошибкой, дроби без потери точности. */
    private static final ObjectMapper STRICT = JsonMapper.builder()
            .configure(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, true)
            .configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, true)
            .configure(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS, false)
            .build();

    public static String write(Object o) {
        try { return M.writeValueAsString(o); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public static String stable(Object o) {
        try { return SORTED.writeValueAsString(o); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** Глубокая копия через JSON. */
    public static <T> T copy(T o, Class<T> type) {
        try { return M.readValue(M.writeValueAsBytes(o), type); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** Разбор тела ответа или запроса; null, если это не JSON. */
    public static JsonNode parse(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            JsonNode n = STRICT.readTree(text);
            return n == null || n.isMissingNode() ? null : n;
        } catch (Exception e) {
            return null;
        }
    }

    /** Отформатированный JSON или null, если текст не JSON. */
    public static String pretty(String text) {
        JsonNode n = parse(text);
        if (n == null) return null;
        StringBuilder b = new StringBuilder(text.length() + text.length() / 4);
        writePretty(n, b, 0);
        return b.toString();
    }

    private static void indent(StringBuilder b, int depth) {
        for (int i = 0; i < depth; i++) b.append("  ");
    }

    private static void writePretty(JsonNode n, StringBuilder b, int depth) {
        if (n.isObject()) {
            if (n.isEmpty()) { b.append("{}"); return; }
            b.append("{\n");
            Iterator<Map.Entry<String, JsonNode>> it = n.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                indent(b, depth + 1);
                quote(e.getKey(), b);
                b.append(": ");
                writePretty(e.getValue(), b, depth + 1);
                if (it.hasNext()) b.append(',');
                b.append('\n');
            }
            indent(b, depth);
            b.append('}');
        } else if (n.isArray()) {
            if (n.isEmpty()) { b.append("[]"); return; }
            b.append("[\n");
            for (int i = 0; i < n.size(); i++) {
                indent(b, depth + 1);
                writePretty(n.get(i), b, depth + 1);
                if (i < n.size() - 1) b.append(',');
                b.append('\n');
            }
            indent(b, depth);
            b.append(']');
        } else if (n.isTextual()) {
            quote(n.textValue(), b);
        } else if (n.isBigDecimal() || n.isFloatingPointNumber()) {
            BigDecimal d = n.decimalValue();
            b.append(d.signum() == 0 ? "0" : d.stripTrailingZeros().toPlainString());
        } else {
            b.append(n.toString()); // целые числа, true, false, null
        }
    }

    /** Строка в кавычках с экранированием, как у JSON.stringify (кириллица не экранируется). */
    public static void quote(String s, StringBuilder b) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                case '\b' -> b.append("\\b");
                case '\f' -> b.append("\\f");
                default -> {
                    if (c < 0x20 || (Character.isSurrogate(c) && !validSurrogate(s, i))) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        b.append('"');
    }

    private static boolean validSurrogate(String s, int i) {
        char c = s.charAt(i);
        if (Character.isHighSurrogate(c)) return i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1));
        return i > 0 && Character.isHighSurrogate(s.charAt(i - 1));
    }

    public static String quote(String s) {
        StringBuilder b = new StringBuilder(s.length() + 2);
        quote(s, b);
        return b.toString();
    }
}
