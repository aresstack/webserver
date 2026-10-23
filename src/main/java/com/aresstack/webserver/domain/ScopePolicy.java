package com.aresstack.webserver.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Which permissions a protected service may hand out.
 *
 * <p>The catalogue is the set of <em>known</em> scopes — reported by the service or
 * entered by the operator. {@code allowByDefault} is the service-wide rule, and each
 * entry may override it. A scope that is not in the catalogue is never granted, so an
 * OAuth client cannot widen its own rights by simply asking for something unknown;
 * {@code Allow all} means "everything we know about", not "anything requested".
 */
public record ScopePolicy(boolean allowByDefault, List<ScopeEntry> entries) {

    public ScopePolicy {
        Objects.requireNonNull(entries, "entries");
        Map<String, ScopeEntry> unique = new LinkedHashMap<>();
        for (ScopeEntry entry : entries) {
            Objects.requireNonNull(entry, "entry");
            unique.put(entry.name(), entry);
        }
        entries = List.copyOf(unique.values());
    }

    /** Default for a fresh service: generous, but limited to known scopes. */
    public static ScopePolicy allowAll(List<String> scopeNames) {
        List<ScopeEntry> entries = new ArrayList<>();
        for (String name : scopeNames) {
            entries.add(ScopeEntry.manual(name));
        }
        return new ScopePolicy(true, entries);
    }

    /** The scopes a token for this service may carry. */
    public List<String> effectiveScopes() {
        List<String> effective = new ArrayList<>();
        for (ScopeEntry entry : entries) {
            if (entry.allowed(allowByDefault)) {
                effective.add(entry.name());
            }
        }
        return List.copyOf(effective);
    }

    public boolean knows(String scope) {
        return entries.stream().anyMatch(entry -> entry.name().equals(scope));
    }

    /**
     * Folds a service's self-reported catalogue in.
     *
     * <p>A registration describes available capabilities; it must never change what
     * the operator decided. Known scopes therefore keep their decision and only gain
     * the description and the registered marker, while new ones arrive as
     * {@link ScopeDecision#DEFAULT} and follow the service-wide rule.
     *
     * <p>Manual entries are kept even when a service stops reporting them: removing
     * an operator's configuration because a plugin went quiet would be destructive.
     */
    public ScopePolicy mergeRegistered(List<ScopeEntry> reported) {
        Map<String, ScopeEntry> merged = new LinkedHashMap<>();
        for (ScopeEntry entry : entries) {
            merged.put(entry.name(), entry);
        }
        for (ScopeEntry incoming : reported) {
            ScopeEntry existing = merged.get(incoming.name());
            if (existing == null) {
                merged.put(incoming.name(), new ScopeEntry(incoming.name(),
                        incoming.description(), true, ScopeDecision.DEFAULT));
            } else {
                String description = incoming.description().isEmpty()
                        ? existing.description() : incoming.description();
                merged.put(incoming.name(), new ScopeEntry(existing.name(), description,
                        true, existing.decision()));
            }
        }
        return new ScopePolicy(allowByDefault, List.copyOf(merged.values()));
    }

    public ScopePolicy withAllowByDefault(boolean allow) {
        return new ScopePolicy(allow, entries);
    }

    public ScopePolicy withDecision(String scope, ScopeDecision decision) {
        List<ScopeEntry> updated = new ArrayList<>();
        boolean found = false;
        for (ScopeEntry entry : entries) {
            if (entry.name().equals(scope)) {
                updated.add(entry.withDecision(decision));
                found = true;
            } else {
                updated.add(entry);
            }
        }
        if (!found) {
            updated.add(ScopeEntry.manual(scope).withDecision(decision));
        }
        return new ScopePolicy(allowByDefault, updated);
    }
}
