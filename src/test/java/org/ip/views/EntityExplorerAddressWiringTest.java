package org.ip.views;

import org.ip.model.Nomenclature;
import org.ip.views.admin.EntityExplorerAccess;
import org.ipro.form.link.OpenResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E3.0: последовательность host'а по адресу Explorer. Адрес вкладки пишется до её открытия,
 * отказ не создаёт вкладку и не меняет адрес, а выбранный в дереве тип даёт канонический адрес
 * либо «адреса нет».
 *
 * <p>Проверяется та же функция, которой пользуется {@code MainLayout}: UI и рабочая область
 * подменены списками эффектов, поэтому порядок виден, а не выводится из чтения кода.</p>
 */
class EntityExplorerAddressWiringTest {

    /** Каталог-заглушка: ключ есть только у одного типа, остальные — «неизвестный ключ». */
    private final Function<String, Optional<Class<?>>> catalog = key ->
        "nomenclature".equals(key) ? Optional.of(Nomenclature.class) : Optional.empty();

    private final List<String> recorded = new ArrayList<>();
    private final List<Class<?>> opened = new ArrayList<>();
    private final List<OpenResult> refusals = new ArrayList<>();

    @Test
    void theAddressIsRecordedBeforeTheTabIsOpened() {
        MainLayout.ExplorerEntry entry =
            MainLayout.decideExplorerEntry(true, "/entity-explorer/nomenclature", catalog);

        List<String> effects = new ArrayList<>();
        MainLayout.applyExplorerEntry(entry,
            address -> {
                effects.add("address:" + address);
                recorded.add(address);
            },
            type -> {
                effects.add("open:" + type.getSimpleName());
                opened.add(type);
            }, refusals::add);

        assertThat(effects).containsExactly(
            "address:/entity-explorer/nomenclature", "open:Nomenclature");
        assertThat(recorded)
            .as("адрес обязан быть записан до активации: обратный порядок записал бы «/»")
            .containsExactly("/entity-explorer/nomenclature");
        assertThat(opened).containsExactly(Nomenclature.class);
        assertThat(refusals).isEmpty();
    }

    @Test
    void theRefusalNeitherOpensATabNorTouchesTheAddress() {
        MainLayout.ExplorerEntry entry =
            MainLayout.decideExplorerEntry(false, "/entity-explorer/nomenclature", catalog);

        MainLayout.applyExplorerEntry(entry, recorded::add, opened::add, refusals::add);

        assertThat(opened)
            .as("отказ не заводит вкладку: в баре не должно появляться то, за чем нет карточки")
            .isEmpty();
        assertThat(recorded)
            .as("адрес отказа не меняется: пользователь остаётся на том, что запросил")
            .isEmpty();
        assertThat(refusals).hasSize(1);
        assertThat(refusals.get(0).message()).isEqualTo(EntityExplorerAccess.REFUSAL_TEXT);
    }

    @Test
    void anUnknownKeyIsRefusedWithoutTouchingTheTab() {
        MainLayout.ExplorerEntry entry =
            MainLayout.decideExplorerEntry(true, "/entity-explorer/nope", catalog);

        MainLayout.applyExplorerEntry(entry, recorded::add, opened::add, refusals::add);

        assertThat(opened).isEmpty();
        assertThat(recorded).isEmpty();
        assertThat(refusals).hasSize(1);
    }

    @Test
    void theSelectedTypeAddressIsCanonicalAndAbsentWithoutAKey() {
        assertThat(MainLayout.explorerAddressOf(Optional.of("nomenclature")))
            .as("канонический адрес строится из published-ключа")
            .isEqualTo("/entity-explorer/nomenclature");
        assertThat(MainLayout.explorerAddressOf(Optional.empty()))
            .as("тип без опубликованного ключа безадресен: выдуманная ссылка открыла бы чужой адрес")
            .isNull();
    }
}
