package com.example.materialrequests.examples;

import com.example.materialrequests.model.MaterialRequest;
import java.util.List;
import org.ipro.form.action.ActionHandler;
import org.ipro.form.action.ActionId;
import org.ipro.form.action.CrudAction;
import org.ipro.form.builder.ListCommandLayout;
import org.ipro.form.builder.ListFormCustomization;
import org.ipro.form.builder.ListFormVariants;
import org.ipro.form.builder.ListUiCommand;
import org.ipro.form.builtin.ListForm;

/** Compiled documentation examples; deliberately not registered as Spring beans. */
public final class ListCommandExamples {
    private static final ActionId PRINT = ActionId.of("report.print");

    private ListCommandExamples() {}

    /** Put these methods in the application's existing list configuration bean. */
    public static final class RequestCommands implements ListFormCustomization {
        @Override public Class<?> entityClass() { return MaterialRequest.class; }

        @Override public void configure(ListFormVariants lists) {
            lists.commands(c -> {
                c.panel(CrudAction.CREATE.id(), CrudAction.EDIT.id());
                c.command(CrudAction.COPY.id()).iconAndText();
                c.command(ListCommandLayout.Ref.optional(PRINT)).inMore();
            });
            lists.commands("review", c -> {
                c.commands(CrudAction.OPEN.id(), CrudAction.REFRESH.id());
                c.panel(CrudAction.REFRESH.id());
            });
        }
    }

    /** An explicit composition that also works without a reporting module. */
    public static ListCommandLayout explicitWithOptionalPrint() {
        return ListCommandLayout.builder()
            .commandRefs(List.of(
                ListCommandLayout.Ref.required(CrudAction.CREATE.id()),
                ListCommandLayout.Ref.required(CrudAction.REFRESH.id()),
                ListCommandLayout.Ref.optional(PRINT)))
            .panel(CrudAction.CREATE.id(), CrudAction.REFRESH.id())
            .build();
    }

    /** Changes only this opening; no button listeners or CRUD handlers are copied. */
    public static void configureOpening(ListForm<?, ?> form) {
        form.configureCommands(c -> {
            c.allCommands();
            c.command(ListUiCommand.VIEWS.id()).iconAndText();
            c.exclude(CrudAction.DELETE.id());
        });
    }

    /** The caller supplies its existing handler in the same response cycle. */
    public static void installLocal(ListForm<?, ?> form, ActionHandler handler) {
        form.configureCommands(c ->
            c.command(ListCommandLayout.Ref.local(handler.definition().id())).inMore());
        form.addAction(handler);
    }
}
