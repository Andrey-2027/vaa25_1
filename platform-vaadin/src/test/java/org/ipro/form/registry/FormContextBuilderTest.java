package org.ipro.form.registry;

import org.ipro.crud.BaseService;
import org.ipro.crud.EntityLookup;
import org.ipro.form.coordinator.FormNavigator;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Тесты типизированного FormContext (Этап 3, D3.5.1): инфраструктура — полями,
 * бизнес-параметры — картой. D3.5.1: concrete LookupService заменён интерфейсом
 * EntityLookup, ApplicationContext удалён, строковый ключ "coordinator" заменён
 * типизированным FormNavigator.
 */
class FormContextBuilderTest {

    @Test
    void typedFieldsRoundTrip() {
        BaseService<?, ?> service = mock(BaseService.class);
        EntityLookup entityLookup = mock(EntityLookup.class);
        FormNavigator formNavigator = mock(FormNavigator.class);

        FormContext ctx = FormContext.builder(String.class)
            .id(5L)
            .parameters(Map.of("workshop", "A"))
            .service(service)
            .entityLookup(entityLookup)
            .formNavigator(formNavigator)
            .build();

        assertThat(ctx.getEntityClass()).isEqualTo(String.class);
        assertThat(ctx.getId()).isEqualTo(5L);
        assertThat(ctx.service()).isSameAs(service);
        assertThat(ctx.entityLookup()).isSameAs(entityLookup);
        assertThat(ctx.formNavigator()).isSameAs(formNavigator);
        assertThat(ctx.<String>getParameter("workshop")).isEqualTo("A");
        assertThat(ctx.hasParameter("service")).isFalse();
        assertThat(ctx.hasParameter("coordinator")).isFalse();
    }

    @Test
    void legacyCtorLeavesNewFieldsNull() {
        FormContext ctx = new FormContext(String.class, null, null, null, null, Map.of());

        assertThat(ctx.service()).isNull();
        assertThat(ctx.entityLookup()).isNull();
        assertThat(ctx.formNavigator()).isNull();
        assertThat(ctx.getParameters()).isEmpty();
    }
}
