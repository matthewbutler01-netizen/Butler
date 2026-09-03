package io.butler.bet.intelligence;

import io.butler.bet.data.AgingModelPlayerProfileRepository;
import io.butler.bet.data.Database;
import io.butler.bet.domain.AgingModelPlayerProfile;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Imports the broad nflverse player universe for aging-model training, independent of fantasy rosters. */
public final class NflverseAgingModelPlayerImporter {
    public static final String SOURCE = "nflverse-players";
    public static final URI PLAYERS_URI = URI.create(
        "https://github.com/nflverse/nflverse-data/releases/download/players/players.csv");

    private final AgingModelPlayerProfileRepository profiles;
    private final HttpClient http;

    public NflverseAgingModelPlayerImporter(Database database) {
        this(database, HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build());
    }

    NflverseAgingModelPlayerImporter(Database database, HttpClient http) {
        Objects.requireNonNull(database, "database must not be null");
        this.profiles = new AgingModelPlayerProfileRepository(database);
        this.http = Objects.requireNonNull(http, "http must not be null");
    }

    public ImportResult refresh() throws IOException, InterruptedException, SQLException {
        return processCsv(download(), LocalDate.now(ZoneOffset.UTC), true);
    }

    public ImportResult preview() throws IOException, InterruptedException, SQLException {
        return processCsv(download(), LocalDate.now(ZoneOffset.UTC), false);
    }

    public ImportResult importCsv(String csv, LocalDate asOfDate) throws SQLException {
        return processCsv(csv, asOfDate, true);
    }

    public ImportResult previewCsv(String csv, LocalDate asOfDate) throws SQLException {
        return processCsv(csv, asOfDate, false);
    }

    private ImportResult processCsv(String csv, LocalDate asOfDate, boolean persist) throws SQLException {
        Objects.requireNonNull(asOfDate, "asOfDate must not be null");
        List<Map<String, String>> rows = Csv.parse(requireText(csv, "csv"));
        if (rows.isEmpty()) throw new IllegalArgumentException("nflverse players contain no data rows");

        Map<String, AgingModelPlayerProfile> byGsis = new LinkedHashMap<>();
        int rowsWithBirthDate = 0;
        for (Map<String, String> row : rows) {
            requireColumn(row, "gsis_id");
            requireColumn(row, "display_name");
            requireColumn(row, "birth_date");
            requireColumn(row, "position");
            String gsis = normalized(row.get("gsis_id"));
            if (gsis == null) continue;
            String displayName = firstText(row.get("display_name"), row.get("football_name"), gsis);
            String position = firstText(row.get("position"), row.get("position_group"), "UNKNOWN");
            LocalDate birthDate = parseOptionalDate(row.get("birth_date"), gsis);
            if (birthDate != null) rowsWithBirthDate++;
            AgingModelPlayerProfile profile = new AgingModelPlayerProfile(
                gsis, displayName, birthDate, position, SOURCE, asOfDate);
            AgingModelPlayerProfile existing = byGsis.putIfAbsent(gsis, profile);
            if (existing != null && !sameProviderFacts(existing, profile)) {
                throw new IllegalArgumentException("conflicting nflverse player rows for GSIS id: " + gsis);
            }
        }
        if (byGsis.isEmpty()) throw new IllegalArgumentException("nflverse players contain no usable GSIS identities");

        int written = persist ? profiles.saveAll(byGsis.values()) : 0;
        int usableBirthDates = (int) byGsis.values().stream().filter(p -> p.birthDate() != null).count();
        return new ImportResult(asOfDate, persist, rows.size(), byGsis.size(), rowsWithBirthDate,
            usableBirthDates, written);
    }

    private String download() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(PLAYERS_URI).timeout(Duration.ofSeconds(60))
            .header("User-Agent", "Butler-FF/0.1").GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("nflverse players unavailable (HTTP " + response.statusCode() + "): " + PLAYERS_URI);
        }
        return response.body();
    }

    private static boolean sameProviderFacts(AgingModelPlayerProfile left, AgingModelPlayerProfile right) {
        return left.displayName().equals(right.displayName())
            && Objects.equals(left.birthDate(), right.birthDate())
            && left.position().equals(right.position());
    }

    private static LocalDate parseOptionalDate(String value, String gsis) {
        String normalized = normalized(value);
        if (normalized == null) return null;
        try {
            return LocalDate.parse(normalized);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("invalid nflverse birth_date for " + gsis + ": " + value, e);
        }
    }

    private static void requireColumn(Map<String, String> row, String column) {
        if (!row.containsKey(column)) throw new IllegalArgumentException("missing nflverse players column: " + column);
    }

    private static String firstText(String... values) {
        for (String value : values) {
            String normalized = normalized(value);
            if (normalized != null) return normalized;
        }
        throw new IllegalArgumentException("no usable text value");
    }

    private static String normalized(String value) {
        if (value == null || value.isBlank() || value.equalsIgnoreCase("NA")) return null;
        return value.trim();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }

    public record ImportResult(LocalDate asOfDate, boolean persisted, int providerRows,
                               int uniqueGsisPlayers, int providerRowsWithBirthDate,
                               int uniquePlayersWithBirthDate, int snapshotsWritten) {}

    private static final class Csv {
        private Csv() {}

        static List<Map<String, String>> parse(String csv) {
            List<List<String>> rows = rows(csv);
            if (rows.isEmpty()) return List.of();
            List<String> header = rows.getFirst();
            List<Map<String, String>> result = new ArrayList<>();
            for (int i = 1; i < rows.size(); i++) {
                List<String> values = rows.get(i);
                if (values.size() == 1 && values.getFirst().isBlank()) continue;
                Map<String, String> row = new LinkedHashMap<>();
                for (int j = 0; j < header.size(); j++) {
                    row.put(header.get(j).trim(), j < values.size() ? values.get(j) : "");
                }
                result.add(row);
            }
            return result;
        }

        private static List<List<String>> rows(String csv) {
            List<List<String>> rows = new ArrayList<>();
            List<String> row = new ArrayList<>();
            StringBuilder cell = new StringBuilder();
            boolean quoted = false;
            for (int i = 0; i < csv.length(); i++) {
                char c = csv.charAt(i);
                if (c == '"') {
                    if (quoted && i + 1 < csv.length() && csv.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else quoted = !quoted;
                } else if (c == ',' && !quoted) {
                    row.add(cell.toString());
                    cell.setLength(0);
                } else if ((c == '\n' || c == '\r') && !quoted) {
                    if (c == '\r' && i + 1 < csv.length() && csv.charAt(i + 1) == '\n') i++;
                    row.add(cell.toString());
                    cell.setLength(0);
                    rows.add(row);
                    row = new ArrayList<>();
                } else cell.append(c);
            }
            if (cell.length() > 0 || !row.isEmpty()) {
                row.add(cell.toString());
                rows.add(row);
            }
            return rows;
        }
    }
}
