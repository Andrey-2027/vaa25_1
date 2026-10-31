package org.ip.repository;

import org.ip.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    @org.springframework.data.jpa.repository.Query("select distinct u from User u left join fetch u.roles where u.id = :id")
    Optional<User> findPermissionSubject(@org.springframework.data.repository.query.Param("id") Long id);
}
