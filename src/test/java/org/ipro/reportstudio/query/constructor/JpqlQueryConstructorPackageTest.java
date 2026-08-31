package org.ipro.reportstudio.query.constructor;

import org.ipro.reportstudio.query.VisualQueryDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JpqlQueryConstructorPackageTest {
    @Test
    void exposesStagesAndKeepsDefinitionsWhenSwitching() {
        var constructor = new JpqlQueryConstructor(QueryConstructorDraftTest.catalog(
                QueryConstructorDraftTest.product()), List.of());
        constructor.setDefinition(definition("Q6Product", "p"));
        var packageDefinition = new org.ipro.reportstudio.query.VisualQueryPackage(
                List.of(new org.ipro.reportstudio.query.VisualQueryPackage.Cte("tmp1",
                        definition("Q6Product", "p"))), definition("tmp1", "t"));

        constructor.setPackage(packageDefinition);

        assertThat(constructor.queryPackage()).isNotNull();
        assertThat(constructor.queryPackage().ctes()).extracting("name").containsExactly("tmp1");
        assertThat(constructor.definition().entityName()).isEqualTo("tmp1");
    }

    private static VisualQueryDefinition definition(String entity, String alias) {
        return new VisualQueryDefinition(VisualQueryDefinition.CURRENT_VERSION, entity, alias,
                List.of(new VisualQueryDefinition.SelectField(alias + ".code", "code")),
                List.of(), List.of(), List.of(), List.of(), null, null, List.of(), List.of());
    }
}
