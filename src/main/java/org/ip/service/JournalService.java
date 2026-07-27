package org.ip.service;

import jakarta.validation.Validator;
import org.ip.model.Journal;
import org.ip.repository.JournalRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class JournalService extends AbstractBaseService<Journal, Long> {

    private final JournalRepository journalRepository;

    public JournalService(JournalRepository repository, Validator validator) {
        super(repository, validator);
        this.journalRepository = repository;
    }

    @Override
    public List<Journal> search(String term) {
        if (term == null || term.isEmpty()) {
            return findAll();
        }
        return journalRepository.searchByTerm(term, PageRequest.of(0, 100));
    }
}
