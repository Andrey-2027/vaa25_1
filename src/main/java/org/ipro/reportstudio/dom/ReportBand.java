package org.ipro.reportstudio.dom;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.ipro.crud.BaseEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * Бэнд отчёта (Фаза 1, layout). Бэнды: REPORT_HEADER, GROUP_HEADER (N уровней,
 * вложенные через parent), DETAIL, GROUP_FOOTER, REPORT_FOOTER.
 *
 * GROUP_HEADER и GROUP_FOOTER образуют пару по (parent, groupField) —
 * groupField это имя/alias QueryField, по которому группируется отчёт
 * (группировка отчёта, не SQL GROUP BY). Вложенность: GROUР_HEADER может
 * ссылаться на родительский GROUP_HEADER.
 */
@Entity
@Table(name = "report_band")
public class ReportBand extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "template_id", nullable = false)
    private ReportTemplate template;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 25)
    private ReportBandKind kind = ReportBandKind.DETAIL;

    /** Порядок бэнда; для групповых пар — порядок следования группы. */
    @Column(nullable = false)
    private int position;

    /** Родительский GROUP_HEADER для вложенной группировки; null = группа верхнего уровня. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private ReportBand parent;

    /** Alias QueryField, по которому группируется бэнд (только для GROUP_HEADER/GROUP_FOOTER). */
    @Size(max = 100)
    @Column(name = "group_field", length = 100)
    private String groupField;

    /**
     * Начинать группу с новой страницы (только GROUP_HEADER).
     * Хранится на header-бэнде пары; footer значение не использует.
     */
    @Column(name = "start_new_page")
    private Boolean startNewPage;

    @OneToMany(mappedBy = "band", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC, id ASC")
    private List<ReportField> fields = new ArrayList<>();

    public ReportTemplate getTemplate() {
        return template;
    }

    public void setTemplate(ReportTemplate template) {
        this.template = template;
    }

    public ReportBandKind getKind() {
        return kind;
    }

    public void setKind(ReportBandKind kind) {
        this.kind = kind;
    }

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    public ReportBand getParent() {
        return parent;
    }

    public void setParent(ReportBand parent) {
        this.parent = parent;
    }

    public String getGroupField() {
        return groupField;
    }

    public void setGroupField(String groupField) {
        this.groupField = groupField;
    }

    public Boolean getStartNewPage() {
        return startNewPage;
    }

    public boolean isStartNewPage() {
        return startNewPage != null && startNewPage;
    }

    public void setStartNewPage(Boolean startNewPage) {
        this.startNewPage = startNewPage;
    }

    public List<ReportField> getFields() {
        return fields;
    }

    public void setFields(List<ReportField> fields) {
        this.fields = fields;
    }

    /** Добавляет поле бэнда с двусторонней связью. */
    public void addField(ReportField field) {
        field.setBand(this);
        fields.add(field);
    }
}