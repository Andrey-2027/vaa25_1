package org.ip.security;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.ipro.rls.RlsDimensionValueSource;
import org.ipro.rls.RlsFilterActivator;
import org.ip.model.Journal;
import org.ip.repository.JournalRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class JournalDimensionValueSource implements RlsDimensionValueSource<Journal> {

    private final JournalRepository journalRepository;
    private final RlsFilterActivator rlsFilterActivator;

    @PersistenceContext
    private EntityManager entityManager;

    public JournalDimensionValueSource(JournalRepository journalRepository, RlsFilterActivator rlsFilterActivator) {
        this.journalRepository = journalRepository;
        this.rlsFilterActivator = rlsFilterActivator;
    }

    @Override
    public String dimension() {
        return "JOURNAL";
    }

    @Override
    public List<Journal> allIgnoringRls() {
        return rlsFilterActivator.withRlsDisabled(entityManager, () -> journalRepository.findAll(Sort.by("code")));
    }

    @Override
    public Long idOf(Journal value) {
        return value.getId();
    }

    @Override
    public String displayCode(Journal value) {
        return value.getCode();
    }

    @Override
    public String displayName(Journal value) {
        return value.getName();
    }
}