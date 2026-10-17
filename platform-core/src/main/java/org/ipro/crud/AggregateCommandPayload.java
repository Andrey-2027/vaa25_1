package org.ipro.crud;

import org.ipro.events.AggregateSection;
import org.ipro.identity.IdentifiableEntity;
import org.ipro.lifecycle.EntitySaveContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Payload команды, объявленный ею для lifecycle-хуков своей сущности (ADR-0026).
 *
 * <p><b>Зачем.</b> У {@code EntityLifecycle} нет payload'а: {@link EntitySaveContext} несёт сущность
 * и {@code EventContext}, а позиции документа знает только команда и aggregate-путь, который отдаёт
 * их хуку уровня агрегата ({@code beforeAggregateSave}). Хуки самой сущности — как раз те, что
 * порождают строки регистра, — оставались без данных команды, и приложение компенсировало это
 * собственным ThreadLocal'ом с эвристиками по {@code id} и Hibernate-классу.</p>
 *
 * <p><b>Что здесь.</b> Один поддержанный способ: команда объявляет свой payload
 * ({@link #open(IdentifiableEntity, List)}) и закрывает объявление, когда её операция закончилась, а
 * хук читает данные через {@link #of(EntitySaveContext)}. Объявление ничего не даёт приложению,
 * кроме закрытия: ни прочитать чужой payload, ни подменить владельца, ни связать копию.
 * Свидетельство принадлежит потоку и операции (дисциплина {@link CallbackScope}), поэтому после
 * закрытия, в другой операции и на другом потоке чтение пусто.</p>
 *
 * <p><b>Тождество — платформенное.</b> Payload виден только объявленному владельцу и — на
 * aggregate-пути — той managed copy, которую произвела сама платформа
 * ({@link #bindManagedCopy(IdentifiableEntity, IdentifiableEntity)}): обновление возвращает
 * {@code merge}-копию, и хуку {@code onSave} достаётся она. Сравнение делает платформа по тождеству
 * экземпляров, которые она же и создала, поэтому приложению не нужны ни {@code id}, ни
 * Hibernate-класс.</p>
 *
 * <p><b>Payload — данные, а не право.</b> Он не даёт права писать строки регистра: это по-прежнему
 * делает только {@code RegisterRecords.forRecorder} со своим токеном, активной транзакцией и
 * истекающим handle'ом. Хуку строки регистра payload не виден — тождество сверяется с владельцем
 * объявления, а не с операцией целиком.</p>
 */
public final class AggregateCommandPayload {

    private static final ThreadLocal<OpenDeclaration> CURRENT = new ThreadLocal<>();

    /**
     * Объявление команды: handle, у которого приложение не может ничего, кроме закрытия. Ни
     * прочитать payload, ни подменить владельца, ни связать копию через него нельзя, а
     * непроверяемого исключения на закрытии нет — объявление стоит вокруг обычного вызова
     * сохранения.
     */
    public interface Declaration extends AutoCloseable {
        @Override
        void close();
    }

    private final List<AggregateSection> sections;

    private AggregateCommandPayload(List<? extends AggregateSection> sections) {
        this.sections = List.copyOf(sections);
    }

    /**
     * Объявленные секции с их строками. Список и строки неизменяемы: читатель не может подменить
     * payload команды.
     */
    public List<AggregateSection> sections() {
        return sections;
    }

    /** Объявлена ли секция этого типа строк. */
    public boolean hasSection(Class<?> rowType) {
        Objects.requireNonNull(rowType, "rowType");
        return sections.stream().anyMatch(section -> section.rowType().equals(rowType));
    }

    /**
     * Строки объявленной секции; для необъявленной секции — пустой список. Строка чужого типа —
     * ошибка объявления, а не пустой результат: молча сузить набор данных нельзя.
     */
    public <R> List<R> section(Class<R> rowType) {
        Objects.requireNonNull(rowType, "rowType");
        for (AggregateSection section : sections) {
            if (!section.rowType().equals(rowType)) {
                continue;
            }
            List<R> rows = new ArrayList<>(section.rows().size());
            for (Object row : section.rows()) {
                rows.add(rowType.cast(row));
            }
            return List.copyOf(rows);
        }
        return List.of();
    }

    /**
     * Payload операции, к которой принадлежит этот callback, — пусто, если объявления нет, если оно
     * закрыто или если callback принадлежит не владельцу объявления.
     *
     * <p>Просматривается вся стопка объявлений, а не только верхнее: вложенная операция не отменяет
     * внешнюю, и её собственные хуки продолжают видеть свой payload.</p>
     */
    public static Optional<AggregateCommandPayload> of(EntitySaveContext<?> context) {
        Objects.requireNonNull(context, "context");
        IdentifiableEntity<?> entity = context.entity();
        for (OpenDeclaration declaration = CURRENT.get(); declaration != null;
                declaration = declaration.previousScope()) {
            if (declaration.describes(entity)) {
                return Optional.of(declaration.payload());
            }
        }
        return Optional.empty();
    }

    /**
     * Объявление команды: пока оно открыто, её payload виден хукам объявленной сущности. Возвращается
     * handle, у которого нет ничего, кроме {@code close()}.
     */
    public static Declaration open(IdentifiableEntity<?> owner, List<? extends AggregateSection> sections) {
        return new OpenDeclaration(Objects.requireNonNull(owner, "owner"),
            new AggregateCommandPayload(sections));
    }

    /**
     * Платформенный шаг, который заменяет прикладную эвристику: {@code merge} на обновлении возвращает
     * управляемую копию владельца, и именно её видит {@code onSave}. Связывает копию только с тем
     * объявлением, чей владелец — тот самый экземпляр, который платформа сохраняла.
     */
    static void bindManagedCopy(IdentifiableEntity<?> commandInstance, IdentifiableEntity<?> managedCopy) {
        OpenDeclaration declaration = CURRENT.get();
        if (declaration != null && declaration.owner() == commandInstance) {
            declaration.bind(managedCopy);
        }
    }

    /** Объявление на время операции: владелец, payload и произведённые платформой копии владельца. */
    static final class OpenDeclaration extends CallbackScope<OpenDeclaration> implements Declaration {

        private final IdentifiableEntity<?> owner;
        private final AggregateCommandPayload payload;
        private final List<IdentifiableEntity<?>> managedCopies = new ArrayList<>(2);

        private OpenDeclaration(IdentifiableEntity<?> owner, AggregateCommandPayload payload) {
            super(CURRENT, "Aggregate command payload");
            this.owner = owner;
            this.payload = payload;
            enter(this);
        }

        IdentifiableEntity<?> owner() {
            return owner;
        }

        AggregateCommandPayload payload() {
            return payload;
        }

        boolean describes(IdentifiableEntity<?> entity) {
            if (entity == owner) {
                return true;
            }
            for (IdentifiableEntity<?> copy : managedCopies) {
                if (copy == entity) {
                    return true;
                }
            }
            return false;
        }

        void bind(IdentifiableEntity<?> managedCopy) {
            if (!describes(managedCopy)) {
                managedCopies.add(managedCopy);
            }
        }
    }
}
