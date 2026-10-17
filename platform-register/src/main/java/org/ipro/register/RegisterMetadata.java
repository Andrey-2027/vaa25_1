package org.ipro.register;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Декларация информационного регистра (ADR-0013, v1.0).
 *
 * <p>Все имена — это имена Java-полей сущности. Для самостоятельного регистра row key состоит
 * из dimensions и period (если он задан); для подчинённого регистра row key задают recorder и
 * line number. Технический {@code id} из {@code BaseEntity} сохраняется.</p>
 *
 * <p>Для регистра, подчинённого документам, {@link #recorderTypes()} перечисляет допустимые
 * типы регистраторов. Их ссылка и номер строки образуют отдельный row key; dimensions остаются
 * группой среза.</p>
 *
 * <p>Корректность декларации проверяет {@link RegisterMetadataValidator}.</p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface RegisterMetadata {

    /**
     * Имя регистра для человека (ADR-0024 §2.4). Из него платформа собирает подписи его
     * именованных операций, поэтому пустая строка — «не объявлено», и регистр, включивший
     * {@link #keyChangeAllowed()} или {@link #keyWriteAllowed()}, обязан имя объявить:
     * иначе {@link RegisterMetadataValidator} отвергает декларацию, а не показывает сырой
     * ключ в админ-экране. Регистру без именованных операций имя не требуется.
     */
    String displayName() default "";

    /** Dimensions: поля группы среза, не nullable и неизменяемые после создания записи. */
    String[] dimensions();

    /** Период ({@code LocalDateTime}) или пустая строка для непериодического регистра. */
    String period() default "";

    /** Calendar normalization at write time; EXACT preserves time to milliseconds. */
    RegisterPeriodicity periodicity() default RegisterPeriodicity.EXACT;

    /** Ресурсы: изменяемые значения записи. */
    String[] resources();

    /** Допустимые типы документов-регистраторов; непустой список требует RecordedRegisterEntry. */
    Class<?>[] recorderTypes() default {};

    /** Opt-in to a separate checked key-change command; ordinary entity writes remain restricted. */
    boolean keyChangeAllowed() default false;

    /**
     * Opt-in к записи по ключу (set/delete, ADR-0023): запись ресурсов для полного ключа и
     * удаление по ключу отдельными именованными операциями. Ключ при этом не меняется —
     * перенос строки остаётся командой {@link #keyChangeAllowed()}. Обычные пути записи
     * (managed flush, generic UPDATE) этим флагом не открываются.
     */
    boolean keyWriteAllowed() default false;
}
