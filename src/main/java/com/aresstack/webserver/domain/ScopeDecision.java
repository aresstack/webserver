package com.aresstack.webserver.domain;

/**
 * Operator decision for a single scope.
 *
 * <p>{@link #DEFAULT} follows the service-wide rule, so a newly discovered scope
 * inherits it without silently overriding an explicit choice the operator already
 * made.
 */
public enum ScopeDecision {
    DEFAULT,
    ALLOW,
    DENY
}
