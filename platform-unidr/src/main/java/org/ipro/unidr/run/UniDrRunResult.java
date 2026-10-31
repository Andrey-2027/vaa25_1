package org.ipro.unidr.run;

import org.ipro.unidr.model.UniDrDefinition;
import org.ipro.unidr.query.UniDrQueryService;
import org.ipro.unidr.render.UniDrRenderedReport;

import java.time.Instant;
import java.util.UUID;

/** One authenticated run/session: detached data and a rendered report, without a global cache. */
public record UniDrRunResult(UUID id, String owner, UniDrDefinition definition,
                             Instant requestedAt, Instant completedAt, UniDrQueryService.Data data, UniDrRenderedReport report) {}
