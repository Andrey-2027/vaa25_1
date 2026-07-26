package org.ip.repository;

import org.ip.model.ReceivingDocument;
import org.ip.model.ReceivingDocumentItem;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReceivingDocumentItemRepository extends JpaRepository<ReceivingDocumentItem, Long> {

    /**
     * @EntityGraph подгружает nomenclature (@ManyToOne LAZY) сразу — без него
     * ItemTable.commit() падает с LazyInitializationException при рендере грида
     * табличной части: findByParent() выполняется в собственной короткой транзакции,
     * которая закрывается до refreshAll(), а прокси nomenclature остаётся неинициализирован.
     */
    @EntityGraph(attributePaths = {"nomenclature"})
    List<ReceivingDocumentItem> findByDocumentOrderByLineNumber(ReceivingDocument document);
}
