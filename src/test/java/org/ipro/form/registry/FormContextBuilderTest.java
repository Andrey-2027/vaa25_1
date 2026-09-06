package org.ipro.form.registry;

import org.ipro.crud.BaseService;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Тесты типизированного FormContext (Этап 3): инфраструктура — полями,
 * бизнес-параметры — картой; строковые ключи service/applicationContext убраны.
 */
class FormContextBuilderTest {

    @Test
    void typedFieldsRoundTrip() {
        BaseService<?, ?> service = mock(BaseService.class);
        ApplicationContext applicationContext = mock(ApplicationContext.class);

        FormContext ctx = FormContext.builder(String.class)
            .id(5L)
            .parameters(Map.of("workshop", "A"))
            .service(service)
            .applicationContext(applicationContext)
            .build();

        assertThat(ctx.getEntityClass()).isEqualTo(String.class);
        assertThat(ctx.getId()).isEqualTo(5L);
        assertThat(ctx.service()).isSameAs(service);
        assertThat(ctx.applicationContext()).isSameAs(applicationContext);
        assertThat(ctx.<String>getParameter("workshop")).isEqualTo("A");
        assertThat(ctx.hasParameter("service")).isFalse();
        assertThat(ctx.hasParameter("applicationContext")).isFalse();
    }

    @Test
    void legacyCtorLeavesNewFieldsNull() {
        FormContext ctx = new FormContext(String.class, null, null, null, null, Map.of());

        assertThat(ctx.service()).isNull();
        assertThat(ctx.applicationContext()).isNull();
        assertThat(ctx.getParameters()).isEmpty();
    }
}
