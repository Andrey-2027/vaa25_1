package org.ip.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;
import org.ipro.crud.BaseEntity;
import org.ipro.crud.InternedEntity;
import org.ipro.data.SearchFields;
import org.ipro.metadata.HasDisplayName;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.Lookup;

/**
 * Шапка набора значений атрибутов КСУ — immutable экземпляр комбинации «тип → значение»
 * для одной номенклатуры (аналог {@code SklOpa} Ис-Про; ранее в черновых проходах — AttributeSet).
 *
 * <p>Уникальность — {@code (nomenclature, canonical)}. Сеттеров нет: набор неизменяем,
 * исправление написания значения выполняется обработкой переименования строки словаря
 * (id значения стабилен → канон всех наборов стабилен), а {@code displayName} пересобирается
 * той же транзакцией. Пустой набор — шапка без строк с пустым каноном, переиспользуется
 * (аналог нулевого кортежа {@code NULL → 0} Ис-Про).
 *
 * <p>Потребители (карточки, партии, строки документов) хранят одну ссылку сюда
 * вместо списка пар. Карточка без атрибутов ссылается на пустой набор, не на NULL.
 */
@Entity
@Table(name = "skl_nom_opa", uniqueConstraints = {
    @UniqueConstraint(name = "uk_skl_nom_opa_nom_canonical", columnNames = {"nomenclature_id", "canonical"})
})
@SearchFields({"displayName"})
@EntityMetadata(
    listFormTitle = "Наборы атрибутов КСУ",
    itemFormTitle = "Набор атрибутов КСУ",
    selectionFormTitle = "Выбор набора атрибутов",
    order = 75,
    icon = "LIST_UL",
    subsystem = org.ip.subsystem.Subsystems.Directories.class,
    selectColumns = {"nomenclature", "displayName"},
    displaySortFields = {"displayName"}
)
public class SklNomOpa extends BaseEntity implements HasDisplayName, InternedEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "nomenclature_id", nullable = false)
    @Lookup(entity = Nomenclature.class)
    private Nomenclature nomenclature;

    /** Каноническая строка набора {@code "typeId:valueId;..."}; пустой набор — "". */
    @NotNull
    @Column(nullable = false, length = 300)
    private String canonical;

    /** Кэш для отчетов и печатных форм ({@code "Тип: Значение, ..."}); пересобирается при переименовании. */
    @Column(name = "display_name", length = 1024)
    private String displayName;

    protected SklNomOpa() {
    }

    public SklNomOpa(Nomenclature nomenclature, String canonical, String displayName) {
        this.nomenclature = nomenclature;
        this.canonical = canonical;
        this.displayName = displayName;
    }

    public Nomenclature getNomenclature() {
        return nomenclature;
    }

    public String getCanonical() {
        return canonical;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * Обновить поддерживаемый кэш отображения (не канон: канон строится по id и при
     * переименовании значения стабилен). Единственный допустимый мутатор
     * «неизменяемого» набора — вызывается обработкой переименования
     * {@code AttributeValueService#renameValue} в той же транзакции; обычное
     * доменное изменение через repository, поэтому optimistic-lock версия
     * корректно увеличивается вместе с кэшем.
     */
    public void refreshDisplayName(String displayName) {
        this.displayName = displayName;
    }

    /**
     * Идентичность — {@code (nomenclature, canonical)}; закреплена уникальным индексом
     * {@code uk_skl_nom_opa_nom_canonical}. Канон строится по id значений словаря, поэтому
     * переименование значения ключ не меняет.
     *
     * <p>Статический вариант — единственное определение ключа: канонизация знает пару до
     * создания шапки, а на попытках повтора шапка создаётся заново.</p>
     */
    public static String interningKeyOf(Nomenclature nomenclature, String canonical) {
        String nomKey = nomenclature == null ? "?" : String.valueOf(nomenclature.getId());
        return nomKey + "|" + canonical;
    }

    @Override
    public String interningKey() {
        return interningKeyOf(nomenclature, canonical);
    }

    @Override
    public String toString() {
        return displayName == null ? canonical : displayName;
    }
}
