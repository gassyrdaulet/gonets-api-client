package kz.gonets;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Форматирование тел: JSON и XML с отступами в два пробела. */
public final class Pretty {
    private Pretty() {}

    public enum Kind { JSON, XML, TEXT }

    public record Body(String text, Kind kind) {}

    /** Определяет формат тела и форматирует его. */
    public static Body body(String text) {
        String j = Json.pretty(text);
        if (j != null) return new Body(j, Kind.JSON);
        if (LEADING_LT.matcher(text).lookingAt()) {
            String x = xml(text);
            if (x != null) return new Body(x, Kind.XML);
        }
        return new Body(text, Kind.TEXT);
    }

    private static final Pattern LEADING_LT = Pattern.compile("\\s*<");
    private static final Pattern DECL = Pattern.compile("^\\s*<\\?xml[^>]*\\?>");

    // Узлы разобранного документа: порядок атрибутов и объявлений пространств имён сохраняется как в исходнике
    private static final class El {
        final String name;
        final List<String[]> attrs = new ArrayList<>();
        final List<Object> kids = new ArrayList<>();
        El(String name) { this.name = name; }
    }
    private record Txt(StringBuilder text) {}
    private record CData(String text) {}
    private record Comment(String text) {}

    private static XMLInputFactory factory() {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, false);
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        f.setProperty(XMLInputFactory.IS_COALESCING, false);
        f.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, true);
        try { f.setProperty("http://java.sun.com/xml/stream/properties/report-cdata-event", true); } catch (IllegalArgumentException ignored) { /* другой парсер */ }
        return f;
    }

    private static String qname(String prefix, String local) {
        return prefix == null || prefix.isEmpty() ? local : prefix + ":" + local;
    }

    /** Форматирует XML с отступами; null, если это не корректный XML. */
    public static String xml(String text) {
        El root = null;
        try {
            XMLStreamReader r = factory().createXMLStreamReader(new StringReader(text));
            List<El> stack = new ArrayList<>();
            while (r.hasNext()) {
                int ev = r.next();
                switch (ev) {
                    case XMLStreamConstants.START_ELEMENT -> {
                        El el = new El(qname(r.getPrefix(), r.getLocalName()));
                        for (int i = 0; i < r.getNamespaceCount(); i++) {
                            String p = r.getNamespacePrefix(i);
                            el.attrs.add(new String[]{p == null || p.isEmpty() ? "xmlns" : "xmlns:" + p, r.getNamespaceURI(i)});
                        }
                        for (int i = 0; i < r.getAttributeCount(); i++) {
                            String n = qname(r.getAttributePrefix(i), r.getAttributeLocalName(i));
                            if (el.attrs.stream().noneMatch(a -> a[0].equals(n))) el.attrs.add(new String[]{n, r.getAttributeValue(i)});
                        }
                        if (stack.isEmpty()) {
                            if (root != null) return null;
                            root = el;
                        } else stack.get(stack.size() - 1).kids.add(el);
                        stack.add(el);
                    }
                    case XMLStreamConstants.END_ELEMENT -> stack.remove(stack.size() - 1);
                    case XMLStreamConstants.CHARACTERS, XMLStreamConstants.SPACE -> {
                        if (stack.isEmpty()) break;
                        List<Object> kids = stack.get(stack.size() - 1).kids;
                        if (!kids.isEmpty() && kids.get(kids.size() - 1) instanceof Txt t) t.text.append(r.getText());
                        else kids.add(new Txt(new StringBuilder(r.getText())));
                    }
                    case XMLStreamConstants.CDATA -> {
                        if (!stack.isEmpty()) stack.get(stack.size() - 1).kids.add(new CData(r.getText()));
                    }
                    case XMLStreamConstants.COMMENT -> {
                        if (!stack.isEmpty()) stack.get(stack.size() - 1).kids.add(new Comment(r.getText()));
                    }
                    default -> { /* инструкции обработки и DTD не выводим, как и прежняя страница */ }
                }
            }
            r.close();
        } catch (Exception e) {
            return null;
        }
        if (root == null) return null;
        Matcher dm = DECL.matcher(text);
        String decl = dm.find() ? dm.group().trim() : "";
        List<String> lines = new ArrayList<>();
        walk(root, 0, lines);
        return (decl.isEmpty() ? "" : decl + "\n") + String.join("\n", lines);
    }

    private static boolean blankText(Object o) {
        return o instanceof Txt t && t.text.toString().isBlank();
    }

    private static void walk(Object n, int depth, List<String> lines) {
        String pad = "  ".repeat(depth);
        if (n instanceof El el) {
            List<Object> kids = new ArrayList<>();
            for (Object k : el.kids) if (!blankText(k)) kids.add(k);
            StringBuilder open = new StringBuilder("<").append(el.name);
            for (String[] a : el.attrs) open.append(' ').append(a[0]).append("=\"").append(escAttr(a[1])).append('"');
            if (kids.isEmpty()) { lines.add(pad + open + "/>"); return; }
            if (kids.stream().allMatch(k -> k instanceof Txt || k instanceof CData)) {
                StringBuilder t = new StringBuilder();
                for (Object k : kids) {
                    if (k instanceof CData c) t.append("<![CDATA[").append(c.text).append("]]>");
                    else t.append(escText(((Txt) k).text.toString().strip()));
                }
                lines.add(pad + open + ">" + t + "</" + el.name + ">");
                return;
            }
            lines.add(pad + open + ">");
            for (Object k : kids) walk(k, depth + 1, lines);
            lines.add(pad + "</" + el.name + ">");
        } else if (n instanceof Txt t) {
            lines.add(pad + escText(t.text.toString().strip()));
        } else if (n instanceof CData c) {
            lines.add(pad + "<![CDATA[" + c.text + "]]>");
        } else if (n instanceof Comment c) {
            lines.add(pad + "<!--" + c.text + "-->");
        }
    }

    private static String escText(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String escAttr(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;");
    }

    public static String size(long n) {
        if (n < 1024) return n + " Б";
        if (n < 1048576) return String.format(java.util.Locale.US, "%.1f КБ", n / 1024.0);
        return String.format(java.util.Locale.US, "%.1f МБ", n / 1048576.0);
    }
}
