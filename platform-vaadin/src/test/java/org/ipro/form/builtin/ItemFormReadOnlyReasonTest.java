package org.ipro.form.builtin;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Span;
import org.ipro.crud.BaseEntity;
import org.ipro.form.FieldFactory;
import org.ipro.form.FormSaveHandler;
import org.ipro.form.FormSaveResult;
import org.ipro.form.action.ReadOnlyReason;
import org.ipro.metadata.EntityMetadataInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * E1.5: причина режима просмотра — часть состояния формы, а не текст, который host выдумывает сам.
 *
 * <p>Проверяется главное различие §2.5 плана: отказ в правах показывает бейдж «Только просмотр:
 * …причина…», а тип, который не умеет менять запись, и явно запрошенный просмотр режим включают,
 * но сообщения о правах <b>не</b> показывают. Раньше оба случая были одним {@code setReadOnly(true)}
 * с необязательной строкой бейджа, поэтому карточка либо молчала о причине, либо сообщала о правах,
 * которых никто не отказывал.</p>
 *
 * <p>Metadata здесь без form-полей (реестр биндингов пуст): режим формы не выводится из реестра, а
 * {@code isReadOnly()} обязан сообщать {@code true} и в этом случае — иначе host, спрашивающий
 * «карточка только для чтения?», получает противоположный ответ.</p>
 */
class ItemFormReadOnlyReasonTest {

    @Test
    void accessDeniedShowsExistingRightsBadgeWithTheSameText() {
        ItemForm<TestDocument> form = formWithMetadata();

        form.setReadOnly(ReadOnlyReason.accessDenied("нет прав на изменение (измерение ENTITY:Doc)"));

        assertThat(form.isReadOnly()).isTrue();
        assertThat(form.readOnlyReason().kind())
            .isEqualTo(ReadOnlyReason.Kind.ACCESS_DENIED);
        assertThat(visibleNotice(form))
            .contains("Только просмотр: нет прав на изменение (измерение ENTITY:Doc)");
    }

    @Test
    void typeWithoutGenericUpdateIsNeutralEvenWithPolicyDetail() {
        ItemForm<TestDocument> form = formWithMetadata();

        form.setReadOnly(ReadOnlyReason.typeReadOnly("Тип не поддерживает UPDATE (policy: …)"));

        assertThat(form.isReadOnly()).isTrue();
        assertThat(form.readOnlyReason().kind())
            .isEqualTo(ReadOnlyReason.Kind.TYPE_READ_ONLY);
        assertThat(visibleNotice(form))
            .as("сообщение о правах там, где права никто не отказывал, — ложь в интерфейсе")
            .isEmpty();
    }

    @Test
    void requestedViewIsNeutralToo() {
        ItemForm<TestDocument> form = formWithMetadata();

        form.setReadOnly(ReadOnlyReason.requested());

        assertThat(form.isReadOnly()).isTrue();
        assertThat(form.readOnlyReason().kind()).isEqualTo(ReadOnlyReason.Kind.REQUESTED);
        assertThat(visibleNotice(form)).isEmpty();
    }

    @Test
    void plainBooleanReadOnlyKeepsNeutralTypedReason() {
        ItemForm<TestDocument> form = formWithMetadata();

        form.setReadOnly(true);

        assertThat(form.readOnlyReason()).isNotNull();
        assertThat(form.readOnlyReason().kind())
            .isEqualTo(ReadOnlyReason.Kind.TYPE_READ_ONLY);
        assertThat(visibleNotice(form)).isEmpty();
    }

    @Test
    void nullReasonReturnsFormToEditableModeAndHidesBadge() {
        ItemForm<TestDocument> form = formWithMetadata();
        form.setReadOnly(ReadOnlyReason.accessDenied("нет прав"));
        assertThat(visibleNotice(form)).isPresent();

        form.setReadOnly((ReadOnlyReason) null);

        assertThat(form.isReadOnly()).isFalse();
        assertThat(form.readOnlyReason()).isNull();
        assertThat(visibleNotice(form)).isEmpty();
    }

    @Test
    void isReadOnlyIsTrueEvenWhenFormHasNoBindings() {
        ItemForm<TestDocument> form = formWithMetadata();

        assertThat(form.getBindingRegistry().isReadOnly()).isFalse();
        form.setReadOnly(true);

        assertThat(form.isReadOnly()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void saveInReadOnlyExplainsModeAndNeverReachesTheHandler() {
        ItemForm<TestDocument> form = formWithMetadata();
        FormSaveHandler<TestDocument> handler = mock(FormSaveHandler.class);
        form.setSaveHandler(handler);
        form.setReadOnly(ReadOnlyReason.typeReadOnly("Тип не поддерживает UPDATE (policy: …)"));

        FormSaveResult<TestDocument> result = form.save();

        assertThat(result.success()).isFalse();
        assertThat(((FormSaveResult.Failure<TestDocument>) result).messages())
            .containsExactly("Форма открыта только для просмотра: "
                + "Тип не поддерживает UPDATE (policy: …)");
        verifyNoInteractions(handler);
    }

    @Test
    @SuppressWarnings("unchecked")
    void saveInReadOnlyWithoutStatedReasonStillRefuses() {
        ItemForm<TestDocument> form = formWithMetadata();
        form.setSaveHandler(mock(FormSaveHandler.class));
        form.setReadOnly(true);

        FormSaveResult<TestDocument> result = form.save();

        assertThat(result.success()).isFalse();
        assertThat(((FormSaveResult.Failure<TestDocument>) result).messages())
            .as("сообщение о режиме, а не об отсутствующем обработчике сохранения")
            .containsExactly("Форма открыта только для просмотра");
    }

    /** Текст видимого бейджа, если бейдж показан (режим редактирования — пусто). */
    private static Optional<String> visibleNotice(ItemForm<TestDocument> form) {
        return form.getChildren()
            .filter(component -> component instanceof Span)
            .filter(Component::isVisible)
            .map(component -> ((Span) component).getText())
            .filter(text -> !text.isEmpty())
            .findFirst();
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
