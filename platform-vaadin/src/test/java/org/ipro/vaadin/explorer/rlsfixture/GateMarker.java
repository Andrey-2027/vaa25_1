package org.ipro.vaadin.explorer.rlsfixture;

import org.ipro.rls.RlsDimension;
import org.ipro.rls.RlsDimensionKind;

/**
 * Проба измерения-ворот, носителем которого является <b>интерфейс-маркер</b>, а не сущность:
 * {@code CHECK_ONLY}-измерения вроде {@code SETTINGS:*} и {@code REPORTS:*} объявляются так, живут
 * в реестре, но карточке сущности не принадлежат — их читает сводка подсистем.
 *
 * <p>Носителя-сущности у измерения нет, поэтому привязывать его к типу «по имени пакета» нельзя:
 * измерение принадлежит маркеру.</p>
 */
@RlsDimension(value = "PROBE_SETTINGS_GATE", kind = RlsDimensionKind.CHECK_ONLY)
public interface GateMarker {
}
