package org.ipro.form;

import com.vaadin.flow.component.html.Span;
import org.ipro.form.builtin.ListForm;
import org.ipro.metadata.*;
import org.ipro.filtergrid.jpa.JpaFilterGrid;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.domain.Specification;
import jakarta.persistence.criteria.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.ipro.form.CatalogOwnerFormsTest.*;

class CatalogOwnerListSelectionTest {
    @Test void listKeepsOpeningOwnerFilterAndPropagatesItToNewItem() {
        var metadata=new MetadataResolver().resolve(Contract.class);
        var grid=new JpaFilterGrid<Contract>(Contract.class,(spec,pageable)->org.springframework.data.domain.Page.empty());
        var form=new ListForm<Contract,Long>(metadata,grid);var a=party(1);var b=party(2);
        form.setOpeningContextFilter("counterparty",a);form.setContextFilter("counterparty",b);form.clearContextFilter();
        assertThat(form.getContextSnapshot().effectiveFilters()).containsEntry("counterparty",a);
        assertThat(form.getRequiredContextValues()).containsEntry("counterparty",a);
        assertThat(form.getChildren().filter(c->c instanceof Span).map(c->((Span)c).getText()).toList()).anyMatch(t->t.startsWith("Владелец:"));
    }
    @Test void selectionAssemblerRetainsOwnerAcrossInteractiveFilterChanges() {
        var locator=mock(org.ipro.crud.ServiceLocator.class);
        var service=mock(org.ipro.crud.BaseService.class);when(locator.findService(Contract.class)).thenReturn(service);
        when(service.findAllByScenario(any(),any(),any(),any())).thenReturn(org.springframework.data.domain.Page.empty());
        var form=new SelectionFormAssembler(new MetadataResolver(),locator).assemble(Contract.class,c->{},Map.of("counterparty",party(1)));
        form.setContextFilter("name","X");form.clearContextFilter("name");
        assertThat(form.getChildren().filter(c->c instanceof Span).map(c->((Span)c).getText()).toList()).anyMatch(t->t.startsWith("Владелец:"));
        // The query boundary keeps the fixed owner even after the interactive panel is cleared.
        var root=mock(Root.class);var path=mock(Path.class);var cb=mock(CriteriaBuilder.class);var query=mock(CriteriaQuery.class);
        when(root.get("counterparty")).thenReturn(path);when(cb.equal(eq(path),any())).thenReturn(mock(Predicate.class));
        var fixed=(Specification<Contract>)org.springframework.test.util.ReflectionTestUtils.getField(form.getFilterGrid(),"additionalSpecification");
        fixed.toPredicate(root,query,cb);verify(cb).equal(eq(path),argThat((Object p)->p instanceof Party party&&party.getId().equals(1L)));
        form.setFixedFilters(Map.of());
        assertThat(org.springframework.test.util.ReflectionTestUtils.getField(form.getFilterGrid(),"additionalSpecification")).isNull();
        assertThat(form.getChildren().filter(c->c instanceof Span && c.isVisible()).map(c->((Span)c).getText()).toList()).noneMatch(t->t.startsWith("Владелец:"));
    }
}
