package org.ip.unidr.model;

import org.ip.unidr.model.UniDrDefinition.*;
import org.ip.unidr.source.UniDrSourceDefinition;
import org.ip.unidr.source.UniDrSourceDefinition.Field;
import org.ipro.reportstudio.data.QueryField;
import org.ipro.reportstudio.data.ReportDataset;
import org.ipro.reportstudio.data.ReportRow;
import org.ipro.reportstudio.render.FormulaEvaluator;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Bounded scalar expressions over source fields. Computed fields cannot query, filter or execute code. */
public final class UniDrComputedColumns {
    private UniDrComputedColumns() {}

    public static Field field(ComputedColumn column) {
        boolean numeric = column.kind() == Computation.FORMULA;
        return new Field(column.id(),column.caption(),numeric?ValueType.DECIMAL:ValueType.TEXT,column.id(),
            null,List.of(),null,null,true,numeric?Set.of(Aggregation.SUM,Aggregation.AVG,Aggregation.MIN,Aggregation.MAX):Set.of(),Set.of());
    }

    public static Set<String> references(ComputedColumn column) {
        if (column.expression()==null || column.expression().isBlank() || column.expression().length()>1000)
            throw new IllegalArgumentException("Выражение должно содержать от 1 до 1000 символов");
        if(column.kind()==null)throw new IllegalArgumentException("Выберите тип вычисляемой колонки");
        if(column.kind()==Computation.FORMULA) {
            FormulaEvaluator.validate(column.expression());
            return FormulaEvaluator.aliasesOf(column.expression());
        }
        Set<String> result=new LinkedHashSet<>();String text=column.expression();
        for(int index=0;index<text.length();index++) {
            if(text.charAt(index)=='}')throw new IllegalArgumentException("Лишняя закрывающая скобка в шаблоне");
            if(text.charAt(index)!='{')continue;
            int end=text.indexOf('}',index+1);
            if(end<0)throw new IllegalArgumentException("Незакрытая ссылка на поле в шаблоне");
            String id=text.substring(index+1,end).trim();
            if(id.isBlank() || id.contains("{"))throw new IllegalArgumentException("Некорректная ссылка на поле");
            result.add(id);index=end;
        }
        return result;
    }

    public static void validate(ComputedColumn column, UniDrSourceDefinition source) {
        for(String id:references(column)) {
            Field field=source.fields().stream().filter(item->item.id().equals(id) && item.valueAlias()!=null).findFirst()
                .orElseThrow(()->new IllegalArgumentException("Неизвестное поле источника: {"+id+"}"));
            if(column.kind()==Computation.FORMULA && field.type()!=ValueType.DECIMAL && field.type()!=ValueType.COUNT)
                throw new IllegalArgumentException("Для арифметики требуется числовое поле: {"+id+"}");
        }
    }

    /** Rewrite parsed brace references, never substrings of field names or surrounding text. */
    public static String rewriteReferences(ComputedColumn column,Map<String,String> fields) {
        references(column);var text=column.expression();var out=new StringBuilder();int start=0,open;
        while((open=text.indexOf('{',start))>=0) {
            int close=text.indexOf('}',open);String id=text.substring(open+1,close).trim();String replacement=fields.get(id);
            if(replacement==null)throw new IllegalArgumentException("Нет замены поля {"+id+"}");
            out.append(text,start,open).append('{').append(replacement).append('}');start=close+1;
        }
        return out.append(text,start,text.length()).toString();
    }

    public static ReportDataset evaluate(UniDrDefinition definition, UniDrSourceDefinition source, ReportDataset data) {
        if(definition.computedColumns().isEmpty())return data;
        Map<String,String> aliases=new HashMap<>();source.fields().forEach(field->aliases.put(field.id(),field.valueAlias()));
        var fields=new ArrayList<>(Arrays.asList(data.fields()));
        for(var column:definition.computedColumns()) {
            if(fields.stream().anyMatch(field->field.name().equals(column.id())))throw new IllegalArgumentException("Имя вычисляемой колонки занято источником");
            fields.add(QueryField.scalar(column.id(),column.kind()==Computation.FORMULA?BigDecimal.class:String.class));
        }
        QueryField[] schema=fields.toArray(QueryField[]::new);var rows=new ArrayList<ReportRow>();
        for(ReportRow row:data.rows()) {
            Object[] values=new Object[schema.length];for(int i=0;i<row.size();i++)values[i]=row.value(i);
            int index=row.size();for(var column:definition.computedColumns()) {
                values[index++]=column.kind()==Computation.FORMULA
                    ?FormulaEvaluator.evaluate(column.expression(),id->FormulaEvaluator.toBigDecimal(row.value(aliases.get(id))))
                    :text(column.expression(),row,aliases);
            }
            rows.add(new ReportRow(schema,values));
        }
        return new ReportDataset(schema,rows.toArray(ReportRow[]::new));
    }
    private static String text(String template,ReportRow row,Map<String,String> aliases) {
        var out=new StringBuilder();int start=0,open;
        while((open=template.indexOf('{',start))>=0) {
            int close=template.indexOf('}',open);out.append(template,start,open);
            Object value=row.value(aliases.get(template.substring(open+1,close).trim()));
            out.append(value instanceof LocalDate date?date.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")):Objects.toString(value,""));
            start=close+1;
        }
        return out.append(template,start,template.length()).toString();
    }
}
