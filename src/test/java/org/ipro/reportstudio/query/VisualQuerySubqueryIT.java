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
 * Полный цикл подзапроса на реальных сущностях: текст с IN(подзапрос) →
 * разбор в визуальное определение → компиляция → выполнение на H2.
 *
 * <p>Собственный ключ Spring-контекста (random-order изоляция, как у
 * ReportJpqlPreviewControllerIT): слайс делит кэш с другими JPA-тестами, и
 * чужое закрытие общего контекста роняло ленивое создание auditing-бинов
 * {@code jpaAuditingHandler/jpaMappingContext} первым persist'ом.</p>
 */
@DataJpaTest(properties = "ip.test.isolation=visual-query-subquery")
// org.ip объявлен в Application#@EnableJpaRepositories (иначе дублирование бобов репозиториев в срезе)
@ImportAutoConfiguration(RlsPersistenceAutoConfiguration.class)
@ContextConfiguration(classes = Application.class)
class VisualQuerySubqueryIT {
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired jakarta.persistence.EntityManager entityManager;

    @Test
    void parsesCompilesAndExecutesInSubquery() {
        var catalog = new QueryBuilderMetadataCatalog(entityManagerFactory,
                new org.ipro.metadata.MetadataResolver(),
                new org.ipro.rls.RlsReadGate(null, null) {
                    @Override public boolean canRead(Class<?> type, String user) { return true; }
                },
                () -> "smoke");
        String jpql = "select s.id as id, s.codeSpec as codeSpec from PrdSpec s "
                + "where s.id in (select m.prdSpec.id from PrdSpecMtr m)";

        var parsed = new VisualQueryTextParser(catalog).parse(jpql, null);
        assertThat(parsed.warnings()).as(String.join("; ", parsed.warnings())).isEmpty();
        assertThat(parsed.definition()).isNotNull();
        assertThat(parsed.definition().subqueries()).hasSize(1);
        assertThat(parsed.definition().subqueries().get(0).definition().entityName()).isEqualTo("PrdSpecMtr");

        var compiled = VisualQueryCompiler.compile(parsed.definition(), catalog);
        assertThat(compiled.jpql()).contains("s.id in (select m.prdSpec.id");

        // Данные: две спецификации; компоненты только у первой.
        var unit = new org.ip.model.UnitOfMeasurement();
        unit.setShortCode("PC"); unit.setCode("PC"); unit.setName("штука");
        entityManager.persist(unit);
        var nomenclature = new org.ip.model.Nomenclature("N-1", "Деталь", unit);
        entityManager.persist(nomenclature);
        var journal = new org.ip.model.Journal();
        journal.setCode("J-1"); journal.setName("Производство");
        entityManager.persist(journal);
        var withComponents = new org.ip.model.PrdSpec();
        withComponents.setJournal(journal); withComponents.setNomenclature(nomenclature); withComponents.setCodeSpec("SP-1");
        entityManager.persist(withComponents);
        var withoutComponents = new org.ip.model.PrdSpec();
        withoutComponents.setJournal(journal); withoutComponents.setNomenclature(nomenclature); withoutComponents.setCodeSpec("SP-2");
        entityManager.persist(withoutComponents);
        var mtr = new org.ip.model.PrdSpecMtr();
        mtr.setPrdSpec(withComponents); mtr.setTypeMtr(1);
        entityManager.persist(mtr);
        entityManager.flush();
        entityManager.clear();

        var query = entityManager.createQuery(compiled.jpql());
        compiled.bindings().forEach(query::setParameter);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();

        // Подзапрос отфильтровал: осталась только спецификация с компонентами.
        assertThat(rows).hasSize(1);
        assertThat(((Number) rows.get(0)[0]).longValue()).isEqualTo(withComponents.getId());
        assertThat(rows.get(0)[1]).isEqualTo("SP-1");
    }
}
