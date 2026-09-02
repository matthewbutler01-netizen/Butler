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
 * Opt-in league-scoped strict refresh. The normal DynastyProcess refresh remains permissive.
 * This path validates the selected league against the exact downloaded payloads and only
 * persists when every rostered player in that league is mapped.
 */
public final class DynastyProcessLeagueStrictRefresh {
    private final DynastyProcessValueImporter importer;
    private final DynastyProcessLeaguePreviewAnalyzer leaguePreview;
    private final HttpClient http;

    public DynastyProcessLeagueStrictRefresh(Database database) {
        this(database, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build());
    }

    DynastyProcessLeagueStrictRefresh(Database database, HttpClient http) {
        Objects.requireNonNull(database, "database must not be null");
        this.importer = new DynastyProcessValueImporter(database);
        this.leaguePreview = new DynastyProcessLeaguePreviewAnalyzer(database);
        this.http = Objects.requireNonNull(http, "http must not be null");
    }

    public RefreshResult refresh(String leagueId)
        throws IOException, InterruptedException, SQLException {
        String valueCsv = download(DynastyProcessValueImporter.VALUES_URI);
        String idCsv = download(DynastyProcessValueImporter.PLAYER_IDS_URI);
        return importCsv(leagueId, valueCsv, idCsv);
    }

    public RefreshResult importCsv(String leagueId, String valueCsv, String idCsv) throws SQLException {
        var preview = importer.previewCsv(valueCsv, idCsv);
        var league = leaguePreview.analyze(leagueId, preview);
        DynastyProcessLeagueRefreshGuard.requireReady(league);
        var imported = importer.importCsv(valueCsv, idCsv);
        return new RefreshResult(imported, league);
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

    public record RefreshResult(DynastyProcessValueImporter.ImportResult importResult,
                                DynastyProcessLeaguePreviewAnalyzer.LeaguePreview leaguePreview) {}
}
