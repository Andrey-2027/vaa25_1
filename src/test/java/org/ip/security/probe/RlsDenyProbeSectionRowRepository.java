package org.ip.security.probe;

import org.ip.model.PrdSpecMtr;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Пробный repository строки owned-секции: только для теста границы RLS.
 *
 * <p>Ни одна строка секции в приложении не имеет собственного repository — доступ
 * наследуется от aggregate root, а произвольный derived/custom запрос к строкам не может
 * выразить обязательный предикат владельца. Чтобы проверять этот запрет, нужен объект,
 * который аспект обязан отклонить: иначе запрет существует только в тексте javadoc.</p>
 */
public interface RlsDenyProbeSectionRowRepository extends JpaRepository<PrdSpecMtr, Long> {
}
