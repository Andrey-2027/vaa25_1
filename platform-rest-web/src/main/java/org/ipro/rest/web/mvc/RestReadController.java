package org.ipro.rest.web.mvc;

import org.ipro.rest.gateway.RestReadGateway;
import org.ipro.rest.service.RestDetailResult;
import org.ipro.rest.service.RestPageResult;
import org.ipro.rest.service.RestReadException;
import org.ipro.rest.service.RestReadOutcome;
import jakarta.servlet.http.HttpServletRequest;
import org.ipro.rest.web.codec.RestJsonCodec;
import org.ipro.rest.web.query.RestQueryParser;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Single MVC controller of the versioned REST read namespace (F-REST-READ-4 §5.3).
 *
 * <p>Routing is driven only by the published {@code (resource, major)} pair and happens inside the
 * gateway: path variables stay {@code String}, and the raw multi-value query map is handed to the
 * gateway parser callback unchanged. The controller never resolves a resource itself, never builds
 * a pageable and never binds a validated DTO, so query parsing runs strictly after the
 * authentication/resource/API/entity gates. Successful envelopes are serialized by the codec
 * before the response is returned.</p>
 */
@RestController
public class RestReadController {

    private static final Pattern CANONICAL_MAJOR = Pattern.compile("[1-9][0-9]*");

    private final RestReadGateway gateway;
    private final RestQueryParser parser;
    private final RestJsonCodec codec;

    public RestReadController(RestReadGateway gateway, RestQueryParser parser, RestJsonCodec codec) {
        this.gateway = Objects.requireNonNull(gateway, "gateway must not be null");
        this.parser = Objects.requireNonNull(parser, "parser must not be null");
        this.codec = Objects.requireNonNull(codec, "codec must not be null");
    }

    /** LIST: {@code GET /api/v{major}/{resource}}. */
    @GetMapping(path = "/api/v{major}/{resource}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> list(@PathVariable String major, @PathVariable String resource,
                                       HttpServletRequest request) {
        int parsedMajor = canonicalMajor(major);
        Map<String, String[]> query = request.getParameterMap();
        RestPageResult result = gateway.list(resource, parsedMajor,
            contract -> parser.parseList(contract, query));
        return json(codec.writeList(result));
    }

    /** DETAIL: {@code GET /api/v{major}/{resource}/{id}}. */
    @GetMapping(path = "/api/v{major}/{resource}/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> detail(@PathVariable String major, @PathVariable String resource,
                                         @PathVariable String id, HttpServletRequest request) {
        int parsedMajor = canonicalMajor(major);
        Map<String, String[]> query = request.getParameterMap();
        RestDetailResult result = gateway.detail(resource, parsedMajor,
            contract -> parser.parseDetail(contract, query, id));
        return json(codec.writeDetail(result));
    }

    /**
     * Non-canonical or unknown route forms are a 404-equivalent, not a 400: the namespace grammar
     * rejects them before any business decision, and the web layer never coerces {@code v01} or
     * {@code v1x} into a published major.
     */
    private static int canonicalMajor(String major) {
        if (major == null || !CANONICAL_MAJOR.matcher(major).matches()) {
            throw new RestReadException(RestReadOutcome.RESOURCE_NOT_FOUND, "Resource not found");
        }
        try {
            return Integer.parseInt(major);
        } catch (NumberFormatException overflow) {
            throw new RestReadException(RestReadOutcome.RESOURCE_NOT_FOUND, "Resource not found");
        }
    }

    private static ResponseEntity<byte[]> json(byte[] body) {
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .cacheControl(CacheControl.noStore())
            .body(body);
    }
}
