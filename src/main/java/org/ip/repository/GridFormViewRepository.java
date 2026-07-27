package org.ip.repository;

import org.ip.model.GridFormView;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GridFormViewRepository
        extends JpaRepository<GridFormView, Long>, JpaSpecificationExecutor<GridFormView> {

    /**
     * Все виды, доступные пользователю для данного formKey: общие (shared) + его
     * собственные личные. Сортировка по имени — для удобного выбора в диалоге.
     */
    @Query("select v from GridFormView v " +
        "where v.formKey = :formKey and (v.shared = true or v.createdBy = :username) " +
        "order by v.name")
    List<GridFormView> findVisibleViews(@Param("formKey") String formKey, @Param("username") String username);

    Optional<GridFormView> findByIdAndFormKey(Long id, String formKey);
}
