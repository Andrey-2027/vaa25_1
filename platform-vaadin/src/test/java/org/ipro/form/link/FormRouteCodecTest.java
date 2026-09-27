package org.ipro.form.link;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * E2.1: синтаксис адреса глубокой ссылки. Тест фиксирует не «умеет разбирать», а границу
 * канонической формы: каждый отказ здесь — решение из ADR-0009 §3, потому что терпимость
 * парсера открывала бы форму по адресу, которого никто не публиковал.
 */
class FormRouteCodecTest {

    private final FormRouteCodec codec = new FormRouteCodec();

    @Test
    void formatsCanonicalAddresses() {
        assertThat(codec.format(FormRoute.record("nomenclature", 42)))
            .isEqualTo("/records/nomenclature/42");
        assertThat(codec.format(FormRoute.list("nomenclature")))
            .isEqualTo("/lists/nomenclature");
        assertThat(codec.format(new FormRoute(FormRouteKind.ITEM, "prd-spec", 73L, "materials-only")))
            .isEqualTo("/records/prd-spec/73?variant=materials-only");
    }

    @Test
    void formatThenParseReturnsTheSameRoute() {
        for (FormRoute route : new FormRoute[] {
                FormRoute.record("nomenclature", 42),
                FormRoute.list("nomenclature"),
                new FormRoute(FormRouteKind.ITEM, "prd-spec", 73L, "full"),
                new FormRoute(FormRouteKind.LIST, "prd-spec", null, "contextual")}) {
            FormRouteParseResult result = codec.parse(codec.format(route));
            assertThat(result.isParsed())
                .as("format → parse обязан возвращать тот же маршрут: %s", route)
                .isTrue();
            assertThat(result.parsedRoute()).contains(route);
        }
    }

    @Test
    void rejectsAddressesOutsideTheCanonicalForm() {
        assertRejected("", "пустой адрес");
        assertRejected("records/nomenclature/42", "относительным");
        assertRejected("https://stand/records/nomenclature/42", "относительным");
        assertRejected("/records/Nomenclature/42", "entityKey");
        assertRejected("/records/nomencl%61ture%20x/42", "entityKey");
        assertRejected("/records/nomenclature/042", "id");
        assertRejected("/records/nomenclature/0", "id");
        assertRejected("/records/nomenclature/-1", "id");
        assertRejected("/records/nomenclature/99999999999999999999", "id");
        assertRejected("/records/nomenclature", "карточка требует");
        assertRejected("/lists/nomenclature/42", "список требует");
        assertRejected("/records/nomenclature/42/lines", "карточка требует");
        assertRejected("/records/nomenclature/42?variant=", "variant");
        assertRejected("/records/nomenclature/42?variant=a&variant=b", "дважды");
        assertRejected("/records/nomenclature/42?varaint=full", "неизвестный query-параметр");
        assertRejected("/records/nomenclature/42?", "пустой query-параметр");
        assertRejected("/unknown/nomenclature/42", "неизвестный раздел");
    }

    /**
     * Percent-encoding разбирается один раз: декодированный регистр не нормализуется, иначе
     * «%4Eomenclature» стал бы вторым адресом той же сущности.
     */
    @Test
    void decodesPercentEncodingBeforeGrammarChecks() {
        assertThat(codec.parse("/records/nomenclature/%34%32").parsedRoute())
            .contains(FormRoute.record("nomenclature", 42));
        assertThat(codec.parse("/records/%4Eomenclature/42").isParsed())
            .as("декодирование не меняет регистр: это отказ, а не второй адрес")
            .isFalse();
        assertRejected("/records/nomenclature/%3", "id");
    }

    /** Инфраструктурный параметр Vaadin переносится; фрагмент не является частью контракта. */
    @Test
    void keepsVaadinContinueParameterAndIgnoresFragment() {
        assertThat(codec.parse("/records/nomenclature/42?continue=%2Frecords%2Fnomenclature%2F7")
                .parsedRoute())
            .contains(FormRoute.record("nomenclature", 42));
        assertThat(codec.parse("/lists/prd-spec?variant=contextual#rows").parsedRoute())
            .contains(new FormRoute(FormRouteKind.LIST, "prd-spec", null, "contextual"));
    }

    /** Инварианты маршрута проверяются конструктором, а не только парсером. */
    @Test
    void routeInvariantsAreEnforcedByTheType() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new FormRoute(FormRouteKind.ITEM, "nomenclature", null, null))
            .withMessageContaining("положительный id");
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new FormRoute(FormRouteKind.LIST, "nomenclature", 1L, null))
            .withMessageContaining("не принимает id");
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new FormRoute(FormRouteKind.LIST, "Nomenclature", null, null))
            .withMessageContaining("вне грамматики");
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new FormRoute(FormRouteKind.LIST, "nomenclature", null, "Full"))
            .withMessageContaining("variant");
    }

    private void assertRejected(String url, String reasonFragment) {
        FormRouteParseResult result = codec.parse(url);
        assertThat(result.isParsed())
            .as("адрес '%s' обязан быть отклонён", url)
            .isFalse();
        assertThat(result.rejection())
            .as("причина отказа для '%s'", url)
            .hasValueSatisfying(reason -> assertThat(reason).contains(reasonFragment));
    }
}
