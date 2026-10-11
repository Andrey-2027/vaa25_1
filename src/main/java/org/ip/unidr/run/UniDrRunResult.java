package org.ip.unidr.run;

import org.ip.unidr.model.UniDrDefinition;
import org.ip.unidr.query.UniDrQueryService;
import org.ip.unidr.render.UniDrRenderedReport;

import java.time.Instant;
import java.util.UUID;

/** One authenticated run/session: detached data and a rendered report, without a global cache. */
public record UniDrRunResult(UUID id, String owner, UniDrDefinition definition,
                             Instant requestedAt, Instant completedAt, UniDrQueryService.Data data, UniDrRenderedReport report) {}
