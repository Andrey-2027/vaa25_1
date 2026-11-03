package com.example.entitytypes.em5;

import com.example.entitytypes.model.*;
import com.example.entitytypes.model.Order;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.ipro.crud.MetadataDrivenAggregateSaveService;
import org.ipro.data.EntityDataAccess;
import org.ipro.events.EventSource;
import org.ipro.metadata.FieldMetadataInfo;
import org.ipro.metadata.MetadataResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EM5, second JVM, schema mode {@code validate}: the files were changed by entity-modify, schema.sql was run on
 * the database that the first JVM wrote, and the project was built. This class is compiled against the patched
 * model, so every setter it uses was written by the patcher.
 */
@SpringBootTest(properties = {"vaadin.productionMode=true", "consumer.demo-password=em5-test-only"})
@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
class Em5Verify {
    @Autowired EntityDataAccess data;
    @Autowired MetadataDrivenAggregateSaveService aggregates;
    @Autowired MetadataResolver metadata;
    @Autowired JdbcTemplate jdbc;

    static void login(String user) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
            user, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @BeforeEach void writer() { login("writer"); }

    private CustomCatalog oldCatalog() {
        return data.list(CustomCatalog.class, (root, q, cb) -> cb.equal(root.get("externalKey"), "EM5-OLD"), PageRequest.of(0, 5)).getContent().getFirst();
    }
    private GeneratedItem oldItem() {
        return data.list(GeneratedItem.class, null, PageRequest.of(0, 5)).getContent().getFirst();
    }

    // The first test reads the rows before the others write to them.
    @Test @org.junit.jupiter.api.Order(1) void oldRowsAreIntactAndTheNewFieldsAreEmpty() {
        var catalog = oldCatalog();
        assertThat(catalog.getCaption()).isEqualTo("Старая подпись");
        assertThat(catalog.getAddress()).isNull();
        var item = oldItem();
        assertThat(item.getTitle()).isEqualTo("old title");
        assertThat(item.getNote()).isNull();
        assertThat(item.getQuantity()).isNull();
        assertThat(item.getActive()).isNull();
        assertThat(item.getCheckedAt()).isNull();
        assertThat(data.lookup(Product.class, "ET5-001", 10).getFirst().getPrice()).isNull();
        assertThat(data.list(ExtendedProduct.class, null, PageRequest.of(0, 5)).getContent().getFirst().getBaseProduct()).isNull();
        assertThat(data.list(Order.class, null, PageRequest.of(0, 5)).getContent()).allMatch(order -> order.getDueDate() == null);
    }

    @Test void newFieldsAreWrittenAndReadBackInANewContext() {
        var catalog = oldCatalog();
        catalog.setAddress("Москва, Тверская 1");
        data.update(CustomCatalog.class, catalog);
        assertThat(data.detail(CustomCatalog.class, catalog.getId()).orElseThrow().getAddress()).isEqualTo("Москва, Тверская 1");

        var product = data.lookup(Product.class, "ET5-001", 10).getFirst();
        product.setPrice(new BigDecimal("12.3456"));
        data.update(Product.class, product);
        assertThat(data.detail(Product.class, product.getId()).orElseThrow().getPrice()).isEqualByComparingTo("12.3456");

        var extended = data.list(ExtendedProduct.class, null, PageRequest.of(0, 5)).getContent().getFirst();
        extended.setBaseProduct(product);
        data.update(ExtendedProduct.class, extended);
        assertThat(data.detail(ExtendedProduct.class, extended.getId()).orElseThrow().getBaseProduct().getId()).isEqualTo(product.getId());

        var order = new Order();
        order.setDueDate(LocalDate.of(2026, 12, 31));
        var savedOrder = aggregates.save(order, List.of(), EventSource.UI).aggregate();
        assertThat(data.detail(Order.class, savedOrder.getId()).orElseThrow().getDueDate()).isEqualTo(LocalDate.of(2026, 12, 31));

        var item = oldItem();
        item.setNote("заметка");
        item.setQuantity(9_000_000_000L);
        item.setActive(Boolean.TRUE);
        item.setCheckedAt(LocalDateTime.of(2026, 11, 3, 12, 34, 56, 789_000_000));
        data.update(GeneratedItem.class, item);
        var read = data.detail(GeneratedItem.class, item.getId()).orElseThrow();
        assertThat(read.getNote()).isEqualTo("заметка");
        assertThat(read.getQuantity()).isEqualTo(9_000_000_000L);
        assertThat(read.getActive()).isTrue();
        assertThat(read.getCheckedAt()).isEqualTo(LocalDateTime.of(2026, 11, 3, 12, 34, 56, 789_000_000));
    }

    @Test void theFormAndTheListShowTheNewFieldsAndTheNewLabel() {
        for (var type : List.<Class<?>>of(CustomCatalog.class, GeneratedItem.class)) {
            var info = metadata.resolve(type);
            String added = type == CustomCatalog.class ? "address" : "note";
            assertThat(info.getFormFields()).extracting(FieldMetadataInfo::getName).contains(added);
            assertThat(info.getListColumnPaths()).extracting(path -> path.getKey()).contains(added);
        }
        assertThat(metadata.resolve(Product.class).getFormFields()).extracting(FieldMetadataInfo::getName).contains("price");
        assertThat(metadata.resolve(Order.class).getFormFields()).extracting(FieldMetadataInfo::getName).contains("dueDate");
        assertThat(metadata.resolve(ExtendedProduct.class).getFormFields()).extracting(FieldMetadataInfo::getName).contains("baseProduct");
        var caption = metadata.resolve(CustomCatalog.class).getFormFields().stream().filter(f -> f.getName().equals("caption")).findFirst().orElseThrow();
        assertThat(caption.getLabel()).isEqualTo("Подпись (новая)");
    }

    private record Column(String type, int size, int digits, String nullable) {}

    private Column column(String table, String column) {
        return jdbc.execute((ConnectionCallback<Column>) connection -> {
            try (var rs = connection.getMetaData().getColumns(null, null, table.toUpperCase(), column.toUpperCase())) {
                assertThat(rs.next()).as(table + "." + column + " exists").isTrue();
                return new Column(rs.getString("TYPE_NAME"), rs.getInt("COLUMN_SIZE"), rs.getInt("DECIMAL_DIGITS"), rs.getString("IS_NULLABLE"));
            }
        });
    }

    @Test void databaseColumnsHaveTheLengthPrecisionAndNullabilityThePlanProposed() {
        assertThat(column("custom_catalog", "address")).isEqualTo(new Column("CHARACTER VARYING", 200, 0, "YES"));
        var price = column("product", "price");
        assertThat(price.type()).isEqualTo("NUMERIC");
        assertThat(price.size()).isEqualTo(15);
        assertThat(price.digits()).isEqualTo(4);
        assertThat(price.nullable()).isEqualTo("YES");
        assertThat(column("et5_order", "due_date").type()).isEqualTo("DATE");
        assertThat(column("et5_order", "due_date").nullable()).isEqualTo("YES");
        assertThat(column("extended_product", "base_product_id").type()).isEqualTo("BIGINT");
        assertThat(column("generated_item", "note").size()).isEqualTo(150);
        assertThat(column("generated_item", "quantity").type()).isEqualTo("BIGINT");
        assertThat(column("generated_item", "active").type()).isEqualTo("BOOLEAN");
        var stamp = column("generated_item", "checked_at");
        assertThat(stamp.type()).startsWith("TIMESTAMP");
        assertThat(stamp.digits()).isEqualTo(6);
        assertThat(stamp.nullable()).isEqualTo("YES");
        assertThat(column("generated_item", "note").nullable()).isEqualTo("YES");
    }

    @Test void theForeignKeyExistsAndTheDatabaseRefusesAReferenceToNothing() {
        List<String> keys = jdbc.execute((ConnectionCallback<List<String>>) connection -> {
            var found = new java.util.ArrayList<String>();
            try (var rs = connection.getMetaData().getImportedKeys(null, null, "EXTENDED_PRODUCT")) {
                while (rs.next()) found.add(rs.getString("FKCOLUMN_NAME") + "->" + rs.getString("PKTABLE_NAME") + "." + rs.getString("PKCOLUMN_NAME"));
            }
            return found;
        });
        assertThat(keys).contains("BASE_PRODUCT_ID->PRODUCT.ID");
        Long id = data.list(ExtendedProduct.class, null, PageRequest.of(0, 5)).getContent().getFirst().getId();
        assertThatThrownBy(() -> jdbc.update("update extended_product set base_product_id = ? where id = ?", 987_654_321L, id))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void rowLevelScopeStillFiltersOrdersByTeam() {
        login("writer");
        var order = new Order();
        order.setDueDate(LocalDate.of(2027, 1, 1));
        var writerOrder = aggregates.save(order, List.of(), EventSource.UI).aggregate();
        login("reader");
        assertThat(data.list(Order.class, null, PageRequest.of(0, 100))).isNotEmpty().allMatch(o -> o.getTeamId().equals(2L));
        assertThat(data.detail(Order.class, writerOrder.getId())).isEmpty();
    }
}
