package org.ipro.form.link;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * E3.0: грамматика адреса типа Explorer. Тест фиксирует не «умеет разбирать», а решения
 * ADR-0010: ключ читается той же грамматикой, что у адреса формы, украшения адреса (регистр,
 * лишний сегмент, query, фрагмент, percent-encoding) не нормализуются — иначе у карточки
 * появился бы второй адрес, — а отказ наружу выходит без причины, чтобы host не получил
 * источник, различающий состояние ключа.
 */
class EntityExplorerAddressTest {

    @Test
    void readsTheKeyFromTheCanonicalAddress() {
        assertThat(EntityExplorerAddress.keyOf("/entity-explorer/nomenclature"))
            .contains("nomenclature");
        assertThat(EntityExplorerAddress.keyOf("/entity-explorer/skl-nom-opa"))
            .contains("skl-nom-opa");
        assertThat(EntityExplorerAddress.format("nomenclature"))
            .isEqualTo("/entity-explorer/nomenclature");
    }

    @Test
    void formatThenKeyOfReturnsTheSameKey() {
        for (String key : List.of("nomenclature", "prd-spec", "skl-nom-opa", "x")) {
            assertThat(EntityExplorerAddress.keyOf(EntityExplorerAddress.format(key)))
                .as("format → keyOf обязан возвращать тот же ключ: %s", key)
                .contains(key);
        }
    }

    @Test
    void rejectsAddressesOutsideTheCanonicalForm() {
        for (String address : List.of(
                "",
                " ",
                "/",
                "entity-explorer",
                "/entity-explorer",
                "/entity-explorer/",
                "/entity-explorer/a/b",
                "/entity-explorer/nomenclature/",
                "/entity-explorer/Nomenclature",
                "/entity-explorer/nomencl%61ture",
                "/entity-explorer/nomenclature?tab=data",
                "/entity-explorer/nomenclature#attributes",
                "/entity-explorers/nomenclature",
                "/records/entity-explorer/42",
                "/lists/entity-explorer")) {
            assertThat(EntityExplorerAddress.keyOf(address))
                .as("адрес вне канонической формы не даёт ключа: '%s'", address)
                .isEmpty();
        }
        assertThat(EntityExplorerAddress.keyOf(null))
            .as("отсутствие адреса — тот же отказ, что и адрес вне грамматики")
            .isEmpty();
    }

    /**
     * «Домой» ведёт адрес, который ничего не просит. Адрес раздела Explorer заявлен даже без
     * ключа и с лишним сегментом: host обязан ответить отказом, а не показать главную.
     */
    @Test
    void claimsExactlyTheExplorerRoot() {
        assertThat(EntityExplorerAddress.claims("/entity-explorer/nomenclature")).isTrue();
        assertThat(EntityExplorerAddress.claims("entity-explorer/nomenclature"))
            .as("ведущий слэш необязателен: адрес окна приходит и без него")
            .isTrue();
        assertThat(EntityExplorerAddress.claims("/entity-explorer")).isTrue();
        assertThat(EntityExplorerAddress.claims("/entity-explorer/a/b")).isTrue();
        assertThat(EntityExplorerAddress.claims("/entity-explorer?tab=data"))
            .as("query не меняет раздел: ключа у такого адреса нет, но главную он не показывает")
            .isTrue();
        assertThat(EntityExplorerAddress.claims("/entity-explorer#data")).isTrue();
        assertThat(EntityExplorerAddress.claims("/entity-explorers/nomenclature")).isFalse();
        assertThat(EntityExplorerAddress.claims("/records/entity-explorer/42")).isFalse();
        assertThat(EntityExplorerAddress.claims("/lists/entity-explorer")).isFalse();
        assertThat(EntityExplorerAddress.claims("/")).isFalse();
        assertThat(EntityExplorerAddress.claims(null)).isFalse();
    }

    @Test
    void theKeyGrammarIsTheSameAsForTheFormAddress() {
        for (String key : List.of("prd_spec", "1x", "x-", "-x", "X", "x y", "x/y")) {
            assertThat(EntityExplorerAddress.keyOf("/entity-explorer/" + key))
                .as("грамматика ключа не расширяется относительно FormRoute.isKey: '%s'", key)
                .isEmpty();
            assertThatIllegalArgumentException()
                .as("format применяется к ключу каталога и на выходе грамматики падает: '%s'", key)
                .isThrownBy(() -> EntityExplorerAddress.format(key));
        }
    }

    /**
     * Резерв занятых первых сегментов — политика публикации (список {@code reserved} в baseline
     * маршрутов), а не грамматика адреса: технически ключ стоит вторым сегментом, поэтому
     * синтаксически допустим любой ключ грамматики, включая совпадающий с разделом.
     */
    @Test
    void aKeyEqualToTheRootSegmentIsGrammaticallyValid() {
        assertThat(EntityExplorerAddress.keyOf("/entity-explorer/entity-explorer"))
            .contains("entity-explorer");
    }

    @Test
    void formatRejectsKeysOutsideTheGrammar() {
        assertThatIllegalArgumentException().isThrownBy(
            () -> EntityExplorerAddress.format(""));
        assertThatNullPointerException()
            .as("null — ошибка вызова, а не ключ вне грамматики: как у FormRoute")
            .isThrownBy(() -> EntityExplorerAddress.format(null));
    }
}
