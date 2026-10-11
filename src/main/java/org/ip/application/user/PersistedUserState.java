package org.ip.application.user;

import jakarta.persistence.EntityManager;
import jakarta.persistence.FlushModeType;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Сохранённое имя и набор ролей пользователя — предсостояние, с которым сравнивается назначение
 * ролей (ADR-0017 §12, C5.8d).
 *
 * <p>Чтение идёт проекцией в режиме {@code COMMIT}, а не canonical read сущности, и обе причины
 * названы прямо. В живой сессии (OSIV) {@code EntityManager.find} вернул бы <b>тот же управляемый
 * экземпляр</b>, который вызывающий уже изменил, — сравнение набора было бы сравнением значения с
 * самим собой, то есть «набор не менялся» независимо от правки. Запрос в режиме {@code AUTO}
 * сбросил бы на диск ещё не разрешённое изменение вызывающего <b>до</b> решения о нём: сравнение
 * увидело бы записанное состояние, а запрещённая правка оказалась бы в базе. Поэтому значения
 * берутся отдельно от экземпляра, а решение о доступе к строке остаётся за canonical read и
 * решением C5, которые вызывающий спрашивает сам.</p>
 *
 * <p>Компонент не решает и не пишет: он отвечает только на вопрос «что лежит в строке сейчас».
 * Пустой результат означает «строки нет» — правило сравнивать не с чем.</p>
 */
@Component
public class PersistedUserState {

    private final EntityManager entityManager;

    public PersistedUserState(EntityManager entityManager) {
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager must not be null");
    }

    /** Сохранённое имя строки и имена её ролей. */
    public record State(String username, Set<String> roleNames) { }

    /** Предсостояние строки; пусто, если строки нет. */
    public Optional<State> of(Long id) {
        Objects.requireNonNull(id, "id must not be null");
        List<Object[]> rows = entityManager.createQuery(
                "select u.username, r.name from User u left join u.roles r where u.id = :id",
                Object[].class)
            .setParameter("id", id)
            .setFlushMode(FlushModeType.COMMIT)
            .getResultList();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Set<String> roleNames = new LinkedHashSet<>();
        for (Object[] row : rows) {
            if (row[1] != null) {
                roleNames.add((String) row[1]);
            }
        }
        return Optional.of(new State((String) rows.get(0)[0], roleNames));
    }
}
