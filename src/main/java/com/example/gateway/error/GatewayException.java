package com.example.gateway.error;

import com.example.gateway.audit.AuditRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ends a request with an error. Anywhere in the pipeline, "reject" means "throw this".
 *
 * <p>{@link #writeTo} renders it as RFC 9457 {@code application/problem+json} and records it in the
 * audit record, so every rejection looks the same and is audited the same way.
 */
public class GatewayException extends RuntimeException {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ErrorCode code;
    private final Map<String, String> headers;

    public GatewayException(ErrorCode code, String detail) {
        this(code, detail, Map.of());
    }

    public GatewayException(ErrorCode code, String detail, Map<String, String> headers) {
        // No stack trace: rejections are normal traffic (a 429 storm), not bugs.
        super(detail, null, false, false);
        this.code = code;
        this.headers = Map.copyOf(headers);
    }

    public ErrorCode code() {
        return code;
    }

    public Map<String, String> headers() {
        return headers;
    }

    public void writeTo(HttpServletRequest request, HttpServletResponse response) throws IOException {
        AuditRecord audit = AuditRecord.of(request);
        if (audit != null) {
            audit.reject(code);
        }
        if (response.isCommitted()) {
            return; // bytes already went to the client; nothing more to say
        }
        response.resetBuffer();
        response.setStatus(code.status);
        headers.forEach(response::setHeader);
        response.setContentType("application/problem+json");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "urn:gateway:problem:" + code.name().toLowerCase().replace('_', '-'));
        body.put("title", code.title);
        body.put("status", code.status);
        body.put("detail", getMessage());
        body.put("code", code.name());
        body.put("requestId", audit == null ? null : audit.requestId());
        JSON.writeValue(response.getOutputStream(), body);
    }
}
