package io.butler.bet.sleeper;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperClientBf748Test {
    @Test
    void defaultClientsReuseExactlyOneProcessScopedHttpTransport() throws Exception {
        Field clientField = SleeperClient.class.getDeclaredField("httpClient");
        clientField.setAccessible(true);

        SleeperClient first = new SleeperClient();
        SleeperClient second = new SleeperClient();

        assertSame(clientField.get(first), clientField.get(second));
        long staticHttpClients = Arrays.stream(SleeperClient.class.getDeclaredFields())
            .filter(field -> Modifier.isStatic(field.getModifiers()))
            .filter(field -> HttpClient.class.equals(field.getType()))
            .count();
        assertEquals(1L, staticHttpClients);
    }

    @Test
    void prewarmTimeoutIsStrictlyBoundedBeforeAnyNetworkAttempt() {
        assertThrows(IllegalArgumentException.class,
            () -> SleeperClient.prewarmSharedTransportBestEffort(Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
            () -> SleeperClient.prewarmSharedTransportBestEffort(Duration.ofMillis(-1)));
        assertThrows(IllegalArgumentException.class,
            () -> SleeperClient.prewarmSharedTransportBestEffort(Duration.ofSeconds(6)));
    }

    @Test
    void sourceSharesOnlyTransportAndKeepsRequestsAndResponsesPerCall() throws Exception {
        String source = source("bet/bet-cli/src/main/java/io/butler/bet/sleeper/SleeperClient.java");

        assertTrue(source.contains("private static final HttpClient SHARED_HTTP_CLIENT"));
        assertTrue(source.contains("this(SHARED_HTTP_CLIENT, DEFAULT_BASE_URI);"));
        assertTrue(source.contains("new SleeperClient(SHARED_HTTP_CLIENT, DEFAULT_BASE_URI).get(\"state/nfl\", requestTimeout)"));
        assertTrue(source.contains("HttpRequest request = HttpRequest.newBuilder"));
        assertTrue(source.contains("HttpResponse<String> response = httpClient.send"));
        assertFalse(source.contains("static final HttpResponse"));
        assertFalse(source.contains("static String response"));
        assertFalse(source.contains("static final String response"));
    }

    private static String source(String relativePath) throws Exception {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; depth < 7 && current != null; depth++) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new IllegalStateException("BF-748 test could not locate " + relativePath);
    }
}
