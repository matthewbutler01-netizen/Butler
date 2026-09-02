package io.butler.bet.intelligence;

import io.butler.bet.data.Database;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;

/**
 * Opt-in strict refresh path. The normal DynastyProcess refresh remains permissive.
 * Strict refresh previews and validates the exact downloaded CSV payloads before persisting
 * those same payloads, so a PARTIAL or BLOCKED provider snapshot cannot be written.
 */
public final class DynastyProcessStrictRefresh {
    private final DynastyProcessValueImporter importer;
    private final HttpClient http;

    public DynastyProcessStrictRefresh(Database database) {
        this(database, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build());
    }

    DynastyProcessStrictRefresh(Database database, HttpClient http) {
        Objects.requireNonNull(database, "database must not be null");
        this.importer = new DynastyProcessValueImporter(database);
        this.http = Objects.requireNonNull(http, "http must not be null");
    }

    public DynastyProcessValueImporter.ImportResult refresh()
        throws IOException, InterruptedException, SQLException {
        String valueCsv = download(DynastyProcessValueImporter.VALUES_URI);
        String idCsv = download(DynastyProcessValueImporter.PLAYER_IDS_URI);
        return importCsv(valueCsv, idCsv);
    }

    public DynastyProcessValueImporter.ImportResult importCsv(String valueCsv, String idCsv)
        throws SQLException {
        var preview = importer.previewCsv(valueCsv, idCsv);
        DynastyProcessRefreshGuard.requireReady(preview.diagnostics());
        return importer.importCsv(valueCsv, idCsv);
    }

    private String download(URI uri) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(60))
            .header("User-Agent", "Butler-FF/0.1")
            .GET()
            .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("DynastyProcess download failed with HTTP " + response.statusCode() + ": " + uri);
        }
        return response.body();
    }
}
