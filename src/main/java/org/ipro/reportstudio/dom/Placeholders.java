package org.ipro.reportstudio.dom;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Разбор плейсхолдеров {@code {alias}} в шаблонах вычисляемых колонок
 * (Фаза 2). Имя алиаса — буквы, цифры, подчёркивание, точка. Шаблон с
 * незакрытой скобкой считается некорректным ({@link IllegalArgumentException}).
 */
public final class Placeholders {

    private static final Pattern ALIAS = Pattern.compile("\\{([A-Za-z0-9_.]+)}");

    private Placeholders() {
    }

    /** Алиасы из шаблона в порядке появления (без дублей). */
    public static Set<String> aliasesOf(String template) {
        if (template == null || template.isBlank()) {
            return Set.of();
        }
        Set<String> aliases = new LinkedHashSet<>();
        Matcher matcher = ALIAS.matcher(template);
        while (matcher.find()) {
            aliases.add(matcher.group(1));
        }
        if (template.indexOf('{') >= 0 && matcher.regionEnd() >= 0 && !balanced(template)) {
            throw new IllegalArgumentException("Шаблон: незакрытая скобка «{»");
        }
        return aliases;
    }

    private static boolean balanced(String template) {
        int open = template.indexOf('{');
        int close = template.lastIndexOf('}');
        return close >= open;
    }
}