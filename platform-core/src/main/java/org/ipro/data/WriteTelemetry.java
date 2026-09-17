package org.ipro.data;

/**
 * Optional seam для telemetry policy записи (C4.8, ADR-0007 §5/§8).
 *
 * <p>Read-граница получила свой seam в C4.1 ({@link ReadTelemetry}), но canonical write
 * pipeline до C4.8 не имел telemetry-collaborator'а вообще — формальное закрытие C4.3
 * оставалось pending именно из-за этого. Симметрично чтению, write-telemetry — это
 * <b>typed collaborator</b> с noop по умолчанию, а не durable event на каждую запись.</p>
 *
 * <p>Исход операции <b>двухфазный</b>, потому что «метод вернулся» и «запись сохранена» —
 * не одно и то же:</p>
 * <ol>
 * <li>{@link WriteScope#pipelineCompleted(int)} — pipeline дошёл до конца и БД приняла SQL
 * (executor выполняет flush внутри операции);</li>
 * <li>{@link WriteScope#committed()} или {@link WriteScope#rolledBack(String)} — исход
 * транзакции, который Spring сообщает уже после возврата метода.</li>
 * </ol>
 *
 * <p>Без второй фазы откат на коммите выглядел бы как успех. Реализация, которой нужен
 * только факт завершения, может считать {@code pipelineCompleted} достаточным; реализация,
 * считающая «сохранено», обязана дождаться {@code committed}.</p>
 *
 * <p>Порядок вызовов при наблюдаемой транзакции: {@code pipelineCompleted} →
 * {@code close()} → {@code committed()} либо {@code rolledBack(...)}. {@code close()}
 * освобождает ресурсы операции, а не фиксирует итог: реализация, которой важен исход
 * транзакции, обязана накапливать состояние до второго вызова. Там, где транзакции нет
 * (hand-built executor без прокси), сообщается только pipeline-фаза.</p>
 *
 * <p>Контракт закрепляет требования C4.8:</p>
 * <ul>
 * <li>scope начинается <b>до проверки capability</b>, поэтому ранний deny фиксируется, а не
 * теряется до пользовательского кода;</li>
 * <li>деноу соответствует {@link DenialKind}, а не «любой {@code IllegalStateException}»:
 * ошибка нумерации, валидации или lifecycle — это {@link WriteScope#failed(Throwable)},
 * а не отказ policy;</li>
 * <li>в telemetry попадают только технические данные — операция, тип, вид отказа, outcome,
 * число строк и длительность. Entity values, payload, пароли, поисковые строки и
 * RLS-предикаты не передаются.</li>
 * </ul>
 *
 * <p>Вложенный canonical write в рамках одной бизнес-операции (например, aggregate save,
 * который в конце вызывает {@code save}) не открывает второй scope: границу удерживает
 * сам executor, чтобы одна бизнес-операция давала одну запись telemetry.</p>
 */
public interface WriteTelemetry {

    /** Начать scope операции до проверки capability. */
    WriteScope begin(DataOperation operation, Class<?> type);

    static WriteTelemetry noop() {
        return (operation, type) -> WriteScope.noop();
    }

    /**
     * Почему write-intent отклонён. Отказ policy отличается от ошибки исполнения: без такого
     * различия deny размывается до «что-то не получилось», а настоящий security-отказ
     * наоборот уезжает в ошибки.
     */
    enum DenialKind {

        /** Пара {@code (type, operation)} не разрешена descriptor'ом типа. */
        CAPABILITY,

        /** Тип с owned-секциями изменяется только через aggregate boundary владельца. */
        AGGREGATE_BOUNDARY,

        /** Отказ по доступу: RLS/security, в том числе недоступная строка при UPDATE. */
        ACCESS
    }

    /**
     * Scope одной write-операции. Реализация обязана быть безопасной при повторном вызове
     * терминального метода и при {@code close()} без него (тогда итог не считается успехом).
     */
    interface WriteScope extends AutoCloseable {

        /** Отказ policy до RLS, валидации, хуков и SQL (см. {@link DenialKind}). */
        void denied(DenialKind kind, String reason);

        /** Операция прервана ошибкой исполнения (валидация, нумерация, событие, SQL). */
        void failed(Throwable error);

        /**
         * Pipeline завершён и SQL принят БД; {@code affectedRows} — число затронутых строк.
         * Это ещё не коммит: исход транзакции сообщается отдельно.
         */
        void pipelineCompleted(int affectedRows);

        /** Транзакция зафиксирована: результат pipeline подтверждён. */
        void committed();

        /** Транзакция не зафиксирована после pipeline: успех отменён, действие не сохранено. */
        void rolledBack(String reason);

        @Override
        void close();

        static WriteScope noop() {
            return new WriteScope() {
                @Override
                public void denied(DenialKind kind, String reason) {
                }

                @Override
                public void failed(Throwable error) {
                }

                @Override
                public void pipelineCompleted(int affectedRows) {
                }

                @Override
                public void committed() {
                }

                @Override
                public void rolledBack(String reason) {
                }

                @Override
                public void close() {
                }
            };
        }
    }
}
