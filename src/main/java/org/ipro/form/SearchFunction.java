package org.ipro.form;

import java.util.List;

@FunctionalInterface
public interface SearchFunction<T> {
    List<T> search(String term);
}
