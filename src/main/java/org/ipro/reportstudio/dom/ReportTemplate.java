package org.ipro.reportstudio.dom;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import org.ipro.crud.BaseEntity;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "report_template", uniqueConstraints = @UniqueConstraint(name = "uk_report_template_name", columnNames = "name"))
public class ReportTemplate extends BaseEntity {
    public static final int DEFAULT_MAX_ROWS = 5000;
    public static final int DEFAULT_TIMEOUT_MS = 30000;
    public static final int PREVIEW_MAX_ROWS = 20;
    public static final int DEFAULT_FONT_SIZE = 10;
    @NotBlank @Size(max = 100) @Column(nullable = false, unique = true, length = 100) private String name;
    @Size(max = 1000) @Column(length = 1000) private String description;
    @NotNull @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private ReportTemplateState state = ReportTemplateState.DRAFT;
    @NotBlank @Column(nullable = false, columnDefinition = "text") private String jpql;
    @Enumerated(EnumType.STRING) @Column(name = "query_source", length = 20) private ReportQuerySource querySource = ReportQuerySource.MANUAL;
    @Column(name = "target_entity_class", length = 512) private String targetEntityClass;
    @Column(name = "max_rows", nullable = false) private int maxRows = DEFAULT_MAX_ROWS;
    @Column(name = "timeout_ms", nullable = false) private int timeoutMs = DEFAULT_TIMEOUT_MS;
    @Column(nullable = false) private boolean advanced;
    @Column(name = "grid_enabled") private Boolean gridEnabled;
    @Column(name = "stripe_rows") private Boolean stripeRows;
    @Column(name = "base_font_size") private Integer baseFontSize;
    @Enumerated(EnumType.STRING) @Column(name = "page_size", length = 10) private ReportPageSize pageSize;
    @Enumerated(EnumType.STRING) @Column(name = "page_orientation", length = 10) private ReportPageOrientation pageOrientation;
    @Column(name = "visual_filter_json", columnDefinition = "text") private String visualFilterJson;
    @Column(name = "visual_query_json", columnDefinition = "text") private String visualQueryJson;
    @OneToMany(mappedBy = "template", cascade = CascadeType.ALL, orphanRemoval = true) @OrderBy("position ASC, id ASC") private List<ReportParam> params = new ArrayList<>();
    @OneToMany(mappedBy = "template", cascade = CascadeType.ALL, orphanRemoval = true) @OrderBy("position ASC, id ASC") private List<ReportBand> bands = new ArrayList<>();
    @OneToMany(mappedBy = "template", cascade = CascadeType.ALL, orphanRemoval = true) @OrderBy("position ASC, id ASC") private List<ReportOrder> orders = new ArrayList<>();
    public String getName(){return name;} public void setName(String v){name=v;} public String getDescription(){return description;} public void setDescription(String v){description=v;}
    public ReportTemplateState getState(){return state;} public void setState(ReportTemplateState v){state=v;} public String getJpql(){return jpql;} public void setJpql(String v){jpql=v;}
    public ReportQuerySource getQuerySource(){return querySource == null ? ReportQuerySource.MANUAL : querySource;}
    public void setQuerySource(ReportQuerySource value){querySource = value == null ? ReportQuerySource.MANUAL : value;}
    public String getTargetEntityClass(){return targetEntityClass;} public void setTargetEntityClass(String v){targetEntityClass=v==null||v.isBlank()?null:v.trim();}
    public int getMaxRows(){return maxRows;} public void setMaxRows(int v){maxRows=v;} public int getTimeoutMs(){return timeoutMs;} public void setTimeoutMs(int v){timeoutMs=v;} public boolean isAdvanced(){return advanced;} public void setAdvanced(boolean v){advanced=v;}
    public Boolean getGridEnabledRaw(){return gridEnabled;} public boolean isGridEnabled(){return gridEnabled==null||gridEnabled;} public void setGridEnabled(Boolean v){gridEnabled=v;} public Boolean getStripeRowsRaw(){return stripeRows;} public boolean isStripeRows(){return stripeRows!=null&&stripeRows;} public void setStripeRows(Boolean v){stripeRows=v;}
    public Integer getBaseFontSize(){return baseFontSize;} public int baseFontSizeOrDefault(){return baseFontSize==null?DEFAULT_FONT_SIZE:baseFontSize;} public void setBaseFontSize(Integer v){baseFontSize=v;} public ReportPageSize getPageSize(){return pageSize;} public ReportPageSize pageSizeOrDefault(){return pageSize==null?ReportPageSize.A4:pageSize;} public void setPageSize(ReportPageSize v){pageSize=v;} public ReportPageOrientation getPageOrientation(){return pageOrientation;} public ReportPageOrientation pageOrientationOrDefault(){return pageOrientation==null?ReportPageOrientation.PORTRAIT:pageOrientation;} public void setPageOrientation(ReportPageOrientation v){pageOrientation=v;}
    public String getVisualFilterJson(){return visualFilterJson;} public void setVisualFilterJson(String v){visualFilterJson=v==null||v.isBlank()?null:v;} public String getVisualQueryJson(){return visualQueryJson;} public void setVisualQueryJson(String v){visualQueryJson=v==null||v.isBlank()?null:v;}
    public List<ReportParam> getParams(){return params;} public void setParams(List<ReportParam> v){params=v;} public void addParam(ReportParam v){v.setTemplate(this);params.add(v);} public List<ReportBand> getBands(){return bands;} public void setBands(List<ReportBand> v){bands=v;} public void addBand(ReportBand v){v.setTemplate(this);bands.add(v);} public List<ReportOrder> getOrders(){return orders;} public void setOrders(List<ReportOrder> v){orders=v;} public void addOrder(ReportOrder v){v.setTemplate(this);orders.add(v);}
}
