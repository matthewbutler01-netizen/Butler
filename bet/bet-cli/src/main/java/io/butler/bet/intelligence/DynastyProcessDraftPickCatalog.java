package io.butler.bet.intelligence;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads generic future-pick values from DynastyProcess without assigning speculative
 * early/mid/late or exact-slot values to league-owned picks.
 */
public final class DynastyProcessDraftPickCatalog {
    public static final URI VALUES_URI = URI.create(
        "https://raw.githubusercontent.com/dynastyprocess/data/master/files/values.csv");

    private static final Pattern GENERIC_PICK = Pattern.compile(
        "^(\\d{4})\\s+(1st|2nd|3rd|4th|5th|6th|7th)$", Pattern.CASE_INSENSITIVE);

    private final HttpClient http;

    public DynastyProcessDraftPickCatalog() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build());
    }

    DynastyProcessDraftPickCatalog(HttpClient http) {
        this.http = Objects.requireNonNull(http, "http must not be null");
    }

    public Catalog fetch() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(VALUES_URI)
            .timeout(Duration.ofSeconds(60))
            .header("User-Agent", "Butler-FF/0.1")
            .GET()
            .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("DynastyProcess draft-pick download failed with HTTP "
                + response.statusCode() + ": " + VALUES_URI);
        }
        return parseCsv(response.body());
    }

    Catalog parseCsv(String csv) {
        List<Map<String, String>> rows = Csv.parse(requireText(csv, "csv"));
        Map<PickKey, PickValue> byKey = new LinkedHashMap<>();
        LocalDate datasetDate = null;

        for (Map<String, String> row : rows) {
            if (!"PICK".equalsIgnoreCase(first(row, "pos", "position"))) continue;
            String label = first(row, "player", "name");
            if (label == null) continue;
            Matcher matcher = GENERIC_PICK.matcher(label.trim());
            if (!matcher.matches()) continue;

            int season = Integer.parseInt(matcher.group(1));
            int round = parseRound(matcher.group(2));
            LocalDate asOfDate = parseDate(required(row, "scrape_date"), label);
            if (datasetDate == null) datasetDate = asOfDate;
            else if (!datasetDate.equals(asOfDate)) {
                throw new IllegalArgumentException("DynastyProcess generic draft-pick values contain multiple scrape dates: "
                    + datasetDate + " and " + asOfDate);
            }

            PickValue value = new PickValue(
                season,
                round,
                label.trim(),
                parseValue(required(row, "value_1qb"), "value_1qb", label),
                parseValue(required(row, "value_2qb"), "value_2qb", label),
                asOfDate);
            PickKey key = new PickKey(season, round);
            PickValue existing = byKey.putIfAbsent(key, value);
            if (existing != null) {
                throw new IllegalArgumentException("duplicate DynastyProcess generic draft-pick value: " + label.trim());
            }
        }

        List<PickValue> values = new ArrayList<>(byKey.values());
        values.sort(Comparator.comparingInt(PickValue::season).thenComparingInt(PickValue::round));
        return new Catalog(datasetDate, List.copyOf(values));
    }

    private static int parseRound(String ordinal) {
        return switch (ordinal.toLowerCase()) {
            case "1st" -> 1;
            case "2nd" -> 2;
            case "3rd" -> 3;
            case "4th" -> 4;
            case "5th" -> 5;
            case "6th" -> 6;
            case "7th" -> 7;
            default -> throw new IllegalArgumentException("unsupported draft round: " + ordinal);
        };
    }

    private static double parseValue(String text, String column, String label) {
        try {
            double value = Double.parseDouble(text.trim());
            if (!Double.isFinite(value) || value < 0) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid DynastyProcess " + column + " for " + label + ": " + text, e);
        }
    }

    private static LocalDate parseDate(String text, String label) {
        try {
            return LocalDate.parse(text.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("invalid DynastyProcess scrape_date for " + label + ": " + text, e);
        }
    }

    private static String required(Map<String, String> row, String column) {
        String value = first(row, column);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing DynastyProcess column value: " + column);
        }
        return value.trim();
    }

    private static String first(Map<String, String> row, String... columns) {
        for (String column : columns) {
            if (row.containsKey(column)) return row.get(column);
        }
        return null;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }

    public record Catalog(LocalDate asOfDate, List<PickValue> values) {
        public Optional<PickValue> find(int season, int round) {
            return values.stream().filter(value -> value.season() == season && value.round() == round).findFirst();
        }
    }

    public record PickValue(int season, int round, String label,
                            double oneQbValue, double twoQbValue, LocalDate asOfDate) {}

    private record PickKey(int season, int round) {}

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
                for (int column = 0; column < header.size(); column++) {
                    row.put(header.get(column), record.get(column));
                }
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
