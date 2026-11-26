package kz.gonets.ui;

import javafx.scene.control.IndexRange;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.NavigationActions.SelectionPolicy;
import org.fxmisc.richtext.model.TwoDimensional.Bias;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Сочетания клавиш как в VS Code для поля с кодом. Каждая операция — одна правка, так что Ctrl+Z отменяет её целиком.
 *
 * <pre>
 * Ctrl+X / Ctrl+C без выделения   вырезать / скопировать всю строку (Ctrl+V такой строки вставит её над текущей)
 * Ctrl+Shift+K                    удалить строку
 * Alt+↑ / Alt+↓                   переместить строку (или выделенные строки) вверх / вниз
 * Shift+Alt+↑ / Shift+Alt+↓       продублировать строку вверх / вниз
 * Tab / Shift+Tab                 отступ / убрать отступ (для нескольких выделенных строк — у всех)
 * Ctrl+] / Ctrl+[                 отступ / убрать отступ у строки
 * Enter                           новая строка с тем же отступом; после { [ или открывающего тега — с отступом глубже
 * Home                            к первому символу строки, повторно — к началу строки
 * Ctrl+L                          выделить строку (повторно — следующую)
 * Ctrl+D                          выделить слово, повторно — следующее такое же
 * Ctrl+/                          закомментировать строки (XML и SOAP: &lt;!-- --&gt;)
 * Shift+Alt+F                     форматировать
 * </pre>
 */
final class EditorKeys {
    private static final String INDENT = "  ";
    private static final Pattern OPEN_TAG = Pattern.compile("<([A-Za-z_][\\w.:-]*)(?:\\s[^<>]*)?>\\s*$");
    private static final Pattern XML_COMMENT = Pattern.compile("^(\\s*)<!--\\s?(.*?)\\s?-->\\s*$");

    private final CodeArea area;
    private final boolean editable;
    private final BooleanSupplier xmlMode;
    private final Runnable onFormat;
    /** Строка, скопированная или вырезанная целиком: при вставке без выделения она встаёт над текущей строкой. */
    private String lineClip;

    private EditorKeys(CodeArea area, boolean editable, BooleanSupplier xmlMode, Runnable onFormat) {
        this.area = area;
        this.editable = editable;
        this.xmlMode = xmlMode;
        this.onFormat = onFormat;
    }

    /**
     * @param xmlMode  включён ли режим XML (для комментариев и отступа после тега)
     * @param onFormat действие по Shift+Alt+F (может быть null)
     */
    static void install(CodeArea area, boolean editable, BooleanSupplier xmlMode, Runnable onFormat) {
        EditorKeys k = new EditorKeys(area, editable, xmlMode, onFormat);
        area.addEventFilter(KeyEvent.KEY_PRESSED, e -> { if (k.handle(e)) e.consume(); });
    }

    // ---------- строки ----------
    private int lines() { return area.getParagraphs().size(); }
    private int par(int offset) { return area.offsetToPosition(offset, Bias.Forward).getMajor(); }
    private int ls(int p) { return area.getAbsolutePosition(p, 0); }
    private int le(int p) { return ls(p) + area.getParagraphLength(p); }
    private String line(int p) { return area.getText(p); }

    /** Строки, которых касается выделение (выделение до начала строки эту строку не захватывает). */
    private int[] selLines() {
        IndexRange s = area.getSelection();
        int a = par(s.getStart()), b = par(s.getEnd());
        if (s.getLength() > 0 && b > a && s.getEnd() == ls(b)) b--;
        return new int[]{a, b};
    }

    private String block(int a, int b) {
        StringBuilder sb = new StringBuilder();
        for (int p = a; p <= b; p++) {
            if (p > a) sb.append('\n');
            sb.append(line(p));
        }
        return sb.toString();
    }

    private static String leading(String s) {
        int i = 0;
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) i++;
        return s.substring(0, i);
    }

    /** Заменяет отрезок и ставит выделение (anchor, caret) уже в новом тексте. */
    private void replace(int start, int end, String text, int anchor, int caret) {
        // отдельный шаг отмены: иначе RichTextFX склеивает быстрые правки, и Ctrl+Z отменяет сразу несколько операций
        area.getUndoManager().preventMerge();
        area.replaceText(start, end, text);
        area.getUndoManager().preventMerge();
        int len = area.getLength();
        area.selectRange(Math.max(0, Math.min(anchor, len)), Math.max(0, Math.min(caret, len)));
        area.requestFollowCaret();
    }

    /** Буфер обмена Windows бывает занят другой программой: пробуем записать несколько раз. */
    private static void toClipboard(String text) {
        ClipboardContent c = new ClipboardContent();
        c.putString(text);
        for (int i = 0; i < 5; i++) {
            if (Clipboard.getSystemClipboard().setContent(c)) return;
            try { Thread.sleep(15); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    // ---------- обработка ----------
    private boolean handle(KeyEvent e) {
        KeyCode k = e.getCode();
        boolean ctrl = e.isShortcutDown(), shift = e.isShiftDown(), alt = e.isAltDown();
        boolean noSel = area.getSelection().getLength() == 0;

        // и для ответа (только чтение), и для тела запроса
        if (ctrl && !shift && !alt && k == KeyCode.C && noSel) { copyLine(); return true; }
        if (ctrl && !shift && !alt && k == KeyCode.L) { selectLine(); return true; }
        if (ctrl && !shift && !alt && k == KeyCode.D) { selectNext(); return true; }
        if (!ctrl && !alt && k == KeyCode.HOME) { smartHome(shift); return true; }
        if (!editable) return false;

        if (ctrl && !shift && !alt && k == KeyCode.X && noSel) { cutLine(); return true; }
        if (ctrl && !shift && !alt && k == KeyCode.V && noSel && pasteLine()) return true;
        if (ctrl && shift && !alt && k == KeyCode.K) { deleteLines(); return true; }
        if (alt && !ctrl && (k == KeyCode.UP || k == KeyCode.DOWN)) {
            int dir = k == KeyCode.UP ? -1 : 1;
            if (shift) copyLines(dir); else moveLines(dir);
            return true;
        }
        if (shift && alt && !ctrl && k == KeyCode.F) { if (onFormat != null) onFormat.run(); return true; }
        if (ctrl && !shift && !alt && k == KeyCode.SLASH) { toggleComment(); return true; }
        if (ctrl && !shift && !alt && k == KeyCode.CLOSE_BRACKET) { indent(true); return true; }
        if (ctrl && !shift && !alt && k == KeyCode.OPEN_BRACKET) { outdent(); return true; }
        if (k == KeyCode.TAB && !ctrl && !alt) {
            int[] l = selLines();
            if (shift) outdent();
            else if (l[0] != l[1]) indent(false);
            else area.replaceSelection(INDENT);
            return true;
        }
        if (k == KeyCode.ENTER && !ctrl && !alt && !shift) { newLine(); return true; }
        return false;
    }

    // ---------- операции ----------
    private void copyLine() {
        int p = area.getCurrentParagraph();
        lineClip = line(p) + "\n";
        toClipboard(lineClip);
    }

    private void cutLine() {
        int p = area.getCurrentParagraph();
        lineClip = line(p) + "\n";
        toClipboard(lineClip);
        removeLines(p, p);
    }

    /** Строка, скопированная целиком, вставляется над текущей строкой; курсор остаётся на месте. */
    private boolean pasteLine() {
        String clip = Clipboard.getSystemClipboard().getString();
        if (clip == null || lineClip == null || !clip.equals(lineClip)) return false;
        int caret = area.getCaretPosition(), start = ls(area.getCurrentParagraph());
        replace(start, start, clip, caret + clip.length(), caret + clip.length());
        return true;
    }

    private void deleteLines() {
        int[] l = selLines();
        removeLines(l[0], l[1]);
    }

    /** Убирает строки a..b вместе с переводом строки; курсор встаёт в начало следующей строки. */
    private void removeLines(int a, int b) {
        int start, end;
        if (b < lines() - 1) { start = ls(a); end = ls(b + 1); }
        else if (a > 0) { start = le(a - 1); end = le(b); }
        else { start = 0; end = le(b); }
        int col = Math.min(area.getCaretColumn(), Integer.MAX_VALUE);
        area.getUndoManager().preventMerge();
        area.replaceText(start, end, "");
        area.getUndoManager().preventMerge();
        int p = Math.min(a, lines() - 1);
        int pos = ls(p) + Math.min(col, area.getParagraphLength(p));
        area.selectRange(pos, pos);
        area.requestFollowCaret();
    }

    private void moveLines(int dir) {
        int[] l = selLines();
        int a = l[0], b = l[1];
        int anchor = area.getAnchor(), caret = area.getCaretPosition();
        if (dir < 0) {
            if (a == 0) return;
            String above = line(a - 1);
            int shift = -(above.length() + 1);
            replace(ls(a - 1), le(b), block(a, b) + "\n" + above, anchor + shift, caret + shift);
        } else {
            if (b >= lines() - 1) return;
            String below = line(b + 1);
            int shift = below.length() + 1;
            replace(ls(a), le(b + 1), below + "\n" + block(a, b), anchor + shift, caret + shift);
        }
    }

    private void copyLines(int dir) {
        int[] l = selLines();
        String blk = block(l[0], l[1]);
        int anchor = area.getAnchor(), caret = area.getCaretPosition();
        if (dir < 0) {
            int at = ls(l[0]);
            replace(at, at, blk + "\n", anchor, caret); // курсор остаётся на верхней (новой) копии
        } else {
            int at = le(l[1]);
            int shift = blk.length() + 1;
            replace(at, at, "\n" + blk, anchor + shift, caret + shift);
        }
    }

    private void indent(boolean evenSingle) {
        int[] l = selLines();
        if (!evenSingle && l[0] == l[1]) { area.replaceSelection(INDENT); return; }
        List<String> out = new ArrayList<>();
        for (int p = l[0]; p <= l[1]; p++) out.add(line(p).isEmpty() && l[0] != l[1] ? "" : INDENT + line(p));
        rewriteLines(l[0], l[1], out);
    }

    private void outdent() {
        int[] l = selLines();
        List<String> out = new ArrayList<>();
        for (int p = l[0]; p <= l[1]; p++) {
            String s = line(p);
            if (s.startsWith("\t")) s = s.substring(1);
            else if (s.startsWith(INDENT)) s = s.substring(INDENT.length());
            else if (s.startsWith(" ")) s = s.substring(1);
            out.add(s);
        }
        rewriteLines(l[0], l[1], out);
    }

    /** Заменяет строки a..b; одна строка — курсор сдвигается вместе с текстом, несколько — выделяются целиком. */
    private void rewriteLines(int a, int b, List<String> out) {
        String text = String.join("\n", out);
        int start = ls(a);
        if (a == b) {
            int delta = text.length() - line(a).length();
            int anchor = Math.max(start, area.getAnchor() + delta), caret = Math.max(start, area.getCaretPosition() + delta);
            replace(start, le(b), text, anchor, caret);
        } else {
            replace(start, le(b), text, start, start + text.length());
        }
    }

    /** Enter: тот же отступ; после { [ или открывающего тега — глубже, а между парой скобок/тегов — ещё и закрывающая строка. */
    private void newLine() {
        IndexRange s = area.getSelection();
        int p = par(s.getStart());
        String cur = line(p);
        int col = s.getStart() - ls(p);
        String before = cur.substring(0, Math.min(col, cur.length()));
        String after = s.getEnd() <= le(p) ? cur.substring(Math.min(s.getEnd() - ls(p), cur.length())) : "";
        String ws = leading(cur);
        String b = before.stripTrailing(), aft = after.stripLeading();
        boolean opens = b.endsWith("{") || b.endsWith("[") || (xmlMode.getAsBoolean() && openTag(b));
        boolean closes = aft.startsWith("}") || aft.startsWith("]") || (xmlMode.getAsBoolean() && aft.startsWith("</"));
        String ins;
        int caretOff;
        if (opens && closes) {
            ins = "\n" + ws + INDENT + "\n" + ws;
            caretOff = 1 + ws.length() + INDENT.length();
        } else if (opens) {
            ins = "\n" + ws + INDENT;
            caretOff = ins.length();
        } else {
            ins = "\n" + ws;
            caretOff = ins.length();
        }
        int pos = s.getStart() + caretOff;
        replace(s.getStart(), s.getEnd(), ins, pos, pos);
    }

    private static boolean openTag(String before) {
        Matcher m = OPEN_TAG.matcher(before);
        if (!m.find()) return false;
        String tag = m.group();
        return !tag.startsWith("</") && !tag.endsWith("/>") && !tag.startsWith("<?") && !tag.startsWith("<!");
    }

    private void smartHome(boolean extend) {
        int p = area.getCurrentParagraph();
        int first = leading(line(p)).length();
        int target = area.getCaretColumn() == first ? 0 : first;
        area.moveTo(ls(p) + target, extend ? SelectionPolicy.EXTEND : SelectionPolicy.CLEAR);
        area.requestFollowCaret();
    }

    /** Ctrl+L: выделить строку; если уже выделены целые строки — добавить следующую. */
    private void selectLine() {
        int[] l = selLines();
        int a = l[0], b = l[1];
        IndexRange s = area.getSelection();
        int fullEnd = b < lines() - 1 ? ls(b + 1) : le(b);
        if (s.getStart() == ls(a) && s.getEnd() == fullEnd && s.getLength() > 0 && b < lines() - 1) {
            b++;
            fullEnd = b < lines() - 1 ? ls(b + 1) : le(b);
        }
        area.selectRange(ls(a), fullEnd);
        area.requestFollowCaret();
    }

    /** Ctrl+D: без выделения — слово под курсором; с выделением — следующее такое же (по кругу). */
    private void selectNext() {
        IndexRange s = area.getSelection();
        String text = area.getText();
        if (s.getLength() == 0) {
            int c = area.getCaretPosition(), a = c, b = c;
            while (a > 0 && isWord(text.charAt(a - 1))) a--;
            while (b < text.length() && isWord(text.charAt(b))) b++;
            if (b > a) area.selectRange(a, b);
            return;
        }
        String needle = text.substring(s.getStart(), s.getEnd());
        int i = text.indexOf(needle, s.getEnd());
        if (i < 0) i = text.indexOf(needle);
        if (i >= 0) {
            area.selectRange(i, i + needle.length());
            area.requestFollowCaret();
        }
    }

    private static boolean isWord(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '$';
    }

    /** Ctrl+/: в XML и SOAP оборачивает строки в &lt;!-- --&gt; или снимает комментарий. В JSON комментариев нет. */
    private void toggleComment() {
        if (!xmlMode.getAsBoolean()) return;
        int[] l = selLines();
        boolean allCommented = true;
        for (int p = l[0]; p <= l[1]; p++) {
            String s = line(p);
            if (!s.isBlank() && !XML_COMMENT.matcher(s).matches()) { allCommented = false; break; }
        }
        List<String> out = new ArrayList<>();
        for (int p = l[0]; p <= l[1]; p++) {
            String s = line(p);
            if (s.isBlank()) { out.add(s); continue; }
            if (allCommented) {
                Matcher m = XML_COMMENT.matcher(s);
                out.add(m.matches() ? m.group(1) + m.group(2) : s);
            } else {
                String ws = leading(s);
                out.add(ws + "<!-- " + s.substring(ws.length()) + " -->");
            }
        }
        rewriteLines(l[0], l[1], out);
    }
}
