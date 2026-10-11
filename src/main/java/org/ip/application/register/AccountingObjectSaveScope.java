package org.ip.application.register;

import org.ip.model.AccountingObjectDocument;
import org.ipro.crud.ValidationException;

/** Only the application command may save an accounting header and its derived set. */
final class AccountingObjectSaveScope implements AutoCloseable {
    private record Intent(AccountingObjectDocument document, java.util.List<AccountingObjectContribution.Line> lines) { }
    private static final ThreadLocal<Intent> ACTIVE = new ThreadLocal<>();
    private final Intent previous;
    private AccountingObjectSaveScope(AccountingObjectDocument document, java.util.List<AccountingObjectContribution.Line> lines) {
        previous = ACTIVE.get(); ACTIVE.set(new Intent(document, java.util.List.copyOf(lines)));
    }
    static AccountingObjectSaveScope open(AccountingObjectDocument document) {
        return new AccountingObjectSaveScope(document, java.util.List.of());
    }
    static AccountingObjectSaveScope open(AccountingObjectDocument document, java.util.List<AccountingObjectContribution.Line> lines) {
        return new AccountingObjectSaveScope(document, lines);
    }
    static void require(AccountingObjectDocument document) {
        if (ACTIVE.get() == null || ACTIVE.get().document() != document) {
            throw new ValidationException("Сохраняйте документ объекта учёта вместе с его позициями через команду документа");
        }
    }
    static java.util.List<AccountingObjectContribution.Line> lines(AccountingObjectDocument document) {
        Intent intent = ACTIVE.get();
        // An update returns the managed copy. beforeSave still requires the original command payload.
        if (intent == null || (intent.document() != document &&
                (document.getId() == null || !document.getId().equals(intent.document().getId()) ||
                 org.hibernate.Hibernate.getClass(document) != org.hibernate.Hibernate.getClass(intent.document())))) {
            throw new ValidationException("Сохраняйте документ объекта учёта вместе с его позициями через команду документа");
        }
        return intent.lines();
    }
    @Override public void close() { if (previous == null) ACTIVE.remove(); else ACTIVE.set(previous); }
}

