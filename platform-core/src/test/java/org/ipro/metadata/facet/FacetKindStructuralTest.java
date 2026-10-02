package org.ipro.metadata.facet;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Грани метаданных — reviewed-список: E3.1 и E3.2.0 добавляли структурные грани одну за другой, и
 * единственным следом каждой было изменение {@code .api}-baseline. Здесь разделение закреплено
 * явно — переопределяемой может быть только грань роли 3 (подпись, видимость, членство), всё
 * остальное описывает структуру типа, и новое имя обязано попасть в один из двух наборов осознанно,
 * а не по умолчанию.
 *
 * <p>Второе, что проверяется, — состав ключа у граней доступа: они занимают {@code fieldName} и
 * оставляют {@code variant} пустым. Это записано и в javadoc {@link FacetKey}, и здесь, потому что
 * следующее хранилище переопределений будет читать состав ключа из кода, а не из плана.</p>
 */
class FacetKindStructuralTest {

    /** Переопределяемые грани (роль 3, П5): подписи, видимость, членство в подсистемах. */
    private static final Set<FacetKind> OVERRIDABLE = Set.of(
        FacetKind.ENTITY_LIST_TITLE,
        FacetKind.ENTITY_ITEM_TITLE,
        FacetKind.ENTITY_SELECTION_TITLE,
        FacetKind.FIELD_LABEL,
        FacetKind.GRID_COLUMN_HEADER,
        FacetKind.CONTEXT_FILTER_LABEL,
        FacetKind.SUBSYSTEM_MEMBERSHIP);

    @Test
    void onlyReviewedFacetsAreOverridable() {
        assertThat(Arrays.stream(FacetKind.values()).filter(FacetKind::overridable).toList())
            .as("переопределяемых граней ровно семь: восьмая — решение, а не побочный эффект")
            .containsExactlyInAnyOrderElementsOf(OVERRIDABLE);
    }

    @Test
    void aspectFacetsAreStructuralBecauseTheyDescribeTheType() {
        assertThat(Set.of(
                FacetKind.ENTITY_KIND,
                FacetKind.ENTITY_EXPOSURE,
                FacetKind.ENTITY_KEY,
                FacetKind.LINKABILITY,
                FacetKind.ENTITY_LIFECYCLE_HANDLER,
                FacetKind.ENTITY_LIFECYCLE_HOOK,
                FacetKind.ACTION,
                FacetKind.ACTION_HANDLER,
                FacetKind.FETCH_PLAN,
                FacetKind.FETCH_PLAN_PATH,
                FacetKind.RLS_DIMENSION,
                FacetKind.RLS_VALUE_RULE,
                FacetKind.LOOKUP_TARGET))
            .as("аспекты описывают структуру типа: подписью их не переопределяют")
            .allSatisfy(kind -> assertThat(kind.overridable()).isFalse());
    }

    @Test
    void accessFacetsTakeTheFieldNameAndLeaveTheVariantEmpty() {
        FacetKey dimension = FacetKey.of(FacetKind.RLS_DIMENSION, Sample.class, "BRANCH");
        assertThat(dimension.fieldName()).isEqualTo("BRANCH");
        assertThat(dimension.variant()).as("измерение не зависит от варианта формы").isNull();

        FacetKey rule = FacetKey.of(FacetKind.RLS_VALUE_RULE, Sample.class, "BRANCH/branch.id");
        assertThat(rule.fieldName())
            .as("измерение входит в имя поля: BRANCH объявлен тремя типами с разными правилами")
            .isEqualTo("BRANCH/branch.id");
        assertThat(rule.variant()).isNull();
    }

    /** Носитель ключа в тесте: грани адресуют тип, а не его экземпляр. */
    private static final class Sample {
    }
}
