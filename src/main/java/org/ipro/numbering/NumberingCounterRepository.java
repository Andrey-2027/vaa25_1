package org.ipro.numbering;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NumberingCounterRepository extends JpaRepository<NumberingCounter, String> {

    /** Все счётчики правила (по префиксу ключа = SimpleName сущности) — для админ-экрана. */
    List<NumberingCounter> findByKeyStartingWith(String prefix);
}
