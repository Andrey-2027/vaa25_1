package org.ipro.vaadin.register;

import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import com.vaadin.flow.component.html.Anchor;
import org.ipro.crud.BaseEntity;
import org.ipro.data.EntityDataAccess;
import org.ipro.data.EntityDataAccessResolver;
import org.ipro.data.EntityReadAccess;
import org.ipro.fetch.instance.InstanceName;
import org.ipro.fetch.instance.InstanceNameResolver;
import org.ipro.form.link.FormLinkResult;
import org.ipro.form.link.FormLinkService;
import org.ipro.form.link.FormRoute;
import org.ipro.register.RecorderRef;
import org.ipro.register.RecorderTypeRegistry;
import org.ipro.metadata.MetadataResolver;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecorderRefPresentationResolverTest {

    @Test void formDelegatesLabelsToCommonServiceAndKeepsDocumentLinks() {
        var reads = mock(EntityReadAccess.class); var types = mock(RecorderTypeRegistry.class); var links = mock(FormLinkService.class);
        when(types.findType("receipt")).thenReturn(Optional.of(ReceiptDocument.class));
        when(links.linkToRecord(ReceiptDocument.class, 11L)).thenReturn(link(11L));
        var service = mock(org.ipro.data.presentation.EntityPresentationService.class);
        var typed = new org.ipro.data.presentation.EntityPresentationService.Reference(ReceiptDocument.class, 11L);
        when(service.resolve(any())).thenReturn(java.util.Map.of(typed, "Общее представление"));
        when(service.unavailable(ReceiptDocument.class)).thenReturn("Документ недоступен");
        var resolver = resolver(reads, types, links); resolver.useCommonPresentations(service);
        var result = resolver.resolve(List.of(new RecorderRef("receipt", 11L), new RecorderRef("receipt", 11L)));
        assertThat(result).extracting(RecorderRefPresentation::label).containsExactly("Общее представление", "Общее представление");
        assertThat(result).allSatisfy(value -> assertThat(value.href()).contains("/records/receipt/11"));
        org.mockito.Mockito.verifyNoInteractions(reads);
    }

    @Test
    void groupsIdsIntoOneCanonicalReadAndUsesNameFetchPathsAndPublishedFormLink() {
        EntityReadAccess access = mock(EntityReadAccess.class);
        RecorderTypeRegistry types = mock(RecorderTypeRegistry.class);
        FormLinkService links = mock(FormLinkService.class);
        when(types.findType("receipt")).thenReturn(Optional.of(ReceiptDocument.class));

        ReceiptDocument first = document(11L, "R-011");
        ReceiptDocument second = document(12L, "R-012");
        when(access.list(eq(ReceiptDocument.class), any(Specification.class), any(Pageable.class), any()))
            .thenReturn(new PageImpl<>(List.of(first, second)));
        when(links.linkToRecord(ReceiptDocument.class, 11L)).thenReturn(link(11L));
        when(links.linkToRecord(ReceiptDocument.class, 12L)).thenReturn(link(12L));

        RecorderRefPresentationResolver resolver = resolver(access, types, links);
        List<RecorderRefPresentation> result = resolver.resolve(List.of(
            new RecorderRef("receipt", 11L), new RecorderRef("receipt", 12L),
            new RecorderRef("receipt", 11L)));

        assertThat(result).extracting(RecorderRefPresentation::label)
            .containsExactly("R-011", "R-012", "R-011");
        assertThat(result).extracting(RecorderRefPresentation::href)
            .containsExactly(Optional.of("/records/receipt/11"), Optional.of("/records/receipt/12"),
                Optional.of("/records/receipt/11"));
        verify(access, times(1)).list(eq(ReceiptDocument.class), any(Specification.class),
            eq(Pageable.ofSize(2)), eq(List.of("id", "party.name")));
    }

    @Test
    void missingAndDeniedDocumentsHaveTheSameMaskedCaptionAndNoLink() {
        EntityReadAccess access = mock(EntityReadAccess.class);
        RecorderTypeRegistry types = mock(RecorderTypeRegistry.class);
        FormLinkService links = mock(FormLinkService.class);
        when(types.findType("receipt")).thenReturn(Optional.of(ReceiptDocument.class));
        when(access.list(eq(ReceiptDocument.class), any(Specification.class), any(Pageable.class), any()))
            .thenReturn(Page.empty())
            .thenThrow(new AccessDeniedException("secret denial detail"));

        RecorderRefPresentationResolver resolver = resolver(access, types, links);
        RecorderRef reference = new RecorderRef("receipt", 99L);
        RecorderRefPresentation missing = resolver.resolve(List.of(reference)).get(0);
        RecorderRefPresentation denied = resolver.resolve(List.of(reference)).get(0);

        assertThat(missing).isEqualTo(RecorderRefPresentation.unavailable());
        assertThat(denied).isEqualTo(RecorderRefPresentation.unavailable());
        verify(links, org.mockito.Mockito.never()).linkToRecord(any(), any());
        verify(access, times(2)).list(eq(ReceiptDocument.class), any(Specification.class),
            any(Pageable.class), any());
    }

    @Test
    void rerunsCanonicalReadOnEveryCallSoAVisibleCaptionIsNotReusedAcrossSecurityContexts() {
        EntityReadAccess access = mock(EntityReadAccess.class);
        RecorderTypeRegistry types = mock(RecorderTypeRegistry.class);
        FormLinkService links = mock(FormLinkService.class);
        when(types.findType("receipt")).thenReturn(Optional.of(ReceiptDocument.class));
        when(access.list(eq(ReceiptDocument.class), any(Specification.class), any(Pageable.class), any()))
            .thenReturn(new PageImpl<>(List.of(document(11L, "R-011"))))
            .thenThrow(new AccessDeniedException("denied after access change"));
        when(links.linkToRecord(ReceiptDocument.class, 11L)).thenReturn(link(11L));

        RecorderRefPresentationResolver resolver = resolver(access, types, links);
        RecorderRef reference = new RecorderRef("receipt", 11L);

        assertThat(resolver.resolve(List.of(reference)).get(0).label()).isEqualTo("R-011");
        assertThat(resolver.resolve(List.of(reference)).get(0))
            .isEqualTo(RecorderRefPresentation.unavailable());
        verify(access, times(2)).list(eq(ReceiptDocument.class), any(Specification.class),
            any(Pageable.class), any());
    }

    @Test
    void accessibleDocumentWithoutPublishedFormStillHasCaptionButNoNavigation() {
        EntityReadAccess access = mock(EntityReadAccess.class);
        RecorderTypeRegistry types = mock(RecorderTypeRegistry.class);
        FormLinkService links = mock(FormLinkService.class);
        when(types.findType("receipt")).thenReturn(Optional.of(ReceiptDocument.class));
        when(access.list(eq(ReceiptDocument.class), any(Specification.class), any(Pageable.class), any()))
            .thenReturn(new PageImpl<>(List.of(document(11L, "R-011"))));
        when(links.linkToRecord(ReceiptDocument.class, 11L))
            .thenReturn(FormLinkResult.notLinkable(
                org.ipro.form.link.NotLinkableReason.NOT_PUBLISHED));

        RecorderRefPresentation result = resolver(access, types, links)
            .resolve(List.of(new RecorderRef("receipt", 11L))).get(0);

        assertThat(result.label()).isEqualTo("R-011");
        assertThat(result.href()).isEmpty();
    }

    @Test
    void usesAnExplicitDocumentReadPolicyInsteadOfTheGenericIntegrationBridge() {
        EntityReadAccess genericRead = mock(EntityReadAccess.class);
        EntityDataAccessResolver accessResolver = mock(EntityDataAccessResolver.class);
        EntityDataAccess customRead = mock(EntityDataAccess.class);
        RecorderTypeRegistry types = mock(RecorderTypeRegistry.class);
        FormLinkService links = mock(FormLinkService.class);
        when(types.findType("receipt")).thenReturn(Optional.of(ReceiptDocument.class));
        when(accessResolver.hasCustomPolicy(ReceiptDocument.class)).thenReturn(true);
        when(accessResolver.resolve(ReceiptDocument.class)).thenReturn(customRead);
        when(customRead.list(eq(ReceiptDocument.class), any(Specification.class), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(document(11L, "R-011"))));
        when(links.linkToRecord(ReceiptDocument.class, 11L)).thenReturn(link(11L));
        InstanceNameResolver names = new InstanceNameResolver(
            List.of(ReceiptDocument.class, Party.class), new MetadataResolver());

        RecorderRefPresentation result = new RecorderRefPresentationResolver(
            genericRead, accessResolver, types, names, links)
            .resolve(List.of(new RecorderRef("receipt", 11L))).get(0);

        assertThat(result.label()).isEqualTo("R-011");
        verify(customRead).list(eq(ReceiptDocument.class), any(Specification.class),
            eq(Pageable.ofSize(1)));
        org.mockito.Mockito.verifyNoInteractions(genericRead);
    }

    @Test
    void chunksLargeTypeGroupsAndKeepsRecorderRenderingReadOnly() {
        EntityReadAccess access = mock(EntityReadAccess.class);
        RecorderTypeRegistry types = mock(RecorderTypeRegistry.class);
        FormLinkService links = mock(FormLinkService.class);
        when(types.findType("receipt")).thenReturn(Optional.of(ReceiptDocument.class));
        when(access.list(eq(ReceiptDocument.class), any(Specification.class), any(Pageable.class), any()))
            .thenAnswer(call -> {
                int size = call.getArgument(2, Pageable.class).getPageSize();
                long firstId = size == RecorderRefPresentationResolver.ID_CHUNK_SIZE ? 1L : 201L;
                List<ReceiptDocument> docs = new java.util.ArrayList<>();
                for (long id = firstId; id < firstId + size; id++) {
                    docs.add(document(id, "R-" + id));
                }
                return new PageImpl<>(docs);
            });
        when(links.linkToRecord(eq(ReceiptDocument.class), anyLong()))
            .thenAnswer(call -> link(call.getArgument(1)));

        List<RecorderRef> references = LongStream.rangeClosed(1, 201)
            .mapToObj(id -> new RecorderRef("receipt", id)).toList();
        List<RecorderRefPresentation> result = resolver(access, types, links).resolve(references);

        assertThat(result).hasSize(201);
        assertThat(result.get(200).label()).isEqualTo("R-201");
        ArgumentCaptor<Pageable> pages = ArgumentCaptor.forClass(Pageable.class);
        verify(access, times(2)).list(eq(ReceiptDocument.class), any(Specification.class),
            pages.capture(), eq(List.of("id", "party.name")));
        assertThat(pages.getAllValues()).extracting(Pageable::getPageSize).containsExactly(200, 1);

        RecorderRefDisplay linked = new RecorderRefDisplay(result.get(0));
        assertThat(linked.getChildren().findFirst().orElseThrow()).isInstanceOf(Anchor.class);
        RecorderRefDisplay masked = new RecorderRefDisplay(RecorderRefPresentation.unavailable());
        assertThat(masked.getElement().getText()).isEqualTo(RecorderRefPresentation.UNAVAILABLE_LABEL);
        assertThat(masked.getChildren()).isEmpty();
        assertThat(masked).isNotInstanceOf(com.vaadin.flow.component.HasValue.class);
    }

    private static RecorderRefPresentationResolver resolver(EntityReadAccess access,
                                                              RecorderTypeRegistry types,
                                                              FormLinkService links) {
        InstanceNameResolver names = new InstanceNameResolver(
            List.of(ReceiptDocument.class, Party.class), new MetadataResolver());
        EntityDataAccessResolver dataAccessResolver = mock(EntityDataAccessResolver.class);
        when(dataAccessResolver.hasCustomPolicy(ReceiptDocument.class)).thenReturn(false);
        return new RecorderRefPresentationResolver(access, dataAccessResolver, types, names, links);
    }

    private static ReceiptDocument document(Long id, String number) {
        ReceiptDocument result = new ReceiptDocument();
        result.setId(id);
        result.setParty(new Party(number));
        return result;
    }

    private static FormLinkResult link(Long id) {
        FormRoute route = FormRoute.record("receipt", id);
        return FormLinkResult.linkable(route, "/records/receipt/" + id);
    }

    @Entity
    @Table(name = "g6_receipt_document")
    @InstanceName("party.name")
    static class ReceiptDocument extends BaseEntity {
        @ManyToOne
        private Party party;

        Party getParty() { return party; }
        void setParty(Party party) { this.party = party; }
    }

    @Entity
    @Table(name = "g6_party")
    static class Party extends BaseEntity {
        private String name;

        Party() { }
        Party(String name) { this.name = name; }
        String getName() { return name; }
        void setName(String name) { this.name = name; }
    }
}
