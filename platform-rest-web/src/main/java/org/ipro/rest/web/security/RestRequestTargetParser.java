package org.ipro.rest.web.security;

import java.util.regex.Pattern;

/**
 * Canonical parser of the versioned REST namespace path (F-REST-READ-4 §5.2).
 *
 * <p>Only the two published forms parse: {@code /api/v{major}/{resource}} (LIST) and
 * {@code /api/v{major}/{resource}/{id}} (DETAIL), where major is a positive decimal number
 * without leading zeros and the resource matches {@code ^[a-z][a-z0-9-]*$}. Anything else —
 * a non-canonical major, an overlong numeric major, an empty id, extra segments, encoded
 * separators — is not a route form at all and becomes the 404 branch without touching grants
 * or the business pipeline.</p>
 */
public final class RestRequestTargetParser {

    private static final Pattern MAJOR = Pattern.compile("[1-9][0-9]*");
    private static final Pattern RESOURCE = Pattern.compile("[a-z][a-z0-9-]*");

    /** null — the path is not a canonical published-address form (404-equivalent). */
    public RestRequestTarget parse(String requestUri) {
        if (requestUri == null) {
            return null;
        }
        String[] segments = requestUri.split("/", -1);
        if (segments.length != 4 && segments.length != 5) {
            return null;
        }
        if (!"api".equals(segments[1])) {
            return null;
        }
        String majorToken = segments[2];
        if (majorToken.length() < 2 || majorToken.charAt(0) != 'v') {
            return null;
        }
        String digits = majorToken.substring(1);
        if (!MAJOR.matcher(digits).matches()) {
            return null;
        }
        int major;
        try {
            major = Integer.parseInt(digits);
        } catch (NumberFormatException overflow) {
            return null;
        }
        if (!RESOURCE.matcher(segments[3]).matches()) {
            return null;
        }
        if (segments.length == 4) {
            return new RestRequestTarget(segments[3], major, null);
        }
        if (segments[4].isEmpty()) {
            return null;
        }
        return new RestRequestTarget(segments[3], major, segments[4]);
    }
}
