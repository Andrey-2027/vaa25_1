package org.ipro.form;

import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Три ветки общего вопроса о несохранённых изменениях (§2.1 плана ДФ-5). */
class UnsavedChangesGuardTest {

    /** Заглушка формы: «грязная» ли, в просмотре ли, чем кончается сохранение. */
    private static final class Form implements Dirtyable, Savable {
        boolean dirty;
        boolean readOnly;
        boolean saveSucceeds = true;
        final List<String> calls = new ArrayList<>();

        @Override public boolean isDirty() { return dirty; }
        @Override public String getCloseConfirmMessage() { return "Есть изменения. Закрыть?"; }
        @Override public boolean doSave() { calls.add("save"); return saveSucceeds; }
        @Override public boolean isReadOnly() { return readOnly; }
    }

    private final Form form = new Form();
    private final AtomicInteger proceeded = new AtomicInteger();
    private final AtomicBoolean discarded = new AtomicBoolean();

    private UnsavedChangesGuard guard() {
        return UnsavedChangesGuard.forForm(form, form, () -> discarded.set(true));
    }

    @Test
    void withoutChangesTheWindowClosesImmediately() {
        guard().beforeClose(proceeded::incrementAndGet);

        assertThat(proceeded).hasValue(1);
        assertThat(discarded).isFalse();
        assertThat(form.calls).isEmpty();
    }

    @Test
    void editableFormAsksAndOffersSaveDiscardAndCancel() {
        form.dirty = true;
        ConfirmDialog question = guard().question(proceeded::incrementAndGet);

        assertThat(question.getElement().getProperty("header")).isEqualTo("Несохранённые изменения");
        assertThat(question.getElement().getProperty("confirmText")).isEqualTo("Сохранить и закрыть");
        assertThat(question.getElement().getProperty("rejectText")).isEqualTo("Закрыть");
        assertThat(question.getElement().getProperty("cancelText")).isEqualTo("Отмена");

        // Esc в самом вопросе — «Отмена»: окно остаётся, правки не отброшены.
        ComponentUtil.fireEvent(question, new ConfirmDialog.CancelEvent(question, true));
        assertThat(proceeded).hasValue(0);
        assertThat(discarded).isFalse();

        // «Закрыть»: откат и закрытие, сохранения нет.
        ComponentUtil.fireEvent(question, new ConfirmDialog.RejectEvent(question, true));
        assertThat(discarded).isTrue();
        assertThat(proceeded).hasValue(1);
        assertThat(form.calls).isEmpty();
    }

    @Test
    void saveAndCloseClosesOnlyWhenSavingSucceeded() {
        form.dirty = true;
        ConfirmDialog question = guard().question(proceeded::incrementAndGet);

        form.saveSucceeds = false;
        ComponentUtil.fireEvent(question, new ConfirmDialog.ConfirmEvent(question, true));
        assertThat(form.calls).containsExactly("save");
        assertThat(proceeded).hasValue(0);

        form.saveSucceeds = true;
        ComponentUtil.fireEvent(question, new ConfirmDialog.ConfirmEvent(question, true));
        assertThat(form.calls).containsExactly("save", "save");
        assertThat(proceeded).hasValue(1);
        assertThat(discarded).isFalse();
    }

    @Test
    void readOnlyFormOffersOnlyCloseAndCancel() {
        form.dirty = true;
        form.readOnly = true;
        ConfirmDialog question = guard().question(proceeded::incrementAndGet);

        assertThat(question.getElement().getProperty("confirmText")).isEqualTo("Закрыть");
        assertThat(question.getElement().getProperty("cancelText")).isEqualTo("Отмена");
        assertThat(question.getElement().getProperty("rejectText")).isNotEqualTo("Закрыть");

        ComponentUtil.fireEvent(question, new ConfirmDialog.ConfirmEvent(question, true));

        assertThat(form.calls).isEmpty();
        assertThat(proceeded).hasValue(1);
    }
}
