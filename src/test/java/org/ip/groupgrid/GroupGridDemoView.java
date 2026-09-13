package org.ip.groupgrid;

import java.util.List;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;

import jakarta.annotation.security.PermitAll;

/**
 * Демо GroupingGrid: эмуляция группировки строк поверх обычного Grid.
 *
 * Доступ: /groupgrid
 */
@Route("groupgrid")
@PageTitle("GroupingGrid Demo")
@PermitAll
public class GroupGridDemoView extends VerticalLayout {

    public record Employee(String department, String position, String name, long salary) {}

    private static final List<Employee> EMPLOYEES = List.of(
            new Employee("ИТ", "Разработчик", "Иванов И.И.", 150_000),
            new Employee("ИТ", "Разработчик", "Петров П.П.", 145_000),
            new Employee("ИТ", "Тестировщик", "Сидорова А.А.", 110_000),
            new Employee("Бухгалтерия", "Бухгалтер", "Козлова М.М.", 95_000),
            new Employee("Бухгалтерия", "Главный бухгалтер", "Смирнова О.О.", 130_000),
            new Employee("Продажи", "Менеджер", "Волков Д.Д.", 120_000),
            new Employee("Продажи", "Менеджер", "Егоров К.К.", 118_000),
            new Employee("Продажи", "Руководитель отдела", "Никитина Е.Е.", 160_000));

    private final GroupingGrid<Employee> grid = new GroupingGrid<>();

    public GroupGridDemoView() {
        setSizeFull();
        setPadding(true);
        setSpacing(true);

        add(new H2("GroupingGrid — эмуляция группировки строк"));
        add(new Paragraph(
                "Клик по строке группы или стрелке — свернуть/развернуть. "
                + "Заголовки колонок — сортировка (внутри групп)."));

        HorizontalLayout toolbar = new HorizontalLayout();
        Button byDepartment = new Button("Группировать: Отдел",
                e -> grid.setItems(EMPLOYEES, Employee::department));
        Button byPosition = new Button("Группировать: Должность",
                e -> grid.setItems(EMPLOYEES, Employee::position));
        Button collapseAll = new Button("Свернуть все", e -> grid.collapseAll());
        Button expandAll = new Button("Развернуть все", e -> grid.expandAll());
        toolbar.add(byDepartment, byPosition, collapseAll, expandAll);
        add(toolbar);

        grid.setSizeFull();

        grid.addItemColumn(Employee::department, "Отдел");
        grid.addItemColumn(Employee::position, "Должность");
        grid.addItemColumn(Employee::name, "Сотрудник");
        Grid.Column<GroupingGrid.GroupRow<Employee>> salaryColumn =
                grid.addItemColumn(Employee::salary, "Оклад", v -> String.format("%,d ₽", v));
        grid.setAggregator(salaryColumn,
                rows -> String.format("Σ %,d ₽", rows.stream().mapToLong(Employee::salary).sum()));

        grid.setItems(EMPLOYEES, Employee::department);

        setFlexGrow(1.0, grid);
        add(grid);
    }
}
