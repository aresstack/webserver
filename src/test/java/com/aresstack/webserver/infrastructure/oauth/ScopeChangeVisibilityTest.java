package com.aresstack.webserver.infrastructure.oauth;

import com.aresstack.webserver.domain.OAuthClientConfiguration;
import com.aresstack.webserver.domain.OAuthConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A withdrawn approval must take effect for every request handled afterwards.
 *
 * <p>Since a scope-only change no longer restarts the service, the approved scopes
 * are now written while request threads are already running. They used to live in two
 * plain, non-volatile fields, so a handler thread had no guaranteed way to see the
 * new values - a scope the operator had just denied could keep being granted. They
 * are now one immutable snapshot behind a volatile field.
 */
class ScopeChangeVisibilityTest {

    private static final String HOST = "pyloros.example.org";
    private static final String REDIRECT = "https://chatgpt.com/connector_oauth_redirect";
    private static final String CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    @TempDir Path root;
    private OAuthRuntime runtime;

    @BeforeEach
    void start() {
        runtime = new OAuthRuntime(root, message -> { }, 0);
        apply(List.of("pyloros", "mcp"));
    }

    @AfterEach
    void stop() {
        if (runtime != null) {
            runtime.stop();
        }
    }

    private void apply(List<String> approved) {
        runtime.apply(new OAuthConfiguration(true, "https://auth.example.org",
                        List.of(new OAuthClientConfiguration("app", "secret",
                                List.of("https://chatgpt.com")))),
                Map.of(HOST, approved));
    }

    @Test
    void everyRequestAfterAWithdrawalIsRefused() throws Exception {
        assertEquals(302, authorize("mcp").statusCode(), "approved up front");

        apply(List.of("pyloros"));

        for (int attempt = 0; attempt < 50; attempt++) {
            HttpResponse<String> response = authorize("mcp");
            assertEquals(400, response.statusCode(),
                    "attempt " + attempt + " still granted a withdrawn scope: "
                            + response.body());
            assertTrue(response.body().contains("invalid_scope"), response.body());
        }
        assertEquals(302, authorize("pyloros").statusCode(),
                "the remaining approval must keep working");
    }

    @Test
    void aWithdrawalDuringConcurrentRequestsIsNeverMissed() throws Exception {
        int threads = 6;
        CountDownLatch running = new CountDownLatch(threads);
        CountDownLatch withdrawn = new CountDownLatch(1);
        AtomicBoolean grantedAfterWithdrawal = new AtomicBoolean(false);
        AtomicInteger checkedAfterWithdrawal = new AtomicInteger();

        List<Thread> workers = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Thread worker = new Thread(() -> {
                try {
                    running.countDown();
                    // Keep hammering the endpoint; the scope is withdrawn underneath.
                    while (checkedAfterWithdrawal.get() < 60) {
                        boolean alreadyWithdrawn = withdrawn.getCount() == 0;
                        HttpResponse<String> response = authorize("mcp");
                        if (alreadyWithdrawn) {
                            checkedAfterWithdrawal.incrementAndGet();
                            if (response.statusCode() == 302) {
                                grantedAfterWithdrawal.set(true);
                            }
                        }
                    }
                } catch (Exception failure) {
                    throw new RuntimeException(failure);
                }
            }, "scope-visibility-" + i);
            worker.setDaemon(true);
            workers.add(worker);
            worker.start();
        }

        assertTrue(running.await(10, TimeUnit.SECONDS), "workers must start");
        apply(List.of("pyloros"));
        withdrawn.countDown();

        for (Thread worker : workers) {
            worker.join(TimeUnit.SECONDS.toMillis(30));
        }

        assertTrue(checkedAfterWithdrawal.get() >= 60,
                "the workers must have observed enough requests after the withdrawal");
        assertTrue(!grantedAfterWithdrawal.get(),
                "no request handled after the withdrawal may still grant the scope");
    }

    private HttpResponse<String> authorize(String scope) throws Exception {
        String query = "?response_type=code&client_id=app"
                + "&redirect_uri=" + java.net.URLEncoder.encode(REDIRECT, StandardCharsets.UTF_8)
                + "&scope=" + java.net.URLEncoder.encode(scope, StandardCharsets.UTF_8)
                + "&code_challenge=" + CHALLENGE + "&code_challenge_method=S256"
                + "&resource=" + java.net.URLEncoder.encode("https://" + HOST + "/",
                        StandardCharsets.UTF_8);
        return http.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + runtime.port()
                                + "/oauth/authorize" + query))
                        .header("Host", HOST).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
