package org.ip.views.components;

import java.util.List;

@FunctionalInterface
public interface SearchFunction<T> {
    List<T> search(String term);
}
