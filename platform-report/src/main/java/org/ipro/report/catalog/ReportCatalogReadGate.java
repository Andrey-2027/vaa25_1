package org.ipro.report.catalog;

import java.util.Objects;

import org.ipro.rls.RlsContext;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.rls.RlsReadGate;

/**
 * Чтение строк каталога: единственное место, где решается, можно ли показать
 * записи движка текущему пользователю.
 *
 * <p>Раньше эта проверка жила в {@link ReportCatalogService} и применялась к
 * внешним хранилищам (UDR-ветка полагается на собственный scope сервиса
 * шаблонов). Теперь её используют адаптеры движков, поэтому имя пользователя
 * достаётся одним и тем же способом: при служебном bypass — без требования
 * аутентификации, иначе — обязательный аутентифицированный субъект.</p>
 */
public class ReportCatalogReadGate {

    private final RlsReadGate readGate;
    private final RlsCurrentUser currentUser;

    public ReportCatalogReadGate(RlsReadGate readGate, RlsCurrentUser currentUser) {
        this.readGate = Objects.requireNonNull(readGate, "readGate");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser");
    }

    public boolean canRead(Class<?> entityClass) {
        return readGate.canRead(entityClass, currentUsername());
    }

    private String currentUsername() {
        return RlsContext.isBypassed()
                ? currentUser.username()
                : currentUser.requireAuthenticatedUsername();
    }
}
