package org.ipro.telemetry.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Маркер включения field-level аудита для класса сущности
 * (имя намеренно НЕ {@code @Audited} — оно принадлежало Hibernate Envers,
 * демонтированному в рамках этого этапа).
 * <p>
 * Опциональный механизм: аудит сущности включается, если её класс помечен
 * этой аннотацией ИЛИ имя сущности входит в whitelist
 * {@code ipro.telemetry.field-audit.entities} (основной механизм —
 * whitelist, см. docs/field-audit-plan.md).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface FieldAudit {
}
