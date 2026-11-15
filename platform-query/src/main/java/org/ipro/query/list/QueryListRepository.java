package org.ipro.query.list;

import org.springframework.data.jpa.repository.JpaRepository;

/** Internal persistence adapter for the query list registry. */
public interface QueryListRepository extends JpaRepository<QueryListEntity, String> {}
