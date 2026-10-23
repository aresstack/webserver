package com.aresstack.webserver.launcher;

import com.aresstack.webserver.application.ScopeRegistration;
import com.aresstack.webserver.domain.ScopeEntry;
import com.aresstack.webserver.domain.WebServerConfiguration;
import com.aresstack.webserver.infrastructure.oauth.OAuthRuntime;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Persists a service's self-reported scopes in headless mode.
 *
 * <p>Headless is the production mode here, so a registration has to be written to the
 * configuration and survive a restart rather than merely being acknowledged. The
 * merge keeps Allow/Deny decisions and manual entries, and the commit is serialised
 * against the configuration watcher so a registration and an external edit cannot
 * overwrite each other.
 *
 * <p>A rejected change is reported as "not changed" instead of propagating: a
 * reporting service must not be taken down because the configuration could not be
 * applied.
 */
final class HeadlessScopeRegistrationSink implements OAuthRuntime.ScopeRegistrationSink {

    private final AtomicReference<WebServerConfiguration> applied;
    private final Consumer<WebServerConfiguration> commit;
    private final Consumer<String> log;
    private final Object lock;

    HeadlessScopeRegistrationSink(AtomicReference<WebServerConfiguration> applied,
                                  Consumer<WebServerConfiguration> commit,
                                  Consumer<String> log,
                                  Object lock) {
        this.applied = applied;
        this.commit = commit;
        this.log = log;
        this.lock = lock;
    }

    @Override
    public boolean register(String host, List<ScopeEntry> scopes) {
        synchronized (lock) {
            Optional<WebServerConfiguration> merged =
                    ScopeRegistration.merge(applied.get(), host, scopes);
            if (merged.isEmpty()) {
                // Unknown host, unprotected site, or nothing new: not an error.
                return false;
            }
            try {
                commit.accept(merged.get());
                applied.set(merged.get());
                log.accept("Registered " + scopes.size() + " scope(s) for " + host);
                return true;
            } catch (RuntimeException failure) {
                log.accept("Scope registration for " + host + " rejected: "
                        + failure.getClass().getSimpleName());
                return false;
            }
        }
    }
}
