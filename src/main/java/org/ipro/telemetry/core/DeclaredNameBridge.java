package org.ipro.telemetry.core;

import org.ipro.telemetry.api.DeclaredNameSource;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Мост между снимками аудита (статические утилиты, работающие вне Spring) и источником
 * объявленных имён {@link DeclaredNameSource}.
 *
 * <p>По образцу {@code InstanceNameBridge}: источник ставится и снимается по жизненному
 * циклу контекста, а не в {@code @Bean}-методе, и регистраций может быть несколько.
 * Однa ячейка состояния здесь — ошибка, а не упрощение: тесты живут в одной JVM и делят
 * статику, поэтому закрытие соседнего контекста стирало бы источник у ещё живого (так и
 * произошло на первом прогоне {@code InstanceNamePilotIT}). Снятие адресует ту самую
 * регистрацию, которую поставил этот же контекст.</p>
 *
 * <p>Отсутствие источника — не ошибка: телеметрия обязана работать в приложении без
 * fetch-плана, и тогда имя выводится рефлексивным способом вызывающего кода.</p>
 */
public final class DeclaredNameBridge {

    private static final List<DeclaredNameSource> SOURCES = new CopyOnWriteArrayList<>();

    private DeclaredNameBridge() {
    }

    public static void install(DeclaredNameSource declaredNameSource) {
        SOURCES.add(declaredNameSource);
    }

    /** Снимает именно эту регистрацию; регистрации других контекстов не трогает. */
    public static void uninstall(DeclaredNameSource declaredNameSource) {
        SOURCES.remove(declaredNameSource);
    }

    /**
     * @return объявленное имя или {@code null} — источников нет либо ни один не знает тип.
     *         Спрашиваются в порядке, обратном установке: последняя регистрация — самая свежая.
     */
    public static String declaredName(Object entity) {
        for (int index = SOURCES.size() - 1; index >= 0; index--) {
            String declared = SOURCES.get(index).declaredName(entity);
            if (declared != null) {
                return declared;
            }
        }
        return null;
    }

    /** Для диагностики и тестов. */
    public static int installedCount() {
        return SOURCES.size();
    }
}
