package com.example.gateway.audit;

import java.util.Map;

/** Destination for audit entries. Implementations must never block the request thread. */
public interface AuditSink {

    void submit(Map<String, Object> entry);
}
