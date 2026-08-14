package org.ipro.reportstudio.query;

import jakarta.persistence.EntityManagerFactory;
import org.ip.Application;
import org.ip.model.Journal;
import org.ip.model.PrdSpec;
import org.ip.model.ReceivingDocument;
import org.ipro.reportstudio.data.QueryField;
import org.ipro.reportstudio.query.sqm.SqmQuerySemanticAnalyzer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Семантический анализатор JPQL (Фаза 2, query-слой) против настоящего
 * Hibernate 7 SQM: корни/джойны/неявные пути/подзапросы, параметры,
 * колонки верхнего SELECT, отказ для не-SELECT и синтаксических ошибок.
 */
@DataJpaTest
@ContextConfiguration(classes = Application.class)
class SqmQuerySemanticAnalyzerTest {

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private QuerySemanticAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new SqmQuerySemanticAnalyzer(entityManagerFactory);
    }

    @Test
    void simpleSelectHasRootEntityAndFields() {
        Analysis analysis = analyzer.analyze(
            "select j.code, j.name from Journal j");

        assertThat(analysis.valid()).isTrue();
        assertThat(entityNames(analysis)).containsExactly("org.ip.model.Journal");
        assertThat(analysis.entities().get(0).path()).isEqualTo("j");
        assertThat(analysis.entities().get(0).inSubquery()).isFalse();

        assertThat(fieldNames(analysis)).containsExactly("j.code", "j.name");
        assertThat(analysis.selectFields().get(0).javaType()).isEqualTo(String.class);
        assertThat(analysis.selectFields().get(0).expression()).isEqualTo("j.code");
    }

    @Test
    void explicitJoinCollectsBothEntities() {
        Analysis analysis = analyzer.analyze(
            "select d.id from ReceivingDocument d join d.journal j");

        assertThat(analysis.valid()).isTrue();
        assertThat(entityNames(analysis)).containsExactlyInAnyOrder(
            "org.ip.model.ReceivingDocument", "org.ip.model.Journal");
        assertThat(analysis.entities()).anySatisfy(e ->
            assertThat(e.entityName()).isEqualTo("org.ip.model.Journal"));
        assertThat(analysis.entities()).anySatisfy(e ->
            assertThat(e.entityName()).isEqualTo("org.ip.model.ReceivingDocument"));
    }

    @Test
    void implicitJoinPathCollectsIntermediateEntity() {
        Analysis analysis = analyzer.analyze(
            "select s.codeSpec from PrdSpec s where s.journal.code = 'A'");

        assertThat(analysis.valid()).isTrue();
        // неявный путь s.journal.code раскрывается в джойн на Journal
        assertThat(entityNames(analysis)).containsExactlyInAnyOrder(
            "org.ip.model.PrdSpec", "org.ip.model.Journal");
    }

    @Test
    void parametersAreCollected() {
        Analysis analysis = analyzer.analyze(
            "select j.code from Journal j where j.code = :code and j.name like :nameLike");

        assertThat(analysis.valid()).isTrue();
        assertThat(analysis.parameters()).containsExactly("code", "nameLike");
    }

    @Test
    void subqueryMarksEntitiesAsInSubquery() {
        Analysis analysis = analyzer.analyze(
            "select j.code from Journal j "
                + "where exists (select s.id from PrdSpec s where s.journal = j)");

        assertThat(analysis.valid()).isTrue();
        assertThat(entityNames(analysis)).containsExactlyInAnyOrder(
            "org.ip.model.Journal", "org.ip.model.PrdSpec");
        assertThat(analysis.entities()).anySatisfy(e -> {
            assertThat(e.entityName()).isEqualTo("org.ip.model.PrdSpec");
            assertThat(e.inSubquery()).isTrue();
        });
        assertThat(analysis.entities()).anySatisfy(e -> {
            assertThat(e.entityName()).isEqualTo("org.ip.model.Journal");
            assertThat(e.inSubquery()).isFalse();
        });
    }

    @Test
    void entitySelectionHasEntityJavaType() {
        Analysis analysis = analyzer.analyze(
            "select s.journal from PrdSpec s");

        assertThat(analysis.valid()).isTrue();
        QueryField field = analysis.selectFields().get(0);
        assertThat(field.javaType()).isEqualTo(Journal.class);
        assertThat(field.expression()).isEqualTo("s.journal");
    }

    @Test
    void aggregateFunctionHasAliasAndLongType() {
        Analysis analysis = analyzer.analyze(
            "select count(s.id) as cnt from PrdSpec s");

        assertThat(analysis.valid()).isTrue();
        QueryField field = analysis.selectFields().get(0);
        assertThat(field.name()).isEqualTo("cnt");
        assertThat(field.javaType()).isEqualTo(Long.class);
    }

    @Test
    void nonSelectStatementIsRejected() {
        Analysis analysis = analyzer.analyze(
            "update Journal j set j.name = 'x'");

        assertThat(analysis.valid()).isFalse();
        assertThat(analysis.failures().get(0)).contains("только SELECT");
    }

    @Test
    void deleteStatementIsRejected() {
        Analysis analysis = analyzer.analyze(
            "delete from Journal j where j.id = 1");

        assertThat(analysis.valid()).isFalse();
        assertThat(analysis.failures().get(0)).contains("только SELECT");
    }

    @Test
    void syntacticallyBrokenQueryIsRejected() {
        Analysis analysis = analyzer.analyze("select from");

        assertThat(analysis.valid()).isFalse();
        assertThat(analysis.failures().get(0)).contains("Не удалось разобрать");
    }

    @Test
    void blankQueryIsRejected() {
        Analysis analysis = analyzer.analyze("   ");

        assertThat(analysis.valid()).isFalse();
        assertThat(analysis.failures().get(0)).contains("пуст");
    }

    @Test
    void entityNameIsHibernateQualifiedName() {
        Analysis analysis = analyzer.analyze("select j from Journal j");

        assertThat(analysis.valid()).isTrue();
        assertThat(entityNames(analysis)).containsExactly(Journal.class.getName());
        QueryField field = analysis.selectFields().get(0);
        assertThat(field.javaType()).isEqualTo(Journal.class);
        assertThat(field.name()).isEqualTo("j");
    }

    @Test
    void distinctGroupingAndOrderingDoNotBreakAnalysis() {
        Analysis analysis = analyzer.analyze(
            "select distinct s.journal.code as journalCode, count(s.id) as cnt "
                + "from PrdSpec s group by s.journal.code order by cnt desc");

        assertThat(analysis.valid()).isTrue();
        assertThat(fieldNames(analysis)).containsExactly("journalCode", "cnt");
        assertThat(entityNames(analysis)).contains("org.ip.model.Journal", "org.ip.model.PrdSpec");
    }

    private List<String> entityNames(Analysis analysis) {
        return analysis.entities().stream()
            .map(EntityUsage::entityName)
            .distinct()
            .collect(Collectors.toList());
    }

    private List<String> fieldNames(Analysis analysis) {
        return analysis.selectFields().stream()
            .map(QueryField::name)
            .collect(Collectors.toList());
    }
}