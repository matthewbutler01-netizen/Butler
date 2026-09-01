package io.butler.bet.sleeper;

import java.io.IOException;
import java.net.URI;

public final class SleeperApiException extends IOException {
    private final int statusCode;
    private final URI uri;

    public SleeperApiException(int statusCode, URI uri, String responseBody) {
        super("Sleeper API request failed with HTTP " + statusCode + " for " + uri + formatBody(responseBody));
        this.statusCode = statusCode;
        this.uri = uri;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public URI getUri() {
        return uri;
    }

    private static String formatBody(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String compact = body.replaceAll("\\s+", " ").trim();
        if (compact.length() > 300) {
            compact = compact.substring(0, 300) + "...";
        }
        return ": " + compact;
    }
}
