package org.ipro.form.builtin;

import com.vaadin.flow.component.UI;
import org.ipro.crud.LookupService;
import org.ipro.filtergrid.jpa.JpaFilterGrid;
import org.ipro.form.FieldFactory;
import org.ipro.form.SelectionFormAssembler;
import org.ipro.form.builder.layout.ItemFormLayout;
import org.ipro.form.builder.layout.Layout;
import org.ipro.form.registry.EmbeddedListFactory;
import org.ipro.identity.IdentifiableEntity;
import org.ipro.metadata.GridViewState;
import org.ipro.metadata.ColumnPath;
import org.ipro.metadata.MetadataResolver;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.ipro.form.CatalogOwnerFormsTest.*;

class EmbeddedCatalogOwnerTest {
    static class Card extends ItemForm<Party> {
        @Override protected ItemFormLayout layout(Layout l) { return l.of(l.remainingFields(),l.list(Contract.class)); }
    }

    @Test void derivedOwnerSurvivesResetAndViewAndIsLockedInTheNewChild() {
        var metadata=new MetadataResolver();
        var fields=new FieldFactory(mock(LookupService.class),mock(SelectionFormAssembler.class));
        var captured=new AtomicReference<ListForm<Contract,Long>>();
        var form=new Card();
        form.setEmbeddedListFactory(new EmbeddedListFactory() {
            @Override public boolean canRead(Class<?> type) { return true; }
            @Override public ListForm<?,?> create(Class<? extends IdentifiableEntity<Long>> type,String selectedVariant,Map<String,Object> filters) {
                var list=new ListForm<Contract,Long>(metadata.resolve(Contract.class),
                    new JpaFilterGrid<>(Contract.class,(spec,page)->Page.empty(page)));
                list.setOpeningContextFilters(filters); captured.set(list); return list;
            }
        });
        form.buildDescription(metadata.resolve(Party.class),fields);
        var owner=party(1); form.setEntity(owner);
        var previous=UI.getCurrent(); var ui=new UI(); UI.setCurrent(ui);
        var session=mock(com.vaadin.flow.server.VaadinSession.class,RETURNS_DEEP_STUBS);
        when(session.getErrorHandler()).thenReturn(null); ui.getInternals().setSession(session);
        try {
            ui.add(form); var list=captured.get();
            assertThat(list.getOpeningContextFilters()).containsEntry("counterparty",owner);
            list.setContextFilter("counterparty",party(2)); list.clearContextFilter();
            list.applyViewState(new GridViewState(List.of(new ColumnPath.Spec("name",null)),List.of()));
            assertThat(list.getContextSnapshot().effectiveFilters()).containsEntry("counterparty",owner);
            var values=new LinkedHashMap<>(list.getRequiredContextValues()); values.put("code","C1"); values.put("name","Contract");
            var child=new ItemForm<Contract>(metadata.resolve(Contract.class),fields); child.initializeNewEntity(values);
            assertThat(child.<Party>entityField("counterparty").getValue()).isSameAs(owner);
            child.setReadOnly(true); child.setReadOnly(false);
            assertThat(child.<Party>entityField("counterparty").isReadOnly()).isTrue();
            assertThatThrownBy(()->child.initializeNewEntity(Map.of("counterparty",party(2))))
                .hasMessageContaining("cannot be replaced");
            assertThat(form.isDirty()).isFalse();
        } finally { UI.setCurrent(previous); }
    }
}
