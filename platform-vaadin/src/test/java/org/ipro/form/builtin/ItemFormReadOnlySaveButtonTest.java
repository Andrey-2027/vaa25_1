package org.ipro.form.builtin;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import org.ipro.crud.BaseEntity;
import org.ipro.form.FieldFactory;
import org.ipro.metadata.EntityMetadataInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * E1.0a: read-only форма не показывает «Сохранить» ни при каком порядке вызовов, и
 * кнопка не выполняет сохранение даже при программном клике.
 *
 * <p>Раньше {@link ItemForm#setReadOnly(boolean)} прятал кнопку по тексту «Сохранить»,
 * а {@link ItemForm#addSaveButton()} создавал новую без проверки режима. В
 * {@code FormCoordinator.openItemFormAsDialog} и {@code ItemFormWrapperView} read-only
 * применялся раньше {@code withDefaultButtons()}, поэтому «Сохранить» оставалась видимой
 * в форме только для просмотра.</p>
 *
 * <p>Metadata здесь без form-полей (реестр биндингов пуст): раньше это был случай
 * {@code isReadOnly() == false} при включённом read-only, поэтому режим формы не мог
 * выводиться из реестра. С E1.5 {@code isReadOnly()} учитывает и запрошенный режим, но
 * проверки кнопок остаются на месте — они про то, что вид кнопки не зависит от порядка вызовов.</p>
 */
class ItemFormReadOnlySaveButtonTest {

    @Test
    void saveButtonAddedWhileReadOnlyIsNotVisible() {
        ItemForm<TestDocument> form = formWithMetadata();
        form.setReadOnly(true);

        Button save = form.addSaveButton();

        assertThat(save.isVisible()).isFalse();
    }

    @Test
    void withDefaultButtonsAfterReadOnlyLeavesOnlyCancelVisible() {
        ItemForm<TestDocument> form = formWithMetadata();

        form.setReadOnly(true);
        ItemForm<TestDocument> withButtons = form.withDefaultButtons();

        assertThat(visibleFooterButtons(withButtons)).hasSize(1);
        assertThat(visibleFooterButtons(withButtons).get(0).getText()).isEqualTo("Отмена");
    }

    @Test
    void readOnlyAfterWithDefaultButtonsHidesSaveAndReturnsItOnEdit() {
        ItemForm<TestDocument> form = formWithMetadata();
        Button save = form.addSaveButton();

        form.setReadOnly(true);
        assertThat(save.isVisible()).isFalse();

        form.setReadOnly(false);
        assertThat(save.isVisible()).isTrue();
    }

    @Test
    void saveButtonInReadOnlyDoesNotSaveEvenWhenClickedProgrammatically() {
        ItemForm<TestDocument> form = formWithMetadata();
        form.setReadOnly(true);
        AtomicInteger saves = new AtomicInteger();
        form.setOnSave(saves::incrementAndGet);
        Button save = form.addSaveButton();

        save.click();

        assertThat(saves.get()).isZero();
    }

    @Test
    void saveButtonInEditableModeInvokesOnSave() {
        ItemForm<TestDocument> form = formWithMetadata();
        AtomicInteger saves = new AtomicInteger();
        form.setOnSave(saves::incrementAndGet);
        Button save = form.addSaveButton();

        save.click();

        assertThat(saves.get()).isEqualTo(1);
    }

    private static List<Button> visibleFooterButtons(ItemForm<TestDocument> form) {
        return form.getFooter().getChildren()
            .filter(component -> component instanceof Button)
            .map(component -> (Button) component)
            .filter(Component::isVisible)
            .toList();
    }

    private static ItemForm<TestDocument> formWithMetadata() {
        EntityMetadataInfo metadata = mock(EntityMetadataInfo.class);
        doReturn(TestDocument.class).when(metadata).getEntityClass();
        when(metadata.getFormFields()).thenReturn(List.of());
        return new ItemForm<>(metadata, mock(FieldFactory.class));
    }

    public static class TestDocument extends BaseEntity {
    }
}
