package org.ipro.data.presentation;
import org.ipro.crud.BaseEntity;
import org.ipro.data.*;
import org.ipro.fetch.instance.InstanceNameResolver;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.annotation.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.access.AccessDeniedException;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class EntityPresentationServiceTest {
    @EntityMetadata(kind=EntityKind.CATALOG) static class Item extends BaseEntity { }
    @EntityMetadata(kind=EntityKind.DOCUMENT) static class Document extends BaseEntity { }
    @Test void batchesUniqueReferencesAndMasksAbsentObjects() {
        var reads = mock(EntityReadAccess.class); var policies = mock(EntityDataAccessResolver.class); var names = mock(InstanceNameResolver.class);
        var service = new EntityPresentationService(reads, policies, names, new MetadataResolver());
        var item = new Item(); item.setId(1L);
        when(names.instanceNamePaths(Item.class)).thenReturn(List.of("id")); when(names.resolve(item)).thenReturn("Первый");
        when(reads.list(eq(Item.class), any(), any(), any())).thenReturn(new PageImpl<>(List.of(item)));
        var one = new EntityPresentationService.Reference(Item.class, 1L); var missing = new EntityPresentationService.Reference(Item.class, 2L);
        assertThat(service.resolve(List.of(one,one,missing))).containsEntry(one,"Первый").containsEntry(missing,"Объект недоступен");
        verify(reads,times(1)).list(eq(Item.class),any(),any(),any()); verify(names,times(1)).resolve(item);
    }
    @Test void deniedDocumentAndLargeGroupsUseBoundedQueries() {
        var reads = mock(EntityReadAccess.class); var policies = mock(EntityDataAccessResolver.class); var names = mock(InstanceNameResolver.class);
        var service = new EntityPresentationService(reads, policies, names, new MetadataResolver());
        when(names.instanceNamePaths(Document.class)).thenReturn(List.of());
        when(reads.list(eq(Document.class),any(),any(),any())).thenThrow(new AccessDeniedException("denied"));
        var references = java.util.stream.LongStream.rangeClosed(1,401).mapToObj(id -> new EntityPresentationService.Reference(Document.class,id)).toList();
        assertThat(service.resolve(references)).hasSize(401).allSatisfy((ref,text)->assertThat(text).isEqualTo("Документ недоступен"));
        verify(reads,times(3)).list(eq(Document.class),any(),any(),any());
    }
}
