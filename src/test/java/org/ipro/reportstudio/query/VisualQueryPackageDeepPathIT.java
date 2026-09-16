package org.ipro.reportstudio.query;

import jakarta.persistence.EntityManagerFactory;
import org.ip.Application;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.ipro.rls.config.RlsPersistenceAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.test.context.ContextConfiguration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-trip пакета 1С-стиля: два CTE «group by спецификация + count строк»
 * и итоговый запрос с left join к обоим CTE. Глубокие пути (m.prdSpec.id) и
 * count(алиас) должны восстанавливаться и компилироваться без потери этапов.
 */
@DataJpaTest
// org.ip объявлен в Application#@EnableJpaRepositories (иначе дублирование бобов репозиториев в срезе)
@ImportAutoConfiguration(RlsPersistenceAutoConfiguration.class)
@ContextConfiguration(classes = Application.class)
class VisualQueryPackageDeepPathIT {
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired jakarta.persistence.EntityManager entityManager;

    @Test
    void parsesGroupedCountPackageWithDeepPathsAndKeepsAllStages() {
        var catalog = new QueryBuilderMetadataCatalog(entityManagerFactory,
                new org.ipro.metadata.MetadataResolver(),
                new org.ipro.rls.RlsReadGate(null, null) {
                    @Override public boolean canRead(Class<?> type, String user) { return true; }
                },
                () -> "smoke");
        String jpql = """
                with
                tmp1 as (
                    select m.prdSpec.id as specId,
                           count(m)      as cntMtr
                    from PrdSpecMtr m
                    group by m.prdSpec.id
                ),
                tmp2 as (
                    select o.prdSpec.id as specId,
                           count(o)      as cntOper
                    from PrdSpecOper o
                    group by o.prdSpec.id
                )
                select s.id           as id,
                       s.journal      as journal,
                       s.nomenclature as nomenclature,
                       t1.cntMtr      as cntMtr,
                       t2.cntOper     as cntOper
                from PrdSpec s
                left join tmp1 t1 on t1.specId = s.id
                left join tmp2 t2 on t2.specId = s.id
                """;

        var parsed = new VisualQueryTextParser(catalog).parsePackage(jpql);
        assertThat(parsed.warnings()).isEmpty();

        // Все три этапа сохранились: конструктор показывает tmp1, tmp2 и main.
        assertThat(parsed.queryPackage()).isNotNull();
        assertThat(parsed.queryPackage().ctes()).extracting(VisualQueryPackage.Cte::name)
                .containsExactly("tmp1", "tmp2");
        assertThat(parsed.queryPackage().main().entityName()).isEqualTo("PrdSpec");

        // CTE: глубокий путь сохранён, count(алиас) превращён в COUNT_ROWS.
        var tmp1 = parsed.queryPackage().ctes().get(0).definition();
        assertThat(tmp1.entityName()).isEqualTo("PrdSpecMtr");
        assertThat(tmp1.selectFields()).extracting(VisualQueryDefinition.SelectField::path)
                .containsExactly("m.prdSpec.id");
        assertThat(tmp1.aggregates()).extracting(VisualQueryDefinition.Aggregate::function)
                .containsExactly("COUNT_ROWS");
        assertThat(tmp1.groupBy()).containsExactly("m.prdSpec.id");

        var tmp2 = parsed.queryPackage().ctes().get(1).definition();
        assertThat(tmp2.entityName()).isEqualTo("PrdSpecOper");
        assertThat(tmp2.selectFields()).extracting(VisualQueryDefinition.SelectField::path)
                .containsExactly("o.prdSpec.id");

        // Main: независимые JOIN к виртуальным CTE восстановлены.
        assertThat(parsed.queryPackage().main().joins())
                .extracting(VisualQueryDefinition.Join::sourcePath)
                .containsExactly("tmp1", "tmp2");

        // Компиляция пакета в текст без потери этапов и JOIN'ов.
        var compiled = VisualQueryCompiler.compile(parsed.queryPackage(), catalog);
        assertThat(compiled.jpql()).startsWith("with tmp1 as (")
                .contains("m.prdSpec.id as specId")
                .contains("count(m.id) as cntMtr")
                .contains("o.prdSpec.id as specId")
                .contains("left join tmp1 t1 on t1.specId = s.id")
                .contains("left join tmp2 t2 on t2.specId = s.id")
                .contains("select s.id as id, s.journal as journal");

        // Колонки CTE типизированы для совместимости ON (specId → Long).
        assertThat(compiled.fields()).extracting(org.ipro.reportstudio.data.QueryField::name)
                .containsExactly("id", "journal", "nomenclature", "cntMtr", "cntOper");
    }

    @Test
    void executesCompiledPackageAgainstHibernate() {
        var catalog = new QueryBuilderMetadataCatalog(entityManagerFactory,
                new org.ipro.metadata.MetadataResolver(),
                new org.ipro.rls.RlsReadGate(null, null) {
                    @Override public boolean canRead(Class<?> type, String user) { return true; }
                },
                () -> "smoke");
        String jpql = """
                with
                tmp1 as (
                    select m.prdSpec.id as specId,
                           count(m)      as cntMtr
                    from PrdSpecMtr m
                    group by m.prdSpec.id
                ),
                tmp2 as (
                    select o.prdSpec.id as specId,
                           count(o)      as cntOper
                    from PrdSpecOper o
                    group by o.prdSpec.id
                )
                select s.id           as id,
                       s.journal      as journal,
                       s.nomenclature as nomenclature,
                       t1.cntMtr      as cntMtr,
                       t2.cntOper     as cntOper
                from PrdSpec s
                left join tmp1 t1 on t1.specId = s.id
                left join tmp2 t2 on t2.specId = s.id
                """;

        var parsed = new VisualQueryTextParser(catalog).parsePackage(jpql);
        assertThat(parsed.queryPackage()).isNotNull();
        var compiled = VisualQueryCompiler.compile(parsed.queryPackage(), catalog);

        // Данные: одна спецификация с одним компонентом и одной операцией.
        var unit = new org.ip.model.UnitOfMeasurement();
        unit.setShortCode("PC"); unit.setCode("PC"); unit.setName("штука");
        entityManager.persist(unit);
        var nomenclature = new org.ip.model.Nomenclature("N-1", "Деталь", unit);
        entityManager.persist(nomenclature);
        var journal = new org.ip.model.Journal();
        journal.setCode("J-1"); journal.setName("Производство");
        entityManager.persist(journal);
        var spec = new org.ip.model.PrdSpec();
        spec.setJournal(journal); spec.setNomenclature(nomenclature); spec.setCodeSpec("SP-1");
        entityManager.persist(spec);
        var mtr = new org.ip.model.PrdSpecMtr();
        mtr.setPrdSpec(spec); mtr.setTypeMtr(1);
        entityManager.persist(mtr);
        var oper = new org.ip.model.PrdSpecOper();
        oper.setPrdSpec(spec);
        entityManager.persist(oper);
        entityManager.flush();
        entityManager.clear();

        var query = entityManager.createQuery(compiled.jpql());
        compiled.bindings().forEach(query::setParameter);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();

        assertThat(rows).hasSize(1);
        Object[] row = rows.get(0);
        assertThat(row[0]).isEqualTo(spec.getId());
        // count(m)/count(o) по спецификации: по одной строке компонентов и операций.
        assertThat(((Number) row[3]).longValue()).isEqualTo(1L);
        assertThat(((Number) row[4]).longValue()).isEqualTo(1L);
    }
}
