package org.ip.service;

import jakarta.validation.Validator;
import org.ip.model.Journal;
import org.ip.repository.JournalRepository;
import org.springframework.stereotype.Service;
import org.ipro.crud.AbstractBaseService;

/**
 * C4.4 pilot: search делегируется canonical engine (ADR-0007 §7). Здесь остаётся только
 * typed CRUD-хвост; repository {@code searchByTerm} не нужен.
 */
@Service
public class JournalService extends AbstractBaseService<Journal, Long> {

    public JournalService(JournalRepository repository, Validator validator) {
        super(repository, validator);
    }
}
