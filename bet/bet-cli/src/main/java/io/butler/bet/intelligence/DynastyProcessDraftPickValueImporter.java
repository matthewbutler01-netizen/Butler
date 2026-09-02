package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.DraftPickRepository;
import io.butler.bet.data.DraftPickValueRepository;
import io.butler.bet.domain.DraftPick;
import io.butler.bet.domain.DraftPickValue;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class DynastyProcessDraftPickValueImporter {
    private final DraftPickRepository picks;
    private final DraftPickValueRepository values;
    private final DynastyProcessDraftPickCatalog catalog;

    public DynastyProcessDraftPickValueImporter(Database database) {
        this(database, new DynastyProcessDraftPickCatalog());
    }

    DynastyProcessDraftPickValueImporter(Database database, DynastyProcessDraftPickCatalog catalog) {
        Objects.requireNonNull(database, "database must not be null");
        this.picks = new DraftPickRepository(database);
        this.values = new DraftPickValueRepository(database);
        this.catalog = Objects.requireNonNull(catalog, "catalog must not be null");
    }

    public ImportResult refresh(String leagueId) throws IOException, InterruptedException, SQLException {
        return importCatalog(leagueId, catalog.fetch());
    }

    ImportResult importCatalog(String leagueId, DynastyProcessDraftPickCatalog.Catalog providerCatalog) throws SQLException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        Objects.requireNonNull(providerCatalog, "providerCatalog must not be null");

        List<DraftPick> leaguePicks = picks.findByLeagueId(normalizedLeagueId);
        List<DraftPickValue> resolved = new ArrayList<>();
        List<MissingPick> missing = new ArrayList<>();

        for (DraftPick pick : leaguePicks) {
            var provider = providerCatalog.find(pick.getSeason(), pick.getRound()).orElse(null);
            if (provider == null) {
                missing.add(new MissingPick(
                    pick.getId(), pick.getSeason(), pick.getRound(), pick.getOriginalTeamId(), pick.getOwnerTeamId()));
                continue;
            }
            resolved.add(DraftPickValue.create(
                pick.getId(), provider.oneQbValue(), DynastyProcessValueImporter.SOURCE_1QB, provider.asOfDate()));
            resolved.add(DraftPickValue.create(
                pick.getId(), provider.twoQbValue(), DynastyProcessValueImporter.SOURCE_2QB, provider.asOfDate()));
        }

        values.saveAll(resolved);
        return new ImportResult(
            normalizedLeagueId,
            providerCatalog.asOfDate(),
            leaguePicks.size(),
            leaguePicks.size() - missing.size(),
            missing.size(),
            resolved.size(),
            List.copyOf(missing));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    public record ImportResult(String leagueId, java.time.LocalDate asOfDate,
                               int draftPicks, int matchedPicks, int missingPicks,
                               int valuesImported, List<MissingPick> missing) {
        public double coveragePercent() {
            return draftPicks == 0 ? 0.0 : matchedPicks * 100.0 / draftPicks;
        }
    }

    public record MissingPick(String draftPickId, int season, int round,
                              String originalTeamId, String ownerTeamId) {}
}
