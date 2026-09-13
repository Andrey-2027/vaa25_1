package org.ipro.lifecycle;

import org.ipro.crud.IdentifiableEntity;
import org.ipro.events.EventContext;
import org.ipro.events.EventSource;

import java.util.Objects;
import java.util.function.Function;

/** Контекст изменения существующей entity с исходным состоянием до persistence. */
public record EntityUpdateContext<T extends IdentifiableEntity>(
        T original,
        T updated,
        EventContext eventContext) {

    public EntityUpdateContext {
        Objects.requireNonNull(original, "original must not be null");
        Objects.requireNonNull(updated, "updated must not be null");
        Objects.requireNonNull(eventContext, "eventContext must not be null");
    }

    public EventSource source() {
        return eventContext.source();
    }

    public Object entityId() {
        return eventContext.aggregateId();
    }

    public String operationName() {
        return eventContext.operationName();
    }

    /**
     * Отличает загруженный detached-снимок от уже managed instance, переданного
     * вызывающим кодом. Для managed instance callback всё равно доставляется, но
     * надёжное сравнение старых значений недоступно.
     */
    public boolean hasDistinctOriginal() {
        return original != updated;
    }

    /**
     * Сравнить значение поля в исходном и новом состоянии.
     *
     * <p>Достоверен только при {@link #hasDistinctOriginal()}: без отдельного
     * исходного снимка (managed instance в обеих ролях) вернёт {@code false} и для
     * «не менялось», и для «определить нельзя». Правила обязаны сначала проверять
     * {@code hasDistinctOriginal()} либо сразу использовать
     * {@link #changedOrUnknown(Function)}.</p>
     */
    public boolean changed(Function<? super T, ?> field) {
        Objects.requireNonNull(field, "field must not be null");
        return !Objects.equals(field.apply(original), field.apply(updated));
    }

    /**
     * Безопасное по умолчанию сравнение поля: точный diff при отдельном исходном
     * снимке, иначе {@code true} («не знаю» трактуется как «изменилось»).
     *
     * <p>Veto-правило («после проводки код менять нельзя») обязано блокировать
     * неизвестность — ложный отказ терпим, пропущенный инвариант нет. Derive-правило
     * («если код изменился — пересчитать») обязано пересчитывать, если пересчёт
     * идемпотентен. Прямой {@link #changed(Function)} без обёртки корректен только
     * в detached-канале и тихо пропускает правило в managed-канале.</p>
     */
    public boolean changedOrUnknown(Function<? super T, ?> field) {
        Objects.requireNonNull(field, "field must not be null");
        return !hasDistinctOriginal() || changed(field);
    }
}
