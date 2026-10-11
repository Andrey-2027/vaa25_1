package org.ipro.rest.web.problem;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.ipro.rest.service.RestReadException;
import org.ipro.rest.web.mvc.RestReadController;
import org.ipro.rest.web.query.RestQueryException;
import org.ipro.rest.web.rls.RestRequestContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.handler.AbstractHandlerExceptionResolver;

import java.util.Objects;

/**
 * MVC advice of the REST read namespace (F-REST-READ-4 §6).
 *
 * <p>It maps the strict parser failures and the read-pipeline outcomes to Problem Details through
 * the single {@link RestProblemWriter}, shared with the namespace filters and the security entry
 * points. A 401 always carries the Basic challenge; responses are written only after the body is
 * complete, so an error never follows a partial successful JSON document.</p>
 */
@RestControllerAdvice(assignableTypes = RestReadController.class)
public class RestReadExceptionHandler extends AbstractHandlerExceptionResolver {

    private final RestProblemWriter writer;

    public RestReadExceptionHandler(RestProblemWriter writer) {
        this.writer = Objects.requireNonNull(writer, "writer must not be null");
        // Also cover MVC framework errors with no selected handler, exclusively on a marked request.
        setOrder(-1);
    }

    @ExceptionHandler(RestQueryException.class)
    public ResponseEntity<byte[]> handleQuery(RestQueryException failure, HttpServletRequest request) {
        return writer.failureResponse(failure, request.getRequestURI());
    }

    @ExceptionHandler(RestReadException.class)
    public ResponseEntity<byte[]> handleRead(RestReadException failure, HttpServletRequest request) {
        return writer.failureResponse(failure, request.getRequestURI());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<byte[]> handleUnexpected(Exception failure, HttpServletRequest request) {
        return writer.failureResponse(failure, request.getRequestURI());
    }

    @Override
    protected ModelAndView doResolveException(HttpServletRequest request, HttpServletResponse response,
                                             Object handler, Exception failure) {
        if (!RestRequestContext.isActive() || response.isCommitted()) return null;
        try {
            writer.writeFailure(response, failure, request.getRequestURI());
            return new ModelAndView();
        } catch (java.io.IOException transportFailure) {
            // Preserve transport failures; an already committed/closed connection cannot be rewritten.
            failure.addSuppressed(transportFailure);
            return null;
        }
    }
}
