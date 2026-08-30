package org.ipro.reportstudio.query.constructor;

import org.ipro.reportstudio.query.VisualQueryDefinition;
import org.ipro.reportstudio.query.VisualQueryExpression;
import org.ipro.reportstudio.query.VisualQueryExpressionText;
import org.ipro.reportstudio.query.VisualQueryTextParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.ipro.reportstudio.query.constructor.QueryConstructorDraftTest.catalog;
import static org.ipro.reportstudio.query.constructor.QueryConstructorDraftTest.product;

/**
 * Диалог создания/правки вычисляемого поля: валидация выражения обратным
 * парсером, автопсевдоним, коллизии имён, режим правки.
 */
class ComputedFieldDialogTest {

    private record Ctx(QueryConstructorDraft draft, ComputedFieldDialog dialog,
                       AtomicReference<VisualQueryDefinition.Expression> saved) { }

    private static Ctx createDialog() {
        var draft = new QueryConstructorDraft();
        draft.setCatalog(catalog(product()));
        draft.setRoot(draft.entity("Product"));
        var saved = new AtomicReference<VisualQueryDefinition.Expression>();
        var dialog = new ComputedFieldDialog(draft, new VisualQueryTextParser(draft.catalog()), null, saved::set);
        return new Ctx(draft, dialog, saved);
    }

    @Test
    void savesValidArithmeticWithAutoAlias() {
        var ctx = createDialog();
        ctx.dialog().expressionField().setValue("product.amount * 2 + 1");
        assertThat(ctx.dialog().save()).isTrue();

        var saved = ctx.saved().get();
        assertThat(saved).isNotNull();
        assertThat(saved.resultName()).isEqualTo("field");
        assertThat(ctx.draft().expressions()).containsExactly(saved);
        assertThat(VisualQueryExpressionText.render(saved.expression()))
                .isEqualTo("((product.amount * 2) + 1)");
    }

    @Test
    void savesCaseExpression() {
        var ctx = createDialog();
        ctx.dialog().expressionField().setValue(
                "case when product.amount > 10 then 'много' else 'мало' end");
        ctx.dialog().aliasField().setValue("level");
        assertThat(ctx.dialog().save()).isTrue();

        var saved = ctx.saved().get();
        assertThat(saved.resultName()).isEqualTo("level");
        var caseExpr = (VisualQueryExpression.Case) saved.expression();
        assertThat(caseExpr.branches()).singleElement().satisfies(branch ->
                assertThat(branch.condition())
                        .isEqualTo(new org.ipro.reportstudio.query.CaseCondition.Cmp(
                                "product.amount", org.ipro.reportstudio.query.CaseCondition.CmpOp.GT, 10.0)));
        assertThat(caseExpr.elseResult()).isEqualTo(new VisualQueryExpression.Literal("мало"));
    }

    @Test
    void invalidFunctionIsRejectedWithReason() {
        var ctx = createDialog();
        ctx.dialog().expressionField().setValue("evil(product.amount)");
        assertThat(ctx.dialog().save()).isFalse();
        assertThat(ctx.dialog().statusText()).contains("не разрешена");
        assertThat(ctx.draft().expressions()).isEmpty();
        assertThat(ctx.saved().get()).isNull();
    }

    @Test
    void unknownFieldIsRejected() {
        var ctx = createDialog();
        ctx.dialog().expressionField().setValue("product.missing * 2");
        assertThat(ctx.dialog().save()).isFalse();
        assertThat(ctx.dialog().statusText()).isNotBlank();
    }

    @Test
    void duplicateAliasGetsNumberedSuffix() {
        var ctx = createDialog();
        ctx.draft().addField(ctx.draft().entity("Product"), List.of(), "amount");
        ctx.dialog().expressionField().setValue("product.amount + 1");
        ctx.dialog().aliasField().setValue("amount");
        assertThat(ctx.dialog().save()).isTrue();
        // «amount» занят полем SELECT — вычисляемому полю достаётся «amount2»
        assertThat(ctx.saved().get().resultName()).isEqualTo("amount2");
    }

    @Test
    void editModePrefillsAndReplaces() {
        var draft = new QueryConstructorDraft();
        draft.setCatalog(catalog(product()));
        draft.setRoot(draft.entity("Product"));
        var old = draft.addExpression("flag", new VisualQueryExpression.Literal(1.0));
        var saved = new AtomicReference<VisualQueryDefinition.Expression>();
        var dialog = new ComputedFieldDialog(draft, new VisualQueryTextParser(draft.catalog()),
                old, saved::set);

        // префилл из существующего поля
        assertThat(dialog.aliasField().getValue()).isEqualTo("flag");
        assertThat(dialog.expressionField().getValue()).isEqualTo("1");

        dialog.expressionField().setValue("product.code");
        assertThat(dialog.save()).isTrue();

        assertThat(draft.expressions()).singleElement().satisfies(expr -> {
            assertThat(expr.resultName()).isEqualTo("flag");
            assertThat(expr.expression()).isEqualTo(new VisualQueryExpression.FieldRef("product.code"));
        });
        assertThat(draft.expressions()).doesNotContain(old);
        assertThat(saved.get()).isEqualTo(draft.expressions().get(0));
    }
}
