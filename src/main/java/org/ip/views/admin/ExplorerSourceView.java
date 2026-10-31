package org.ip.views.admin;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Span;

/** One shared, escaped text-only viewer, with file lines and a confirmed method focus. */
final class ExplorerSourceView extends Div {
    ExplorerSourceView() { addClassName("explorer-source-view"); }

    void show(ExplorerSourceReader.Source source) {
        removeAll();
        String symbol = source.target() == null ? "Символ не задан" : source.target().symbol();
        H4 title = new H4(symbol);
        title.getElement().setAttribute("data-source-symbol", symbol);
        add(title);
        if (!source.available()) {
            add(new Span("Исходник недоступен: " + source.reason()));
            return;
        }
        Span note = new Span("Источник: рабочая копия; соответствие запущенному bytecode не подтверждено");
        note.addClassName("explorer-property-note");
        add(note);
        if (!source.reason().isBlank()) add(new Span(source.reason()));
        Div code = new Div();
        code.addClassName("explorer-source-code");
        code.getElement().setAttribute("tabindex", "0");
        code.getElement().setAttribute("role", "region");
        code.getElement().setAttribute("aria-label", "Исходный код " + symbol);
        Span focus = null;
        for (int index = 0; index < source.lines().size(); index++) {
            int number = index + 1;
            Span line = new Span();
            line.addClassName("explorer-source-line");
            line.getElement().setAttribute("data-source-line", String.valueOf(number));
            Span gutter = new Span(String.valueOf(number));
            gutter.addClassName("explorer-source-number");
            line.add(gutter, new Span(source.lines().get(index)));
            if (number >= source.firstLine() && number <= source.lastLine()) line.addClassName("explorer-source-focus");
            if (number == source.firstLine()) focus = line;
            code.add(line);
        }
        add(code);
        if (focus != null) focus.getElement().executeJs(
            "const box=this.parentElement;box.scrollTop=Math.max(0,this.offsetTop-24);");
    }
}
