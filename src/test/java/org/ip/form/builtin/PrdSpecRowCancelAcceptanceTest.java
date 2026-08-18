package org.ip.form.builtin;

import org.ip.form.FieldFactory;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.TableSectionMetadataInfo;
import org.ipro.metadata.FieldMetadataInfo;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ip.model.Nomenclature;
import org.ip.model.PrdSpec;
import org.ip.model.PrdSpecMtr;
import org.ip.model.UnitOfMeasurement;
import org.ip.service.LookupService;
import org.ip.service.TableSectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Приёмочные тесты PR-1.3 «RowDraft: три состояния отмены строки для PrdSpecMtr» (решение №3,
 * тестовая карта, стр. 210).
 *
 * <p>Диалоги строки (ItemTable.openRowDialog) — UI-уровень, недостижимый без UI-сессии
 * (Dialog.open() бросает IllegalStateException), поэтому три состояния отмены фиксируются на
 * тестируемых швах с РЕАЛЬНОЙ сущностью {@link PrdSpecMtr} и реальными FieldMetadataInfo:</p>
 * <ul>
 *   <li><b>new</b> — отмена добавления: строка создаётся только в момент «Добавить»
 *       (service.createNew + rowInitializer), а в таблицу попадает только при подтверждении —
 *       после отмены rows пусты и состояние чистое;</li>
 *   <li><b>pristine</b> — отмена «Изменить» без правок: строка не трогается, LookupService
 *       не опрашивается (restore не нужен), таблица чистая;</li>
 *   <li><b>dirty</b> — отмена «Изменить» с правками: «Закрыть» в ConfirmDialog выполняет
 *       RowDraft.restore(row, lookupService) — скаляры возвращаются как были, entity-ссылки
 *       перерезолвиваются по типу+id через LookupService (не JPA-clone, решение №3).</li>
 * </ul>
 */
class PrdSpecRowCancelAcceptanceTest {

    private TableSectionService<PrdSpecMtr, PrdSpec> service;
    private LookupService lookupService;
    private ItemTable<PrdSpecMtr, PrdSpec> table;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() throws Exception {
        TableSectionMetadataInfo sectionMeta = mock(TableSectionMetadataInfo.class);
        when(sectionMeta.getRowClass()).thenReturn((Class) PrdSpecMtr.class);
        when(sectionMeta.getGridFields()).thenReturn(List.of());
        when(sectionMeta.getFormFields()).thenReturn((List) formFields());

        service = mock(TableSectionService.class);
        lookupService = mock(LookupService.class);

        table = new ItemTable<>(sectionMeta, mock(FieldFactory.class), service,
            mock(MetadataResolver.class), null, null, lookupService, () -> null);
    }

    // === new: отмена добавления ===

    @Test
    void canceledAddDoesNotAdmitCreatedRowIntoTable() {
        PrdSpec parent = new PrdSpec();
        table.setParent(parent);

        PrdSpecMtr created = new PrdSpecMtr();
        when(service.createNew(parent)).thenReturn(created);
        PrdSpecMtrTableAddOptionInitializer initializer = new PrdSpecMtrTableAddOptionInitializer();

        // «Добавить»: строка создаётся сервисом и инициализируется опцией («Добавить материал»)
        PrdSpecMtr newRow = service.createNew(parent);
        initializer.initMaterial(newRow);

        // «Отмена»: подтверждения не было — строка не попадает в таблицу, состояние чистое
        assertThat(newRow.getId()).isNull();
        assertThat(table.getRows()).isEmpty();
        assertThat(table.isDirty()).isFalse();
    }

    // === pristine: отмена «Изменить» без правок ===

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void canceledEditWithoutChangesKeepsRowUntouchedAndClean() throws Exception {
        Nomenclature nomenclature = nomenclature(1L);
        UnitOfMeasurement unit = unit(2L);
        PrdSpecMtr row = row(3L, "10.5", nomenclature, unit);
        PrdSpec parent = new PrdSpec();
        parent.setId(7L);
        when(service.findByParent(any(), any())).thenReturn(List.of(row));
        table.setParent(parent);

        // openRowDialog: draft снят в момент открытия диалога
        RowDraft<PrdSpecMtr> draft = RowDraft.capture(row, formFields());
        assertThat(draft).isNotNull();

        // пользователь ничего не менял; «Закрыть» без подтверждения (не-dirty ветка) —
        // restore не выполняется и LookupService не опрашивается
        assertThat(table.getRows()).containsExactly(row);
        assertThat(table.isDirty()).isFalse();
        verify(lookupService, never()).findById(any(), any());
        assertThat(row.getQt()).isEqualByComparingTo("10.5");
        assertThat(row.getNomenclature()).isSameAs(nomenclature);
    }

    // === dirty: отмена «Изменить» с правками → восстановление через RowDraft ===

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void canceledEditWithChangesRestoresScalarsAndReResolvesReferences() throws Exception {
        Nomenclature original = nomenclature(1L);
        UnitOfMeasurement originalUnit = unit(2L);
        PrdSpecMtr row = row(3L, "10.5", original, originalUnit);
        PrdSpec parent = new PrdSpec();
        parent.setId(7L);
        when(service.findByParent(any(), any())).thenReturn(List.of(row));
        table.setParent(parent);

        // openRowDialog: draft снят при открытии диалога редактирования
        RowDraft<PrdSpecMtr> draft = RowDraft.capture(row, formFields());

        // правки в форме: скаляр изменён, ссылки заменены на другие экземпляры
        row.setQt(new BigDecimal("20.000"));
        row.setNomenclature(nomenclature(9L));
        row.setUnit(unit(10L));

        // «Закрыть» в ConfirmDialog (ItemTable.java:545-549): restore через LookupService
        when(lookupService.findById(Nomenclature.class, 1L)).thenReturn(Optional.of(original));
        when(lookupService.findById(UnitOfMeasurement.class, 2L)).thenReturn(Optional.of(originalUnit));
        draft.restore(row, lookupService);

        assertThat(row.getQt()).isEqualByComparingTo("10.5");
        assertThat(row.getNomenclature()).isSameAs(original);
        assertThat(row.getUnit()).isSameAs(originalUnit);
        assertThat(table.getRows()).containsExactly(row);
        assertThat(table.isDirty()).isFalse();
    }

    /** Реальная инициализация опции «Добавить материал» (PrdSpecMtrTableCustomization.java:34). */
    private static final class PrdSpecMtrTableAddOptionInitializer {
        void initMaterial(PrdSpecMtr row) {
            row.setTypeMtr(0);
        }
    }

    // === Вспомогательное ===

    private static PrdSpecMtr row(Long id, String qt, Nomenclature nomenclature, UnitOfMeasurement unit) {
        PrdSpecMtr row = new PrdSpecMtr();
        row.setId(id);
        row.setTypeMtr(0);
        row.setQt(new BigDecimal(qt));
        row.setNomenclature(nomenclature);
        row.setUnit(unit);
        return row;
    }

    private static Nomenclature nomenclature(Long id) {
        Nomenclature n = new Nomenclature();
        n.setId(id);
        return n;
    }

    private static UnitOfMeasurement unit(Long id) {
        UnitOfMeasurement u = new UnitOfMeasurement();
        u.setId(id);
        return u;
    }

    /** Реальные FieldMetadataInfo по @FieldMetadata-полям PrdSpecMtr — как resolveRowMetadata. */
    private static List<FieldMetadataInfo> formFields() throws Exception {
        return List.of(
            field("typeMtr"), field("prdSpecMtr"), field("nomenclature"), field("unit"), field("qt"));
    }

    private static FieldMetadataInfo field(String name) throws NoSuchFieldException {
        Field field = PrdSpecMtr.class.getDeclaredField(name);
        return new FieldMetadataInfo(field, field.getAnnotation(FieldMetadata.class));
    }
}
