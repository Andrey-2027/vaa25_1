package org.ipro.form.coordinator;

import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import org.ipro.crud.BaseEntity;
import org.ipro.form.action.ActionResolver;
import org.ipro.form.action.CopyLinkButton;
import org.ipro.form.builtin.ItemForm;
import org.ipro.form.link.FormLinkResult;
import org.ipro.form.link.FormLinkService;
import org.ipro.form.link.NotLinkableReason;
import org.ipro.identity.IdentifiableEntity;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ItemFormLinkAffordanceTest {
    static class Document extends BaseEntity { }
    static class StringIdentity implements IdentifiableEntity<String> {
        public String getId() { return "42"; }
        public void setId(String id) { }
    }

    @Test
    void recordAddressReadsNewLongIdAfterSaveWithoutReattachingButton() {
        Document entity = new Document();
        AtomicReference<Object> current = new AtomicReference<>(entity);
        FormLinkService links = links();
        CopyLinkButton button = attach(current, links);
        address(button).get();
        verify(links).linkToRecord(Document.class, null, "variant");
        entity.setId(42L);
        address(button).get();
        verify(links).linkToRecord(Document.class, 42L, "variant");
    }

    @Test
    void runtimeBoundaryDoesNotConvertStringIdentityToLongRecordLink() {
        FormLinkService links = links();
        CopyLinkButton button = attach(new AtomicReference<>(new StringIdentity()), links);
        address(button).get();
        verify(links).linkToRecord(Document.class, null, "variant");
    }

    private static CopyLinkButton attach(AtomicReference<Object> current, FormLinkService links) {
        ItemForm<?> form = mock(ItemForm.class);
        // Deliberately exercise the erased runtime boundary with a foreign identity type.
        doAnswer(ignored -> current.get()).when(form).peekEntity();
        when(form.getFooter()).thenReturn(new HorizontalLayout());
        return ItemFormLinkAffordance.attach(form, links, mock(ActionResolver.class), Document.class, "variant");
    }

    private static FormLinkService links() {
        FormLinkService links = mock(FormLinkService.class);
        when(links.linkToRecord(eq(Document.class), any(), eq("variant")))
            .thenReturn(FormLinkResult.notLinkable(NotLinkableReason.MISSING_ID));
        return links;
    }

    private static Supplier<?> address(CopyLinkButton button) {
        return (Supplier<?>) ReflectionTestUtils.getField(button, "address");
    }
}
