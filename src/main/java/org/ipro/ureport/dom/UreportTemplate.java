package org.ipro.ureport.dom;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.ipro.crud.BaseEntity;

/**
 * Метаданные шаблона отчёта UReport3 (веб-дизайнер /ureport/designer).
 *
 * <p>Сам шаблон — XML-файл в файловом хранилище UReport
 * ({@code ureport.fileStoreDir}), движок читает его по {@link #fileName}.
 * БД хранит только каталожные метаданные: имя, описание, признак активности
 * (см. UnionReport1.md, Р1/Р2 — хранение раздельно с ReportTemplate (UDR),
 * объединение только на уровне read-модели каталога).</p>
 */
@Entity
@Table(name = "ureport_template",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_ureport_template_name", columnNames = "name"),
        @UniqueConstraint(name = "uk_ureport_template_file", columnNames = "file_name")
    })
public class UreportTemplate extends BaseEntity {

    /** Отображаемое имя в каталоге отчётов. */
    @NotBlank
    @Size(max = 250)
    @Column(nullable = false, unique = true, length = 250)
    private String name;

    /** Имя XML-файла шаблона в fileStoreDir (напр. "proba1.ureport.xml"). */
    @NotBlank
    @Size(max = 255)
    @Column(name = "file_name", nullable = false, unique = true, length = 255)
    private String fileName;

    @Size(max = 1000)
    @Column(length = 1000)
    private String description;

    /** Отключённый отчёт скрыт из каталога выполнения (но не из редактирования). */
    @Column(nullable = false)
    private boolean enabled = true;

    /**
     * Привязка к реестру сущностей (canonical name, nullable): шаблон появляется
     * в кнопке «Печать» этого реестра (ContextualReportLauncher). null = только каталог.
     */
    @Size(max = 255)
    @Column(name = "target_entity_class", length = 255)
    private String targetEntityClass;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getTargetEntityClass() {
        return targetEntityClass;
    }

    public void setTargetEntityClass(String targetEntityClass) {
        this.targetEntityClass = targetEntityClass;
    }
}
