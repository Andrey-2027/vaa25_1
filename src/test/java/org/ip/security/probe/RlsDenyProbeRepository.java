package org.ip.security.probe;

import org.ip.model.Journal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Пробный repository только для тестов границы RLS: он объявляет два пути, которые
 * {@code RlsRepositoryEnforcementAspect} обязан отклонить для защищённой сущности —
 * native query (Hibernate-фильтр её не защищает вовсе) и bulk DML ({@code @Modifying}
 * не прогоняет ни entity lifecycle callbacks, ни построчную проверку политики).
 *
 * <p>В приложении таких методов нет — ни один production repository не объявляет
 * native query, — поэтому проверить запрет без объявленного метода нельзя. Оба метода
 * никогда не выполняются: тест утверждает отказ до {@code proceed()}, а не результат
 * запроса. Сущность {@link Journal} выбрана как уже защищённая (измерение {@code JOURNAL}),
 * чтобы запрет проверялся на реальной policy, а не на пустой заглушке.</p>
 */
public interface RlsDenyProbeRepository extends JpaRepository<Journal, Long> {

    /** Native SQL: фильтры Hibernate к нему не применяются — канал обязан быть закрыт. */
    @Query(value = "select * from journal where code = :code", nativeQuery = true)
    List<Journal> findByCodeNative(@Param("code") String code);

    /** Bulk DML: затрагивает строки, но не даёт проверить политику построчно. */
    @Modifying
    @Query("delete from Journal j where j.code = :code")
    int deleteByCodeBulk(@Param("code") String code);
}
