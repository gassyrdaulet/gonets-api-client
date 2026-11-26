package kz.gonets;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Генерируемые значения: {{$uuid}}, {{$now}}, {{$date}}, {{$timestamp}} и другие (полный список — {@link #HELP}).
 * <p>
 * Прямо в запросе каждое вхождение даёт новое значение. Переменная окружения, в значении которой стоит генератор
 * (например, message_id = {{$uuid}}), вычисляется один раз на запрос: во всех местах, где она встречается,
 * будет одно и то же значение. Один объект Gen — один запрос.
 */
public final class Gen {
    /** Не генерировать, оставлять как есть: для ключа кэша токенов, чтобы новые значения не сбрасывали кэш. */
    final boolean keep;
    /** Значения переменных окружения в этом запросе. */
    final Map<String, String> envValues = new HashMap<>();

    public Gen() { this(false); }
    Gen(boolean keep) { this.keep = keep; }

    /** Генераторы с описанием для подсказки в окне окружений (порядок важен). */
    public static final Map<String, String> HELP = new LinkedHashMap<>();
    static {
        HELP.put("{{$uuid}}", "случайный UUID: 3f6c2a1e-8d4b-4c7a-9e21-5b0d7f4a6c13");
        HELP.put("{{$now}}", "местное время с поясом: 2026-11-20T17:00:00.000+05:00");
        HELP.put("{{$utc}}", "время UTC: 2026-11-20T12:00:00.000Z");
        HELP.put("{{$date}}", "дата: 2026-11-20");
        HELP.put("{{$time}}", "время: 17:00:00");
        HELP.put("{{$timestamp}}", "Unix-время в секундах: 1795179600");
        HELP.put("{{$timestampMs}}", "Unix-время в миллисекундах: 1795179600000");
        HELP.put("{{$randomInt}}", "случайное число от 0 до 1000; {{$randomInt:1:9}} — в своих границах");
        HELP.put("{{$now:dd.MM.yyyy HH:mm}}", "дата и время в своём формате (так же $utc, $date, $time)");
    }

    private static final DateTimeFormatter ISO_LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
    private static final DateTimeFormatter ISO_UTC = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

    /**
     * Значение генератора по имени (без $) и необязательному аргументу после двоеточия.
     * null, если такого генератора нет или формат даты неверный: тогда текст остаётся как есть.
     */
    public static String generate(String name, String arg) {
        String a = arg == null ? null : arg.trim();
        boolean hasArg = a != null && !a.isEmpty();
        try {
            switch (name.toLowerCase(Locale.ROOT)) {
                case "uuid", "guid": return UUID.randomUUID().toString();
                case "now": return fmt(ZonedDateTime.now(), hasArg ? a : null, ISO_LOCAL);
                case "utc", "isotimestamp": return fmt(ZonedDateTime.now(ZoneOffset.UTC), hasArg ? a : null, ISO_UTC);
                case "date": return fmt(ZonedDateTime.now(), hasArg ? a : "yyyy-MM-dd", null);
                case "time": return fmt(ZonedDateTime.now(), hasArg ? a : "HH:mm:ss", null);
                case "timestamp": return String.valueOf(Instant.now().getEpochSecond());
                case "timestampms": return String.valueOf(System.currentTimeMillis());
                case "randomint": {
                    long min = 0, max = 1000;
                    if (hasArg) {
                        String[] p = a.split(":");
                        if (p.length == 1) max = Long.parseLong(p[0].trim());
                        else { min = Long.parseLong(p[0].trim()); max = Long.parseLong(p[1].trim()); }
                    }
                    if (max < min) { long t = min; min = max; max = t; }
                    return String.valueOf(ThreadLocalRandom.current().nextLong(min, max + 1));
                }
                default: return null;
            }
        } catch (RuntimeException e) {
            return null; // неверный формат или границы: оставляем текст, чтобы ошибку было видно в запросе
        }
    }

    private static String fmt(ZonedDateTime t, String pattern, DateTimeFormatter def) {
        DateTimeFormatter f = pattern == null ? def : DateTimeFormatter.ofPattern(pattern, Locale.forLanguageTag("ru"));
        return f.format(t.withZoneSameInstant(t.getZone() == null ? ZoneId.systemDefault() : t.getZone()));
    }
}
