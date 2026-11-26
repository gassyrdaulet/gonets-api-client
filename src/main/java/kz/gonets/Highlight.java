package kz.gonets;

import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Подсветка JSON, XML и переменных {{имя}}: те же регулярные выражения и классы, что в прежней странице.
 * Подсветка терпима к недописанному тексту (нужна для редактора).
 */
public final class Highlight {
    private Highlight() {}

    public enum Mode { JSON, XML, VARS, PLAIN, HEADERS }

    private static final Pattern HEADER_NAME = Pattern.compile("(?m)^[^:\\n]+(?=:)");

    /** Отрезок текста с классом CSS. */
    public record Range(int start, int end, String cls) {}

    private static final Pattern VAR_RE = Pattern.compile("\\{\\{[^}]*\\}\\}");
    private static final Pattern XML_RE = Pattern.compile(
            "<!\\[CDATA\\[[\\s\\S]*?\\]\\]>|<!--[\\s\\S]*?-->|<\\?[\\s\\S]*?\\?>|</?[^\\s>/]+(?:\\s+[^\\s=>/]+\\s*=\\s*(?:\"[^\"]*\"|'[^']*'))*\\s*/?>|[^<]+|<");
    private static final Pattern TAG_RE = Pattern.compile("^<(/?)([^\\s>/]+)([\\s\\S]*?)(/?)>$");
    private static final Pattern ATTR_RE = Pattern.compile("([^\\s=]+)(\\s*=\\s*)(\"[^\"]*\"|'[^']*')");

    public static List<Range> ranges(String text, Mode mode) {
        List<Range> out = new ArrayList<>();
        switch (mode) {
            case JSON -> json(text, out);
            case XML -> xml(text, out);
            case HEADERS -> {
                Matcher m = HEADER_NAME.matcher(text);
                while (m.find()) out.add(new Range(m.start(), m.end(), "hdr-k"));
            }
            case VARS, PLAIN -> { }
        }
        return out;
    }

    /** Переменные подсвечиваются поверх JSON и простого текста (в XML, как и раньше, нет). */
    public static List<Range> vars(String text) {
        List<Range> out = new ArrayList<>();
        Matcher m = VAR_RE.matcher(text);
        while (m.find()) out.add(new Range(m.start(), m.end(), "x-v"));
        return out;
    }

    /** Ручной разбор вместо регулярки: на длинных строках (base64, SVG) регулярка с повтором переполняет стек. */
    private static void json(String text, List<Range> out) {
        int n = text.length(), i = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (c == '"') {
                int j = i + 1;
                while (j < n) {
                    char d = text.charAt(j);
                    if (d == '\\') { j = Math.min(j + 2, n); continue; }
                    if (d == '"') { j++; break; }
                    if (d == '\n') break;
                    j++;
                }
                int k = j;
                while (k < n && Character.isWhitespace(text.charAt(k))) k++;
                out.add(new Range(i, j, k < n && text.charAt(k) == ':' ? "json-k" : "json-s"));
                i = j;
            } else if (c == '-' || (c >= '0' && c <= '9')) {
                int j = i + 1;
                while (j < n && "0123456789.eE+-".indexOf(text.charAt(j)) >= 0) j++;
                out.add(new Range(i, j, "json-n"));
                i = j;
            } else if (Character.isLetter(c)) {
                int j = i + 1;
                while (j < n && Character.isLetterOrDigit(text.charAt(j))) j++;
                String w = text.substring(i, j);
                if (w.equals("true") || w.equals("false")) out.add(new Range(i, j, "json-b"));
                else if (w.equals("null")) out.add(new Range(i, j, "json-null"));
                i = j;
            } else i++;
        }
    }

    private static void xml(String text, List<Range> out) {
        Matcher m = XML_RE.matcher(text);
        while (m.find()) {
            String tok = m.group();
            int base = m.start();
            if (tok.startsWith("<!--") || tok.startsWith("<![CDATA[") || tok.startsWith("<?")) {
                out.add(new Range(base, m.end(), "x-c"));
                continue;
            }
            if (tok.charAt(0) != '<') continue;
            Matcher t = TAG_RE.matcher(tok);
            if (!t.matches()) continue;
            out.add(new Range(base, base + 1 + t.group(1).length(), "x-p"));
            out.add(new Range(base + t.start(2), base + t.end(2), "x-t"));
            Matcher a = ATTR_RE.matcher(t.group(3));
            int off = base + t.start(3);
            while (a.find()) {
                out.add(new Range(off + a.start(1), off + a.end(1), "x-a"));
                out.add(new Range(off + a.start(3), off + a.end(3), "x-s"));
            }
            out.add(new Range(base + t.start(4), m.end(), "x-p"));
        }
    }

    /** Строит спаны для CodeArea; слои накладываются по порядку (классы объединяются). */
    @SafeVarargs
    public static StyleSpans<Collection<String>> spans(int length, List<Range>... layers) {
        StyleSpans<Collection<String>> result = null;
        for (List<Range> layer : layers) {
            if (layer == null || layer.isEmpty()) continue;
            StyleSpans<Collection<String>> s = build(length, layer);
            result = result == null ? s : result.overlay(s, (a, b) -> {
                if (b.isEmpty()) return a;
                if (a.isEmpty()) return b;
                List<String> u = new ArrayList<>(a);
                u.addAll(b);
                return u;
            });
        }
        return result == null ? build(length, List.of()) : result;
    }

    /** Слой, отсортированный по началу: нужен для быстрого выбора отрезков в окне. */
    public static List<Range> sorted(List<Range> layer) {
        List<Range> l = new ArrayList<>(layer);
        l.sort((x, y) -> Integer.compare(x.start, y.start));
        return l;
    }

    /** Стили для окна [from, to) текста: слои обязаны быть отсортированы (sorted). Длина результата — to - from. */
    @SafeVarargs
    public static StyleSpans<Collection<String>> window(int from, int to, List<Range>... layers) {
        List<List<Range>> clipped = new ArrayList<>();
        for (List<Range> layer : layers) {
            if (layer == null || layer.isEmpty()) continue;
            int lo = 0, hi = layer.size();
            while (lo < hi) { int mid = (lo + hi) >>> 1; if (layer.get(mid).start < from) lo = mid + 1; else hi = mid; }
            List<Range> part = new ArrayList<>();
            for (int i = Math.max(0, lo - 2); i < layer.size(); i++) {
                Range r = layer.get(i);
                if (r.start >= to) break;
                if (r.end <= from) continue;
                part.add(new Range(Math.max(r.start, from) - from, Math.min(r.end, to) - from, r.cls));
            }
            clipped.add(part);
        }
        @SuppressWarnings("unchecked")
        List<Range>[] arr = clipped.toArray(new List[0]);
        return spans(to - from, arr);
    }

    /** Отрезки одного слоя не должны пересекаться; они могут идти не по порядку только внутри одного токена. */
    private static StyleSpans<Collection<String>> build(int length, List<Range> ranges) {
        List<Range> sorted = new ArrayList<>(ranges);
        sorted.sort((x, y) -> Integer.compare(x.start, y.start));
        StyleSpansBuilder<Collection<String>> b = new StyleSpansBuilder<>();
        int pos = 0;
        boolean any = false;
        for (Range r : sorted) {
            int s = Math.max(r.start, pos), e = Math.min(r.end, length);
            if (e <= s) continue;
            if (s > pos) { b.add(Collections.emptyList(), s - pos); }
            b.add(Collections.singletonList(r.cls), e - s);
            pos = e;
            any = true;
        }
        if (length > pos || !any) b.add(Collections.emptyList(), Math.max(0, length - pos));
        return b.create();
    }
}
