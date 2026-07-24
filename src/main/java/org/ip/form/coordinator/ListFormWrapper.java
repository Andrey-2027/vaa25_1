package org.ip.form.coordinator;

import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import org.ip.form.builtin.ListForm;
import org.ip.views.workspace.Dirtyable;
import org.ip.views.workspace.Savable;

/**
 * Wrapper для ListForm, чтобы его можно было открыть в Workspace.
 * Workspace требует Component, а ListForm уже extends VerticalLayout.
 *
 * Этот класс нужен для того, чтобы WorkspaceManager мог создать экземпляр через Spring,
 * а затем мы программно устанавливаем контент через setContent().
 *
 * Поддерживает Dirtyable/Savable для управления несохранёнными изменениями при закрытии вкладки.
 * Делегирует проверку к внутреннему контенту, если тот реализует эти интерфейсы.
 */
public class ListFormWrapper extends VerticalLayout implements Dirtyable, Savable {

    private ListForm<?, ?> listForm;
    private Dirtyable dirtyDelegate;
    private Savable saveDelegate;

    public ListFormWrapper() {
        setSizeFull();
        setPadding(false);
        setSpacing(false);
    }

    /**
     * Устанавливает ListForm как содержимое этого wrapper'а.
     */
    public void setContent(ListForm<?, ?> listForm) {
        removeAll();
        add(listForm);
        this.listForm = listForm;

        // Проверяем, реализует ли ListForm нужные интерфейсы (на будущее)
        if (listForm instanceof Dirtyable) {
            this.dirtyDelegate = (Dirtyable) listForm;
        }
        if (listForm instanceof Savable) {
            this.saveDelegate = (Savable) listForm;
        }
    }

    /**
     * Устанавливает делегат для проверки dirty-состояния.
     * Используется, когда внутри ListForm открывается ItemForm в режиме редактирования.
     */
    public void setDirtyDelegate(Dirtyable delegate) {
        this.dirtyDelegate = delegate;
    }

    /**
     * Устанавливает делегат для сохранения.
     */
    public void setSaveDelegate(Savable delegate) {
        this.saveDelegate = delegate;
    }

    @Override
    public boolean isDirty() {
        return dirtyDelegate != null && dirtyDelegate.isDirty();
    }

    @Override
    public String getCloseConfirmMessage() {
        return dirtyDelegate != null
            ? dirtyDelegate.getCloseConfirmMessage()
            : "Закрыть без сохранения?";
    }

    @Override
    public boolean doSave() {
        return saveDelegate != null && saveDelegate.doSave();
    }

    public ListForm<?, ?> getListForm() {
        return listForm;
    }
}
