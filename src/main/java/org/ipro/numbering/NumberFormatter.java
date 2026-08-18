package org.ipro.numbering;

import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Тонкий форматирующий слой: превращает {последовательное значение, префикс, шаблон, дата}
 * в строку номера. Токены шаблона: {@code {prefix}}, {@code {yyyy}}, {@code {MM}}, {@code {dd}},
 * {@code {seq:0n}} (нулевое заполнение до n знаков, например {@code {seq:000000}} → 000001).
 *
 * <p>Формат не влияет на тождество последовательности — смена префикса/шаблона не создаёт
 * новую серию (в отличие от scope/периодичности, которые входят в ключ счётчика).</p>
 */
public final class NumberFormatter {

    private static final Pattern SEQ = Pattern.compile("\\{seq:(0+)\\}");

    private NumberFormatter() {
    }

    public static String format(long seq, String prefix, String pattern, LocalDate date) {
        String result = pattern
                .replace("{prefix}", prefix)
                .replace("{yyyy}", Integer.toString(date.getYear()))
                .replace("{MM}", String.format("%02d", date.getMonthValue()))
                .replace("{dd}", String.format("%02d", date.getDayOfMonth()));
        Matcher matcher = SEQ.matcher(result);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            int width = matcher.group(1).length();
            matcher.appendReplacement(sb, String.format("%0" + width + "d", seq));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
