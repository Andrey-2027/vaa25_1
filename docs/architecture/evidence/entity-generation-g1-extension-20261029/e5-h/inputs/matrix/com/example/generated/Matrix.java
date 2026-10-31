package com.example.generated;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Size;
import org.ipro.crud.StandardCatalogEntity;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.GridColumn;

@Entity
@Table(name = "matrix")
@EntityMetadata(
    listFormTitle = "Матрицы",
    itemFormTitle = "Матрица",
    selectionFormTitle = "Выбор матрицы",
    icon = "FILE",
    listColumns = {"code", "name", "idSys", "draftAgk", "active"},
    selectColumns = {"code", "name", "idSys", "draftAgk", "active"}
)
public class Matrix extends StandardCatalogEntity {
    @Column(name = "id_sys")
    @FieldMetadata(label = "ID внешней системы", order = 30, grid = @GridColumn(visible = false))
    private Long idSys;

    @Size(max = 150)
    @Column(name = "draft_agk", length = 150)
    @FieldMetadata(label = "Обозначение АгК", order = 40, grid = @GridColumn(visible = false))
    private String draftAgk;

    @Column(name = "active")
    @FieldMetadata(label = "Активен", order = 50, grid = @GridColumn(visible = false))
    private Boolean active;

    public Long getIdSys() {
        return idSys;
    }

    public void setIdSys(Long idSys) {
        this.idSys = idSys;
    }

    public String getDraftAgk() {
        return draftAgk;
    }

    public void setDraftAgk(String draftAgk) {
        this.draftAgk = draftAgk;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }
}
