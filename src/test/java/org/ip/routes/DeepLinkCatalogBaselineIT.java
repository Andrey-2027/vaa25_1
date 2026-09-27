package org.ip.routes;

import org.ip.config.DataInitializer;
import org.ip.model.Nomenclature;
import org.ip.model.PrdSpec;
import org.ipro.form.link.FormLinkResult;
import org.ipro.form.link.FormLinkService;
import org.ipro.form.link.FormRouteCatalog;
import org.ipro.form.link.FormRouteCodec;
import org.ipro.form.link.FormRouteKind;
import org.ipro.form.link.NotLinkableReason;
import org.ipro.form.link.PublishedFormRoute;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2.1: рантайм-половина забора публичных адресов. {@code DeepLinkRouteBaselineTest} читает
 * <b>исходники</b> (литералы {@code @Route}, вывод ключа из имени класса), а этот тест поднимает
 * настоящее приложение и сверяет с тем же baseline <b>построенный каталог</b>: настоящие
 * дескрипторы типов, настоящий реестр форм с регистрациями приложения и настоящие причины
 * отсутствия ссылки.
 *
 * <p>Зачем обе половины. Забор по исходникам ловит решение в коде (новый тип, новый named
 * вариант, переименование ключа), но ничего не знает о том, что реально попало в каталог:
 * например, тип мог получить {@code INTERNAL_STORE} из-за отсутствия {@code @EntityMetadata},
 * и файл об этом не скажет. Рантайм-забор ловит именно расхождение опубликованной поверхности
 * с обещанной, включая случай, когда ключ выведен иначе, чем считает источник.</p>
 */
@SpringBootTest
class DeepLinkCatalogBaselineIT {

    private static final Path BASELINE = Path.of("src/test/resources/routes/deep-link-baseline.txt");

    @MockitoBean
    private DataInitializer dataInitializer;

    @Autowired
    private FormRouteCatalog catalog;

    @Autowired
    private FormLinkService links;

    @Autowired
    private FormRouteCodec codec;

    @Test
    void runtimeCatalogPublishesExactlyTheBaselineAliasesAndTypes() {
        DeepLinkBaseline baseline = DeepLinkBaseline.read(BASELINE);

        Map<String, String> fromBaseline = new LinkedHashMap<>();
        for (DeepLinkBaseline.Alias alias : baseline.aliases()) {
            fromBaseline.put(alias.alias(), alias.entityClass());
        }
        Map<String, String> fromCatalog = new LinkedHashMap<>();
        for (PublishedFormRoute route : catalog.all()) {
            fromCatalog.put(route.entityKey(), route.entityClass().getName());
        }

        assertThat(fromCatalog)
            .as("опубликованная поверхность — это обещание совместимости: расхождение означает,"
                + " что адрес появился или исчез без решения")
            .isEqualTo(fromBaseline);
        assertThat(catalog.size())
            .as("забор не должен быть вакуумным: baseline и каталог сверяются целиком")
            .isEqualTo(baseline.aliases().size());
    }

    @Test
    void runtimeVariantsMatchTheBaselineArticleByArticle() {
        DeepLinkBaseline baseline = DeepLinkBaseline.read(BASELINE);

        for (DeepLinkBaseline.Alias alias : baseline.aliases()) {
            PublishedFormRoute route = catalog.find(alias.alias()).orElseThrow();
            assertThat(route.variants(FormRouteKind.ITEM))
                .as("ITEM-варианты '%s' зарегистрированы приложением и являются публичными"
                    + " адресами: их набор обязан совпасть с baseline", alias.alias())
                .containsExactlyInAnyOrderElementsOf(alias.item());
            assertThat(route.variants(FormRouteKind.LIST))
                .as("LIST-варианты '%s' — тот же контракт, что для ITEM", alias.alias())
                .containsExactlyInAnyOrderElementsOf(alias.list());
        }
    }

    @Test
    void declaredListContextDecidesLinkabilityAndReachesTheLinkService() {
        DeepLinkBaseline baseline = DeepLinkBaseline.read(BASELINE);

        for (DeepLinkBaseline.Alias alias : baseline.aliases()) {
            PublishedFormRoute route = catalog.find(alias.alias()).orElseThrow();
            if (alias.requiredContext().isEmpty()) {
                assertThat(route.notLinkable(FormRouteKind.LIST, null))
                    .as("список '%s' без обязательного контекста обязан быть линкабельным",
                        alias.alias())
                    .isEmpty();
                assertThat(links.linkToList(route.entityClass()).isLinkable())
                    .as("ссылка на список '%s' строится, если каталог не назвал причину", alias.alias())
                    .isTrue();
            } else {
                assertThat(route.notLinkable(FormRouteKind.LIST, null))
                    .as("объявленный обязательный контекст '%s' — причина, а не молчаливая выдача"
                        + " адреса с другим контекстом", alias.alias())
                    .contains(NotLinkableReason.REQUIRED_CONTEXT);
                assertThat(links.linkToList(route.entityClass()).notLinkableReason())
                    .contains(NotLinkableReason.REQUIRED_CONTEXT);
            }
        }
    }

    @Test
    void generatedLinkForAPilotTypeParsesBackIntoTheSameRoute() {
        FormLinkResult record = links.linkToRecord(Nomenclature.class, 42L);

        assertThat(record.linkPath()).as("пилот E2: изменяемый справочник линкабелен").isPresent();
        assertThat(codec.parse(record.linkPath().orElseThrow()).parsedRoute())
            .as("канонический адрес обязан разбираться обратно в тот же маршрут")
            .contains(record.linkedRoute().orElseThrow());
    }

    @Test
    void namedItemVariantOfThePilotDocumentIsItsOwnAddress() {
        FormLinkResult variant = links.linkToRecord(PrdSpec.class, 73L, "materials-only");

        assertThat(variant.linkPath()).contains("/records/prd-spec/73?variant=materials-only");
        assertThat(links.linkToRecord(PrdSpec.class, 73L).linkPath())
            .as("default-вариант не несёт параметра: каноническая форма одна")
            .contains("/records/prd-spec/73");
    }
}
