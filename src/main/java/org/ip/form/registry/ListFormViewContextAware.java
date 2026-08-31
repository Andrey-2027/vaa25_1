package org.ip.form.registry;

/**
 * Контракт для variant-View списка, которому нужны параметры открытия.
 *
 * <p>Экземпляр View создаётся Spring-ом, после чего координатор вызывает
 * {@link #init(FormContext)} до того, как View будет показан пользователю.</p>
 */
@FunctionalInterface
public interface ListFormViewContextAware {

    void init(FormContext context);
}
