package org.ipro.form.spi;

import com.vaadin.flow.component.Component;

import java.util.function.Consumer;

/**
 * Платформенный контракт рабочей области (вкладки 1С-стиля).
 *
 * <p>Координатор открывает формы, не зная конкретный Workspace приложения: реализация — бин
 * <b>UI-scoped</b>, и координатор берёт её лениво, в момент открытия. Приложение не должно
 * «передавать» область куда-либо: наличие бина и есть включённый режим вкладок, а отсутствие —
 * работа в диалогах.</p>
 */
public interface WorkspaceGateway {

    /** Открыть (или активировать существующую) вкладку с View-классом. */
    <T extends Component> void open(Class<T> viewType, String entryId,
                                    String tabTitle, Consumer<T> initializer);

    /** Открыть (или активировать существующую) вкладку с готовым компонентом. */
    void openComponent(Component view, String entryId, String tabTitle);

    /** Закрыть вкладку (с подтверждением при несохранённых изменениях — на стороне impl). */
    void close(String entryId);
}
