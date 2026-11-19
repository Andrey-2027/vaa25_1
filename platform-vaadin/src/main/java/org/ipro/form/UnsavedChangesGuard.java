package org.ipro.form;

import com.vaadin.flow.component.confirmdialog.ConfirmDialog;

import java.util.Objects;

/**
 * Общий вопрос о несохранённых изменениях при закрытии диалоговой формы (Д-ДФ12): окно карточки
 * и окно строки табличной части закрываются через один и тот же механизм, а формулировки те же,
 * что у вкладки рабочей области.
 *
 * <ul>
 *   <li>изменений нет — окно закрывается без вопроса;</li>
 *   <li>есть, форма редактируется — вопрос «Несохранённые изменения»: «Сохранить и закрыть»
 *       ({@link Savable#doSave()}, при успехе окно закрывается), «Закрыть» (отбросить изменения
 *       и закрыть), «Отмена» (остаться);</li>
 *   <li>есть, форма в просмотре — «Закрыть» / «Отмена»: сохранять нечего (E1.5).</li>
 * </ul>
 *
 * <p>Кнопки вопроса расставлены так, что Esc в самом вопросе означает «Отмена», а не «Закрыть»:
 * случайное нажатие не должно отбрасывать правки.</p>
 *
 * <p>Служебный класс платформы: приложение им не пользуется.</p>
 */
public final class UnsavedChangesGuard implements DialogForm.CloseGuard {

    private final Dirtyable dirty;
    private final Savable savable;
    private final Runnable discard;

    private UnsavedChangesGuard(Dirtyable dirty, Savable savable, Runnable discard) {
        this.dirty = Objects.requireNonNull(dirty, "dirty");
        this.savable = Objects.requireNonNull(savable, "savable");
        this.discard = Objects.requireNonNull(discard, "discard");
    }

    /**
     * @param dirty   источник признака «есть изменения» и текста вопроса
     * @param savable сохранение и признак режима просмотра
     * @param discard что сделать при «Закрыть» (откат правок формы); перед закрытием окна
     */
    public static UnsavedChangesGuard forForm(Dirtyable dirty, Savable savable, Runnable discard) {
        return new UnsavedChangesGuard(dirty, savable, discard);
    }

    /** Форма, которая сама и «грязная», и сохраняемая (ItemForm), без отката правок. */
    public static <F extends Dirtyable & Savable> UnsavedChangesGuard forForm(F form) {
        return new UnsavedChangesGuard(form, form, () -> { });
    }

    @Override
    public void beforeClose(Runnable proceed) {
        if (!dirty.isDirty()) {
            proceed.run();
            return;
        }
        question(proceed).open();
    }

    /** Собранный, но не открытый вопрос; отдельно от {@link #beforeClose} ради проверки без браузера. */
    ConfirmDialog question(Runnable proceed) {
        ConfirmDialog confirm = new ConfirmDialog();
        confirm.setHeader("Несохранённые изменения");
        confirm.setText(dirty.getCloseConfirmMessage());
        if (savable.isReadOnly()) {
            // Карточка просмотра не предлагает сохранение (E1.5): сохранять нечего, а
            // «Сохранить и закрыть» вело бы к отклонённому сервером записи.
            confirm.setConfirmButton("Закрыть", event -> discardAndClose(proceed));
            confirm.setCancelButton("Отмена", event -> { });
        } else {
            confirm.setConfirmButton("Сохранить и закрыть", event -> {
                if (savable.doSave()) {
                    proceed.run();
                }
            });
            confirm.setRejectButton("Закрыть", event -> discardAndClose(proceed));
            confirm.setCancelButton("Отмена", event -> { });
        }
        return confirm;
    }

    private void discardAndClose(Runnable proceed) {
        discard.run();
        proceed.run();
    }
}
