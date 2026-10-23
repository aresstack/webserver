package com.aresstack.webserver.domain;

import java.util.Objects;

/**
 * One permission in a service's scope catalogue.
 *
 * <p>{@code registered} only records where the entry came from: a service that
 * reported it over the internal registration endpoint, or an operator who typed it
 * in. Both are equal citizens — automatic discovery is a convenience, never a
 * precondition, so a plugin that reports nothing stays fully configurable by hand.
 */
public record ScopeEntry(String name, String description, boolean registered,
                         ScopeDecision decision) {

    public ScopeEntry {
        Objects.requireNonNull(name, "name");
        description = description == null ? "" : description.trim();
        decision = decision == null ? ScopeDecision.DEFAULT : decision;
        name = name.trim();
        if (name.isEmpty() || name.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("Invalid scope name: '" + name + "'");
        }
    }

    /** A scope the operator added by hand. */
    public static ScopeEntry manual(String name) {
        return new ScopeEntry(name, "", false, ScopeDecision.DEFAULT);
    }

    /** A scope a service reported as available. */
    public static ScopeEntry registered(String name, String description) {
        return new ScopeEntry(name, description, true, ScopeDecision.DEFAULT);
    }

    public ScopeEntry withDecision(ScopeDecision updated) {
        return new ScopeEntry(name, description, registered, updated);
    }

    /** True when this scope may be granted under the given service-wide rule. */
    public boolean allowed(boolean allowByDefault) {
        return switch (decision) {
            case ALLOW -> true;
            case DENY -> false;
            case DEFAULT -> allowByDefault;
        };
    }
}
