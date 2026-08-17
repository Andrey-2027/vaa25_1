package org.ipro.reportstudio.dto;

import org.ipro.reportstudio.dom.ReportBandKind;
import org.ipro.reportstudio.dom.ReportComputedValue;
import org.ipro.reportstudio.dom.ReportFieldAggregation;
import org.ipro.reportstudio.dom.ReportFieldAlignment;
import org.ipro.reportstudio.dom.ReportFieldKind;
import org.ipro.reportstudio.dom.ReportOrderDirection;
import org.ipro.reportstudio.dom.ReportPageOrientation;
import org.ipro.reportstudio.dom.ReportPageSize;
import org.ipro.reportstudio.dom.ReportParamKind;
import org.ipro.reportstudio.dom.ReportParamSource;
import org.ipro.reportstudio.dom.ReportTemplateState;

import java.util.ArrayList;
import java.util.List;

/**
 * DTO шаблона отчёта для обмена с UI (Фаза 1). Зеркало сущностей без
 * JPA/Hibernate; иерархия бэндов передаётся через {@link ReportBandDto#parentId}
 * (родитель — тоже бэнд из того же списка {@link ReportTemplateDto#bands}).
 *
 * PREVIEW_UI: для сохранения используется дельта родителей (applyTo в
 * {@link org.ipro.reportstudio.dto.ReportTemplateMapper}), поэтому повторные
 * правки не плодят версий.
 */
public class ReportTemplateDto {

    private Long id;
    private Long version;
    private String name;
    private String description;
    private ReportTemplateState state = ReportTemplateState.DRAFT;
    private String jpql;
    private Integer maxRows;
    private Integer timeoutMs;
    private boolean advanced;
    private ReportPageSize pageSize;
    private ReportPageOrientation pageOrientation;
    private Integer baseFontSize;
    private Boolean gridEnabled;
    private Boolean stripeRows;
    private List<ReportParamDto> params = new ArrayList<>();
    private List<ReportBandDto> bands = new ArrayList<>();
    private List<ReportOrderDto> orders = new ArrayList<>();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public ReportTemplateState getState() {
        return state;
    }

    public void setState(ReportTemplateState state) {
        this.state = state;
    }

    public String getJpql() {
        return jpql;
    }

    public void setJpql(String jpql) {
        this.jpql = jpql;
    }

    public Integer getMaxRows() {
        return maxRows;
    }

    public void setMaxRows(Integer maxRows) {
        this.maxRows = maxRows;
    }

    public Integer getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(Integer timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public boolean isAdvanced() {
        return advanced;
    }

    public void setAdvanced(boolean advanced) {
        this.advanced = advanced;
    }

    public ReportPageSize getPageSize() {
        return pageSize;
    }

    public void setPageSize(ReportPageSize pageSize) {
        this.pageSize = pageSize;
    }

    public ReportPageOrientation getPageOrientation() {
        return pageOrientation;
    }

    public void setPageOrientation(ReportPageOrientation pageOrientation) {
        this.pageOrientation = pageOrientation;
    }

    public Integer getBaseFontSize() {
        return baseFontSize;
    }

    public void setBaseFontSize(Integer baseFontSize) {
        this.baseFontSize = baseFontSize;
    }

    public Boolean getGridEnabled() {
        return gridEnabled;
    }

    public void setGridEnabled(Boolean gridEnabled) {
        this.gridEnabled = gridEnabled;
    }

    public Boolean getStripeRows() {
        return stripeRows;
    }

    public void setStripeRows(Boolean stripeRows) {
        this.stripeRows = stripeRows;
    }

    public List<ReportParamDto> getParams() {
        return params;
    }

    public void setParams(List<ReportParamDto> params) {
        this.params = params;
    }

    public List<ReportBandDto> getBands() {
        return bands;
    }

    public void setBands(List<ReportBandDto> bands) {
        this.bands = bands;
    }

    public List<ReportOrderDto> getOrders() {
        return orders;
    }

    public void setOrders(List<ReportOrderDto> orders) {
        this.orders = orders;
    }

    public static class ReportOrderDto {

        private Long id;
        private Long version;
        private String columnName;
        private ReportOrderDirection direction = ReportOrderDirection.ASC;
        private int position;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public Long getVersion() {
            return version;
        }

        public void setVersion(Long version) {
            this.version = version;
        }

        public String getColumnName() {
            return columnName;
        }

        public void setColumnName(String columnName) {
            this.columnName = columnName;
        }

        public ReportOrderDirection getDirection() {
            return direction;
        }

        public void setDirection(ReportOrderDirection direction) {
            this.direction = direction;
        }

        public int getPosition() {
            return position;
        }

        public void setPosition(int position) {
            this.position = position;
        }
    }

    public static class ReportParamDto {

        private Long id;
        private Long version;
        private String name;
        private String caption;
        private ReportParamKind kind = ReportParamKind.SCALAR;
        private String entityClass;
        private ReportParamSource valueSource = ReportParamSource.FORM;
        private boolean required;
        private boolean showOnForm = true;
        private String defaultValue;
        private ReportComputedValue computed = ReportComputedValue.NONE;
        private int position;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public Long getVersion() {
            return version;
        }

        public void setVersion(Long version) {
            this.version = version;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getCaption() {
            return caption;
        }

        public void setCaption(String caption) {
            this.caption = caption;
        }

        public ReportParamKind getKind() {
            return kind;
        }

        public void setKind(ReportParamKind kind) {
            this.kind = kind;
        }

        public String getEntityClass() {
            return entityClass;
        }

        public void setEntityClass(String entityClass) {
            this.entityClass = entityClass;
        }

        public ReportParamSource getValueSource() {
            return valueSource;
        }

        public void setValueSource(ReportParamSource valueSource) {
            this.valueSource = valueSource;
        }

        public boolean isRequired() {
            return required;
        }

        public void setRequired(boolean required) {
            this.required = required;
        }

        public boolean isShowOnForm() {
            return showOnForm;
        }

        public void setShowOnForm(boolean showOnForm) {
            this.showOnForm = showOnForm;
        }

        public String getDefaultValue() {
            return defaultValue;
        }

        public void setDefaultValue(String defaultValue) {
            this.defaultValue = defaultValue;
        }

        public ReportComputedValue getComputed() {
            return computed;
        }

        public void setComputed(ReportComputedValue computed) {
            this.computed = computed;
        }

        public int getPosition() {
            return position;
        }

        public void setPosition(int position) {
            this.position = position;
        }
    }

    public static class ReportBandDto {

        private Long id;
        private Long version;
        private ReportBandKind kind = ReportBandKind.DETAIL;
        private int position;
        private Long parentId;
        private String groupField;
        private Boolean startNewPage;
        private List<ReportFieldDto> fields = new ArrayList<>();

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public Long getVersion() {
            return version;
        }

        public void setVersion(Long version) {
            this.version = version;
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

        public Long getParentId() {
            return parentId;
        }

        public void setParentId(Long parentId) {
            this.parentId = parentId;
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

        public void setStartNewPage(Boolean startNewPage) {
            this.startNewPage = startNewPage;
        }

        public List<ReportFieldDto> getFields() {
            return fields;
        }

        public void setFields(List<ReportFieldDto> fields) {
            this.fields = fields;
        }
    }

    public static class ReportFieldDto {

        private Long id;
        private Long version;
        private ReportFieldKind kind = ReportFieldKind.COLUMN;
        private String queryField;
        private String text;
        private String caption;
        private Integer width;
        private String format;
        private Boolean border;
        private boolean visible = true;
        private ReportFieldAggregation aggregation = ReportFieldAggregation.NONE;
        private ReportFieldAlignment alignment = ReportFieldAlignment.LEFT;
        private int position;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public Long getVersion() {
            return version;
        }

        public void setVersion(Long version) {
            this.version = version;
        }

        public ReportFieldKind getKind() {
            return kind;
        }

        public void setKind(ReportFieldKind kind) {
            this.kind = kind;
        }

        public String getQueryField() {
            return queryField;
        }

        public void setQueryField(String queryField) {
            this.queryField = queryField;
        }

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }

        public String getCaption() {
            return caption;
        }

        public void setCaption(String caption) {
            this.caption = caption;
        }

        public Integer getWidth() {
            return width;
        }

        public void setWidth(Integer width) {
            this.width = width;
        }

        public String getFormat() {
            return format;
        }

        public void setFormat(String format) {
            this.format = format;
        }

        public Boolean getBorder() {
            return border;
        }

        public void setBorder(Boolean border) {
            this.border = border;
        }

        public boolean isVisible() {
            return visible;
        }

        public void setVisible(boolean visible) {
            this.visible = visible;
        }

        public ReportFieldAggregation getAggregation() {
            return aggregation;
        }

        public void setAggregation(ReportFieldAggregation aggregation) {
            this.aggregation = aggregation;
        }

        public ReportFieldAlignment getAlignment() {
            return alignment;
        }

        public void setAlignment(ReportFieldAlignment alignment) {
            this.alignment = alignment;
        }

        public int getPosition() {
            return position;
        }

        public void setPosition(int position) {
            this.position = position;
        }
    }
}