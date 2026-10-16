package org.ip.unidr.model;

import org.ipro.reportstudio.dom.ReportComputedValue;
import org.ipro.reportstudio.dom.ReportParam;
import org.ipro.reportstudio.dom.ReportParamKind;
import org.ipro.reportstudio.dom.ReportParamSource;
import org.ipro.reportstudio.dom.ReportQuerySource;
import org.ipro.reportstudio.dom.ReportTemplate;
import java.util.List;

/** Immutable query/parameter recipe. UDR templates used by the editor are never persisted. */
public record UniDrQuerySource(ReportQuerySource kind, String jpql, String visualQueryJson,
                               List<Param> parameters,String tableEntity) {
    public static final String ID="query";
    public UniDrQuerySource { parameters=List.copyOf(parameters); }
    public UniDrQuerySource(ReportQuerySource kind,String jpql,String visualQueryJson,List<Param> parameters){this(kind,jpql,visualQueryJson,parameters,null);}
    public record Param(String name,String caption,ReportParamKind kind,String entityClass,
                        ReportParamSource valueSource,boolean required,boolean showOnForm,
                        String defaultValue,ReportComputedValue computed,int position) {
        public static Param from(ReportParam value) {
            return new Param(value.getName(),value.getCaption(),value.getKind(),value.getEntityClass(),value.getValueSource(),
                value.isRequired(),value.isShowOnForm(),value.getDefaultValue(),value.getComputed(),value.getPosition());
        }
        ReportParam parameter() {
            var value=new ReportParam();value.setName(name);value.setCaption(caption);value.setKind(kind);value.setEntityClass(entityClass);
            value.setValueSource(valueSource);value.setRequired(required);value.setShowOnForm(showOnForm);value.setDefaultValue(defaultValue);
            value.setComputed(computed);value.setPosition(position);return value;
        }
    }
    public static UniDrQuerySource from(ReportTemplate template) {
        return new UniDrQuerySource(template.getQuerySource(),template.getJpql(),template.getVisualQueryJson(),template.getParams().stream().map(Param::from).toList());
    }
    public ReportTemplate template() {
        var template=new ReportTemplate();template.setName("UniDR");template.setQuerySource(kind);template.setJpql(jpql);template.setVisualQueryJson(visualQueryJson);
        parameters.forEach(value->template.addParam(value.parameter()));return template;
    }
}
