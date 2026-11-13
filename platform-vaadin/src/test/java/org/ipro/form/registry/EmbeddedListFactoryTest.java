package org.ipro.form.registry;

import org.ipro.crud.BaseEntity;
import org.ipro.crud.LookupService;
import org.ipro.form.FieldFactory;
import org.ipro.form.SelectionFormAssembler;
import org.ipro.form.TableSectionFactory;
import org.ipro.form.builtin.ItemForm;
import org.ipro.form.builtin.ListForm;
import org.ipro.form.builder.layout.ItemFormLayout;
import org.ipro.form.builder.layout.Layout;
import org.ipro.form.config.FormAutoConfiguration;
import org.ipro.form.coordinator.FormNavigator;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.annotation.EntityMetadata;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.ApplicationContext;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class EmbeddedListFactoryTest {
    @EntityMetadata public static class Doc extends BaseEntity { }
    static class Shell extends ItemForm<Doc> {
        EmbeddedListFactory factoryDuringLayout;
        @Override protected ItemFormLayout layout(Layout l) {
            factoryDuringLayout = embeddedListFactory(); return l.of();
        }
    }

    @Test void resolverSuppliesPortBeforeLayoutAndAlsoInTypedFactoryContext() {
        var registry = new FormRegistry(); var metadata = new MetadataResolver();
        var port = mock(EmbeddedListFactory.class); var contextPort = new AtomicReference<EmbeddedListFactory>();
        registry.registerItemForm(Doc.class, (String) null, ctx -> {
            contextPort.set(ctx.embeddedListFactory()); return new Shell();
        });
        var fields = new FieldFactory(mock(LookupService.class), mock(SelectionFormAssembler.class));
        var resolver = new FormResolver(registry, metadata, fields, mock(ApplicationContext.class),
            mock(TableSectionFactory.class), mock(SelectionFormAssembler.class), null);
        resolver.setEmbeddedListFactory(port);
        var form = (Shell) resolver.resolveItemForm(Doc.class, null, null, null);
        assertThat(form.factoryDuringLayout).isSameAs(port);
        assertThat(contextPort.get()).isSameAs(port);
        verifyNoInteractions(port);
    }

    @Test void factoryResolvesNavigatorOnlyWhenInvokedAndPassesImmutableOpeningContext() {
        var navigator = mock(FormNavigator.class); var beans = new StaticListableBeanFactory();
        beans.addBean("navigator", navigator);
        var gate = mock(org.ipro.rls.RlsReadGate.class);
        var user = mock(org.ipro.rls.RlsCurrentUser.class);
        var entityReads = mock(org.ipro.rls.c5.C5PermissionEvaluator.class);
        when(user.username()).thenReturn("reader");
        when(entityReads.isEntityReadPermitted("reader", Doc.class)).thenReturn(true);
        when(gate.canRead(Doc.class, "reader")).thenReturn(true);
        beans.addBean("readGate", gate); beans.addBean("currentUser", user); beans.addBean("entityReads", entityReads);
        var port = new FormAutoConfiguration().embeddedListFactory(beans.getBeanProvider(FormNavigator.class),
            beans.getBeanProvider(org.ipro.rls.RlsReadGate.class), beans.getBeanProvider(org.ipro.rls.RlsCurrentUser.class),
            beans.getBeanProvider(org.ipro.rls.c5.C5PermissionEvaluator.class));
        verifyNoInteractions(navigator);
        var filters = new java.util.LinkedHashMap<String, Object>(); filters.put("code", "A");
        port.create(Doc.class, "embedded", filters);
        verify(navigator).createListForm(Doc.class, "embedded", Map.of("contextFilters", Map.of("code", "A")));
        when(gate.canRead(Doc.class, "reader")).thenReturn(false);
        assertThat(port.canRead(Doc.class)).isFalse();
        assertThatThrownBy(() -> port.create(Doc.class, "embedded", Map.of()))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        when(gate.canRead(Doc.class, "reader")).thenReturn(true);
        when(entityReads.isEntityReadPermitted("reader", Doc.class)).thenReturn(false);
        assertThat(port.canRead(Doc.class)).isFalse();
        verifyNoMoreInteractions(navigator);
    }

    @Test void oldCreationOnlyAdaptersMustNotImplicitlyGrantReading() {
        EmbeddedListFactory creationOnly = (type, selectedVariant, filters) -> { throw new AssertionError(); };
        assertThatThrownBy(() -> creationOnly.canRead(Doc.class))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("read access is not installed");
    }

    @Test void manualLegacyContextsKeepConstructorsAndDoNotHideInfrastructureInBusinessParameters() {
        var legacy = new FormContext(Doc.class, null, null, null, null, Map.of("business", "value"));
        assertThat(legacy.embeddedListFactory()).isNull();
        var factory = mock(EmbeddedListFactory.class);
        var typed = FormContext.builder(Doc.class).embeddedListFactory(factory).parameter("business", "value").build();
        assertThat(typed.embeddedListFactory()).isSameAs(factory);
        assertThat(typed.getParameters()).containsExactlyEntriesOf(Map.of("business", "value"));
    }
}
