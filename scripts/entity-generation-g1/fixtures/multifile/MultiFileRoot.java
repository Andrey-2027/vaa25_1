package com.example.generated;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.ipro.crud.StandardDocumentEntity;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.TableSections;

@Entity
@Table(name = "multi_file_root")
@EntityMetadata(
    listFormTitle = "Контрольные комплекты",
    itemFormTitle = "Контрольный комплект",
    selectionFormTitle = "Выбор контрольного комплекта",
    icon = "FILE",
    listColumns = {"number", "date"},
    selectColumns = {"number", "date"}
)
@TableSections({MultiFileRow.class, MultiFileNote.class})
public class MultiFileRoot extends StandardDocumentEntity {
}
