package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.PlayerValueRepository;
import io.butler.bet.domain.Player;
import io.butler.bet.domain.PlayerValue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class DynastyProcessValueImporter {
    public static final String SOURCE_1QB = "dynastyprocess-1qb";
    public static final String SOURCE_2QB = "dynastyprocess-2qb";
    public static final URI VALUES_URI = URI.create(
        "https://raw.githubusercontent.com/dynastyprocess/data/master/files/values-players.csv");
    public static final URI PLAYER_IDS_URI = URI.create(
        "https://raw.githubusercontent.com/dynastyprocess/data/master/files/db_playerids.csv");

    private final PlayerRepository players;
    private final PlayerValueRepository values;
    private final HttpClient http;

    public DynastyProcessValueImporter(Database database) {
        this(database, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build());
    }

    DynastyProcessValueImporter(Database database, HttpClient http) {
        Objects.requireNonNull(database, "database must not be null");
        this.players = new PlayerRepository(database);
        this.values = new PlayerValueRepository(database);
        this.http = Objects.requireNonNull(http, "http must not be null");
    }

    public ImportResult refresh() throws IOException, InterruptedException, SQLException {
        String valueCsv = download(VALUES_URI);
        String idCsv = download(PLAYER_IDS_URI);
        return importCsv(valueCsv, idCsv);
    }

    public ImportResult importCsv(String valueCsv, String idCsv) throws SQLException {
        List<Map<String, String>> valueRows = Csv.parse(requireText(valueCsv, "valueCsv"));
        List<Map<String, String>> idRows = Csv.parse(requireText(idCsv, "idCsv"));
        if (valueRows.isEmpty()) throw new IllegalArgumentException("DynastyProcess values file contains no data rows");
        if (idRows.isEmpty()) throw new IllegalArgumentException("DynastyProcess player-id file contains no data rows");

        Map<String, String> sleeperByFantasyPros = new LinkedHashMap<>();
        Map<String, String> sleeperByIdentity = new LinkedHashMap<>();
        for (Map<String, String> row : idRows) {
            String sleeperId = normalizeId(first(row, "sleeper_id"));
            if (sleeperId == null) continue;

            String fpId = normalizeId(first(row, "fantasypros_id", "fp_id"));
            if (fpId != null) {
                String existing = sleeperByFantasyPros.putIfAbsent(fpId, sleeperId);
                if (existing != null && !existing.equals(sleeperId)) {
                    throw new IllegalArgumentException("ambiguous DynastyProcess FantasyPros id mapping: " + fpId);
                }
            }

            String identity = identity(first(row, "name", "player"),
                first(row, "position", "pos"), first(row, "team", "team_abbr"));
            if (identity != null) {
                if (!sleeperByIdentity.containsKey(identity)) sleeperByIdentity.put(identity, sleeperId);
                else if (!Objects.equals(sleeperByIdentity.get(identity), sleeperId)) sleeperByIdentity.put(identity, null);
            }
        }

        int uniqueIdentityMappings = (int) sleeperByIdentity.values().stream().filter(Objects::nonNull).count();
        int ambiguousIdentityMappings = sleeperByIdentity.size() - uniqueIdentityMappings;
        Map<String, ProviderValue> providerBySleeper = new LinkedHashMap<>();
        LocalDate datasetDate = null;
        int providerRowsMappedByPrimaryId = 0;
        int providerRowsMappedByIdentity = 0;
        for (Map<String, String> row : valueRows) {
            String fpId = normalizeId(first(row, "fp_id", "fantasypros_id"));
            String sleeperId = fpId == null ? null : sleeperByFantasyPros.get(fpId);
            boolean identityFallback = false;
            if (sleeperId != null) {
                providerRowsMappedByPrimaryId++;
            } else {
                String identity = identity(first(row, "player", "name"),
                    first(row, "pos", "position"), first(row, "team", "team_abbr"));
                sleeperId = identity == null ? null : sleeperByIdentity.get(identity);
                identityFallback = sleeperId != null;
                if (identityFallback) providerRowsMappedByIdentity++;
            }
            if (sleeperId == null) continue;

            String providerKey = fpId == null ? sleeperId : fpId;
            LocalDate asOf = parseDate(required(row, "scrape_date"), providerKey);
            if (datasetDate == null) datasetDate = asOf;
            else if (!datasetDate.equals(asOf)) {
                throw new IllegalArgumentException("DynastyProcess values contain multiple scrape dates: "
                    + datasetDate + " and " + asOf);
            }

            double oneQb = parseValue(required(row, "value_1qb"), "value_1qb", providerKey);
            double twoQb = parseValue(required(row, "value_2qb"), "value_2qb", providerKey);
            ProviderValue provider = new ProviderValue(fpId, sleeperId, asOf, oneQb, twoQb, identityFallback);
            ProviderValue existing = providerBySleeper.putIfAbsent(sleeperId, provider);
            if (existing != null && !existing.equals(provider)) {
                throw new IllegalArgumentException("ambiguous DynastyProcess Sleeper id mapping: " + sleeperId);
            }
        }
        if (datasetDate == null) {
            throw new IllegalArgumentException("DynastyProcess values could not be mapped through the player-id database");
        }

        List<PlayerValue> resolved = new ArrayList<>();
        List<UnmatchedPlayer> unmatched = new ArrayList<>();
        int eligiblePlayers = 0;
        int matchedPlayers = 0;
        int identityFallbackMatches = 0;
        for (Player player : players.findAll()) {
            String sleeperId = normalizeId(player.getExternalId());
            if (sleeperId == null) continue;
            eligiblePlayers++;
            ProviderValue provider = providerBySleeper.get(sleeperId);
            if (provider == null) {
                unmatched.add(new UnmatchedPlayer(player.getId(), sleeperId, player.getDisplayName()));
                continue;
            }
            matchedPlayers++;
            if (provider.identityFallback()) identityFallbackMatches++;
            resolved.add(PlayerValue.create(player.getId(), provider.oneQbValue(), SOURCE_1QB, provider.asOfDate()));
            resolved.add(PlayerValue.create(player.getId(), provider.twoQbValue(), SOURCE_2QB, provider.asOfDate()));
        }

        values.saveAll(resolved);
        ProviderDiagnostics diagnostics = new ProviderDiagnostics(
            valueRows.size(), idRows.size(), sleeperByFantasyPros.size(), uniqueIdentityMappings,
            ambiguousIdentityMappings, providerRowsMappedByPrimaryId, providerRowsMappedByIdentity,
            valueRows.size() - providerRowsMappedByPrimaryId - providerRowsMappedByIdentity);
        return new ImportResult(datasetDate, eligiblePlayers, matchedPlayers, identityFallbackMatches,
            unmatched.size(), resolved.size(), diagnostics, List.copyOf(unmatched));
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

    private static String required(Map<String, String> row, String column) {
        String value = first(row, column);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("missing DynastyProcess column value: " + column);
        return value.trim();
    }

    private static String first(Map<String, String> row, String... columns) {
        for (String column : columns) {
            if (row.containsKey(column)) return row.get(column);
        }
        return null;
    }

    private static String identity(String name, String position, String team) {
        String normalizedName = normalizeIdentityPart(name);
        String normalizedPosition = normalizeIdentityPart(position);
        String normalizedTeam = normalizeIdentityPart(team);
        if (normalizedName == null || normalizedPosition == null || normalizedTeam == null) return null;
        return normalizedName + "|" + normalizedPosition + "|" + normalizedTeam;
    }

    private static String normalizeIdentityPart(String value) {
        if (value == null || value.isBlank() || value.equalsIgnoreCase("NA")) return null;
        return value.trim().replace('\u2019', '\'').replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static double parseValue(String text, String column, String fpId) {
        try {
            double value = Double.parseDouble(text);
            if (!Double.isFinite(value) || value < 0) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid DynastyProcess " + column + " for provider id " + fpId + ": " + text, e);
        }
    }

    private static LocalDate parseDate(String text, String fpId) {
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("invalid DynastyProcess scrape_date for provider id " + fpId + ": " + text, e);
        }
    }

    private static String normalizeId(String value) {
        if (value == null || value.isBlank() || value.equalsIgnoreCase("NA")) return null;
        String normalized = value.trim();
        if (normalized.matches("[0-9]+\\.0")) normalized = normalized.substring(0, normalized.length() - 2);
        return normalized;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }

    public record ImportResult(LocalDate asOfDate, int eligiblePlayers, int matchedPlayers,
                               int identityFallbackMatches, int unmatchedPlayers, int valuesImported,
                               ProviderDiagnostics diagnostics, List<UnmatchedPlayer> unmatched) {}

    public record ProviderDiagnostics(int valueRows, int playerIdRows, int primaryCrosswalkEntries,
                                      int uniqueIdentityMappings, int ambiguousIdentityMappings,
                                      int providerRowsMappedByPrimaryId, int providerRowsMappedByIdentity,
                                      int providerRowsUnmapped) {
        public int providerRowsMapped() {
            return providerRowsMappedByPrimaryId + providerRowsMappedByIdentity;
        }

        public double providerMappingPercent() {
            return valueRows == 0 ? 0.0 : (providerRowsMapped() * 100.0) / valueRows;
        }
    }

    public record UnmatchedPlayer(String playerId, String sleeperId, String playerName) {}

    private record ProviderValue(String fantasyProsId, String sleeperId, LocalDate asOfDate,
                                 double oneQbValue, double twoQbValue, boolean identityFallback) {}

    private static final class Csv {
        private Csv() {}

        private static List<Map<String, String>> parse(String csv) {
            List<List<String>> records = records(csv);
            if (records.isEmpty()) return List.of();
            List<String> header = records.get(0).stream().map(Csv::header).toList();
            List<Map<String, String>> rows = new ArrayList<>();
            for (int i = 1; i < records.size(); i++) {
                List<String> record = records.get(i);
                if (record.size() == 1 && record.get(0).isBlank()) continue;
                if (record.size() != header.size()) {
                    throw new IllegalArgumentException("invalid CSV row " + (i + 1) + ": expected "
                        + header.size() + " columns but found " + record.size());
                }
                Map<String, String> row = new LinkedHashMap<>();
                for (int c = 0; c < header.size(); c++) row.put(header.get(c), record.get(c));
                rows.add(row);
            }
            return List.copyOf(rows);
        }

        private static String header(String value) {
            String normalized = value.trim();
            if (!normalized.isEmpty() && normalized.charAt(0) == '\ufeff') normalized = normalized.substring(1);
            return normalized;
        }

        private static List<List<String>> records(String csv) {
            List<List<String>> rows = new ArrayList<>();
            List<String> row = new ArrayList<>();
            StringBuilder cell = new StringBuilder();
            boolean quoted = false;
            for (int i = 0; i < csv.length(); i++) {
                char ch = csv.charAt(i);
                if (quoted) {
                    if (ch == '"') {
                        if (i + 1 < csv.length() && csv.charAt(i + 1) == '"') {
                            cell.append('"');
                            i++;
                        } else quoted = false;
                    } else cell.append(ch);
                    continue;
                }
                if (ch == '"' && cell.length() == 0) {
                    quoted = true;
                } else if (ch == ',') {
                    row.add(cell.toString());
                    cell.setLength(0);
                } else if (ch == '\n') {
                    row.add(trimCr(cell.toString()));
                    cell.setLength(0);
                    rows.add(List.copyOf(row));
                    row.clear();
                } else {
                    cell.append(ch);
                }
            }
            if (quoted) throw new IllegalArgumentException("unterminated quoted CSV field");
            if (cell.length() > 0 || !row.isEmpty()) {
                row.add(trimCr(cell.toString()));
                rows.add(List.copyOf(row));
            }
            return rows;
        }

        private static String trimCr(String value) {
            return value.endsWith("\r") ? value.substring(0, value.length() - 1) : value;
        }
    }
}
