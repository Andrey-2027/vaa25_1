package org.ip.views.admin;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.splitlayout.SplitLayout;

/** Client-local geometry and scroll state; no viewport state in the shared snapshot. */
final class ExplorerSequentialLayout {
    private ExplorerSequentialLayout() { }

    static void install(SplitLayout split, Div list, Div detail) {
        split.addClassName("explorer-sequential");
        list.addClassName("explorer-sequential-list");
        Button back = new Button("Назад к списку");
        back.addClassName("explorer-sequential-back");
        Div pane = new Div(back, detail);
        pane.addClassName("explorer-sequential-detail");
        split.addToSecondary(pane);
        back.addClickListener(e -> {
            split.getElement().removeAttribute("data-detail");
            list.getElement().executeJs("requestAnimationFrame(() => this.querySelector('[aria-selected=true], [aria-pressed=true]')?.focus({preventScroll:true}));");
        });
        split.addAttachListener(e -> split.getElement().executeJs("""
            if (this.__sequentialObserver) this.__sequentialObserver.disconnect();
            this.__sequentialObserver = new ResizeObserver(entries => {
              if (entries[0].contentRect.width > 0)
                this.toggleAttribute('data-narrow', entries[0].contentRect.width < 650);
            });
            this.__sequentialObserver.observe(this);
            """));
        split.addDetachListener(e -> split.getElement().executeJs("this.__sequentialObserver?.disconnect();"));
    }

    static void showDetail(SplitLayout split) {
        split.getElement().setAttribute("data-detail", "");
        split.getElement().executeJs("if(this.hasAttribute('data-narrow')) requestAnimationFrame(() => this.querySelector('.explorer-sequential-back')?.focus({preventScroll:true}));");
    }
}
