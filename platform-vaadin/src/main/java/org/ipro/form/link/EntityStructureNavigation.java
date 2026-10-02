package org.ipro.form.link;

import java.util.Objects;
import java.util.Optional;

/**
 * SPI перехода к структуре типа (E3.2.2 §3.3/§10.1): форма или структура подсистемы спрашивает
 * доступность, получает ссылку типа и исполняет открытие, не зная MainLayout, Workspace, пути
 * к файлу или Spring-контекста приложения.
 *
 * <p><b>Роль — APP_SPI.</b> Контракт объявляет платформа, реализацию даёт приложение: правило
 * доступа ADMIN, каталог опубликованных ключей и открытие единственной Workspace-вкладки Explorer
 * живут в приложении. Если реализации нет, контракт не внедряется и диагностический пункт меню
 * не появляется — запасной непроверенный путь не открывается.</p>
 *
 * <p><b>Ссылка не выдумывается.</b> Публичный адрес собирается только
 * {@link EntityExplorerAddress#format}: тип без опубликованного ключа остаётся открываемым
 * программно внутри текущего Workspace, но ссылки не получает. Отсутствие места — это
 * {@link OpenResult.Unavailable} с явной причиной, а не {@link OpenResult.Opened} с выдуманным
 * маршрутом.</p>
 *
 * <p><b>Owned-тип.</b> Реализация нормализует owned-строку к подтверждённой секции владельца;
 * если владельца нельзя определить однозначно, возвращается явная недоступность, а случайный
 * root не выбирается (нормализация общая для перехода из формы и из структуры подсистем).</p>
 */
public interface EntityStructureNavigation {

    /**
     * Доступность перехода к структуре конкретного типа: правило ADMIN и наличие определимого
     * места. Проверяется при показе пункта и повторно при клике — роль между показом и кликом
     * может смениться.
     */
    Availability availability(Class<?> type);

    /** Опубликованная ссылка типа; пусто — ключа нет, и ссылка не выдумывается. */
    Optional<String> link(Class<?> type);

    /**
     * Открыть структуру типа: активировать ту же Workspace-вкладку Explorer и применить место.
     * {@code anchor} может быть {@code null} — тип открывается без названного места; форма якоря
     * проверяется грамматикой адреса до вызова.
     */
    OpenResult open(Class<?> type, String anchor);

    /** Исход проверки доступности: недоступность всегда названа причиной. */
    record Availability(boolean available, String reason) {

        public Availability {
            reason = reason == null ? "" : reason;
        }

        public static Availability allowed() {
            return new Availability(true, "");
        }

        public static Availability unavailable(String reason) {
            Objects.requireNonNull(reason, "reason must not be null");
            if (reason.isBlank()) {
                throw new IllegalArgumentException("unavailable reason must not be blank");
            }
            return new Availability(false, reason);
        }
    }

    /** Исход открытия: структура открыта либо отказ назван явно. */
    sealed interface OpenResult {

        /** Структура открыта; {@code address} — опубликованная ссылка, если открытие шло по ней. */
        record Opened(Class<?> type, Optional<String> address) implements OpenResult {

            public Opened {
                Objects.requireNonNull(type, "type must not be null");
                address = address == null ? Optional.empty() : address;
            }
        }

        /** Отказ: причина обязательна и не изображает несуществующее место. */
        record Unavailable(String reason) implements OpenResult {

            public Unavailable {
                Objects.requireNonNull(reason, "reason must not be null");
                if (reason.isBlank()) {
                    throw new IllegalArgumentException("unavailable reason must not be blank");
                }
            }
        }
    }
}
