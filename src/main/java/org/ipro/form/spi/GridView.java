package org.ipro.form.spi;

/**
 * Data сохранённого вида грида — всё, что нужно формам (без JPA-сущности приложения).
 */
public record GridView(
    Long id,
    String formKey,
    String name,
    String columns,
    boolean shared,
    String createdBy
) {
}
