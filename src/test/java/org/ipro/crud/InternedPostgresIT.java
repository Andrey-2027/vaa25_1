package org.ipro.crud;

import jakarta.persistence.*;
import org.ip.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;

/** Explicit PostgreSQL gate, run only against the isolated database made by the ET8 runner. */
@DataJpaTest(properties = "spring.test.database.replace=none")
@org.springframework.test.context.ContextConfiguration(classes = org.ip.Application.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class InternedPostgresIT {
    @Autowired DataSource dataSource;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactionManager;
    private JdbcTemplate jdbc() { return new JdbcTemplate(dataSource); }

    /** The gate is PostgreSQL-only; the default H2 run must skip it instead of failing on dialect checks. */
    @BeforeEach
    void requiresPostgreSql() throws Exception {
        try (var connection = dataSource.getConnection()) {
            org.junit.jupiter.api.Assumptions.assumeTrue(
                "PostgreSQL".equals(connection.getMetaData().getDatabaseProductName()),
                "InternedPostgresIT runs only against the isolated PostgreSQL database (ET8 runner)");
        }
    }

    @Test void appliedConstraintsMatchExactKeysAndNullSemantics() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
        }
        for (Class<?> type : List.of(AttributeValue.class, SklNomOpa.class)) {
            var table = type.getAnnotation(Table.class);
            for (var key : InterningContracts.find(type).orElseThrow().keys()) {
                var unique = Arrays.stream(table.uniqueConstraints()).filter(c -> c.name().equals(key.constraint())).findFirst().orElseThrow();
                var columns = jdbc().queryForList("""
                    select a.attname from pg_constraint c
                    join pg_class t on t.oid=c.conrelid join pg_namespace n on n.oid=t.relnamespace
                    join pg_index i on i.indexrelid=c.conindid
                    cross join lateral unnest(c.conkey) k(attnum)
                    join pg_attribute a on a.attrelid=t.oid and a.attnum=k.attnum
                    where n.nspname=current_schema() and t.relname=? and c.conname=? and c.contype='u'
                      and i.indisunique and i.indisvalid and i.indpred is null and i.indexprs is null
                      and not i.indnullsnotdistinct and not c.condeferrable
                    """, String.class, table.name(), key.constraint());
                assertThat(columns).containsExactlyInAnyOrder(unique.columnNames());
            }
        }
        var required = jdbc().queryForList("""
            select table_name || '.' || column_name from information_schema.columns
            where table_schema=current_schema() and is_nullable='NO'
            """, String.class);
        assertThat(required).contains("attribute_value.attr_type_id", "skl_nom_opa.nomenclature_id", "skl_nom_opa.canonical")
            .doesNotContain("attribute_value.code_up", "attribute_value.ref_id");
    }

    private AttributeType type() {
        return type(AttributeValueType.STRING);
    }
    private AttributeType type(AttributeValueType valueType) {
        var type = new AttributeType("E" + UUID.randomUUID().toString().substring(0,12), "ET8", valueType);
        if (valueType == AttributeValueType.REF) type.setTargetDictionary(GroupNom.class.getName());
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> em.persist(type));
        return type;
    }
    private Nomenclature nomenclature() {
        return new TransactionTemplate(transactionManager).execute(status -> {
            String code = "E" + UUID.randomUUID().toString().substring(0,8);
            var unit = new UnitOfMeasurement(code, "ET8", code);
            em.persist(unit);
            var nom = new Nomenclature(code, "ET8", unit);
            em.persist(nom);
            return nom;
        });
    }
    private Optional<AttributeValue> findValue(AttributeValue key) {
        return jdbc().query("select id from attribute_value where attr_type_id=? and "
                + (key.getRefId() == null ? "code_up=?" : "ref_id=?"),
            (rs, row) -> {
                var value = new AttributeValue(key.getAttrType(), key.getCode(), key.getName(), key.getCodeUp(), key.getRefId());
                value.setId(rs.getLong(1)); return value;
            }, key.getAttrType().getId(), key.getRefId() == null ? key.getCodeUp() : key.getRefId()).stream().findFirst();
    }
    private AttributeValue insertValue(AttributeValue key) {
        Long id = jdbc().queryForObject("""
            insert into attribute_value(attr_type_id,code,name,code_up,ref_id,version)
            values(?,?,?,?,?,0) returning id
            """, Long.class, key.getAttrType().getId(),key.getCode(),key.getName(),key.getCodeUp(),key.getRefId());
        var row = new AttributeValue(key.getAttrType(),key.getCode(),key.getName(),key.getCodeUp(),key.getRefId());
        row.setId(id); return row;
    }
    private <T extends InternedEntity> List<T> race(Class<T> type, T key,
            Supplier<Optional<T>> finder, Supplier<T> creator) throws Exception {
        var bothAbsent = new CountDownLatch(2);
        var selects = new AtomicInteger();
        var support = new NaturalKeyCreateSupport(new DataSourceTransactionManager(dataSource));
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<T> call = () -> support.getOrCreate(type, key, () -> {
                var result = finder.get();
                if (selects.incrementAndGet() <= 2) {
                    assertThat(result).isEmpty();
                    bothAbsent.countDown();
                    try { if (!bothAbsent.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("race barrier timeout"); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                }
                return result;
            }, creator);
            var first = pool.submit(call); var second = pool.submit(call);
            var rows = List.of(first.get(30,TimeUnit.SECONDS), second.get(30,TimeUnit.SECONDS));
            // Both transactions saw absence: one INSERT really lost and reselected after rollback.
            assertThat(selects.get()).isGreaterThanOrEqualTo(3);
            return rows;
        }
    }
    @Test void scalarAndReferenceRacesReturnOneCommittedWinner() throws Exception {
        for (boolean reference : List.of(false,true)) {
            var type = type(reference ? AttributeValueType.REF : AttributeValueType.STRING);
            var key = new AttributeValue(type,"value","value",reference ? null : "VALUE",reference ? 91L : null);
            var rows = race(AttributeValue.class,key, () -> findValue(key), () -> insertValue(key));
            assertThat(rows.getFirst().getId()).isEqualTo(rows.getLast().getId());
            assertThat(jdbc().queryForObject("select count(*) from attribute_value where attr_type_id=?",Long.class,type.getId())).isEqualTo(1L);
        }
    }
    @Test void aggregateRaceCommitsOneHeaderAndOneComposition() throws Exception {
        var type=type(); var value=insertValue(new AttributeValue(type,"a","a","A",null)); var nom=nomenclature();
        var key=new SklNomOpa(nom,type.getId()+":"+value.getId(),"ET8");
        var rows=race(SklNomOpa.class,key,
            () -> jdbc().query("select id from skl_nom_opa where nomenclature_id=? and canonical=?", (rs,n) -> {
                var row=new SklNomOpa(nom,key.getCanonical(),"ET8"); row.setId(rs.getLong(1)); return row;
            },nom.getId(),key.getCanonical()).stream().findFirst(),
            () -> {
                Long id=jdbc().queryForObject("insert into skl_nom_opa(nomenclature_id,canonical,display_name,version) values(?,?,?,0) returning id",
                    Long.class,nom.getId(),key.getCanonical(),"ET8");
                jdbc().update("insert into skl_nom_opa_value(set_id,attr_type_id,attr_value_id,version) values(?,?,?,0)",id,type.getId(),value.getId());
                var row=new SklNomOpa(nom,key.getCanonical(),"ET8"); row.setId(id); return row;
            });
        assertThat(rows.getFirst().getId()).isEqualTo(rows.getLast().getId());
        assertThat(jdbc().queryForObject("select count(*) from skl_nom_opa where nomenclature_id=?",Long.class,nom.getId())).isEqualTo(1L);
        assertThat(jdbc().queryForObject("select count(*) from skl_nom_opa_value where set_id=?",Long.class,rows.getFirst().getId())).isEqualTo(1L);
    }
    @Test void failedAttemptRollsBackHeaderAndCompositionAndOuterRollbackDoesNotUndoRequiresNew() {
        var type=type(); var value=insertValue(new AttributeValue(type,"b","b","B",null)); var nom=nomenclature();
        var manager=new DataSourceTransactionManager(dataSource);
        var support=new NaturalKeyCreateSupport(manager);
        String canonical=type.getId()+":"+value.getId();
        assertThatThrownBy(() -> support.getOrCreate(SklNomOpa.class,new SklNomOpa(nom,canonical,""),Optional::empty,() -> {
            Long id=jdbc().queryForObject("insert into skl_nom_opa(nomenclature_id,canonical,version) values(?,?,0) returning id",Long.class,nom.getId(),canonical);
            jdbc().update("insert into skl_nom_opa_value(set_id,attr_type_id,attr_value_id,version) values(?,?,?,0)",id,type.getId(),value.getId());
            throw new IllegalStateException("fail after composition");
        })).hasMessageContaining("fail after composition");
        assertThat(jdbc().queryForObject("select count(*) from skl_nom_opa where nomenclature_id=?",Long.class,nom.getId())).isZero();
        assertThat(jdbc().queryForObject("select count(*) from skl_nom_opa_value where attr_type_id=?",Long.class,type.getId())).isZero();
        var key=new AttributeValue(type,"c","c","C",null);
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            support.getOrCreate(AttributeValue.class,key,() -> findValue(key),() -> insertValue(key));
            status.setRollbackOnly();
        });
        assertThat(findValue(key)).isPresent();
    }
}
