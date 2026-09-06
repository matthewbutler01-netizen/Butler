package io.butler.bet.sleeper;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class SleeperApiGateway implements SleeperGateway {
    private final SleeperClient client;
    private final SleeperJsonParser parser;

    SleeperApiGateway() {
        this(new SleeperClient(), new SleeperJsonParser());
    }

    SleeperApiGateway(SleeperClient client, SleeperJsonParser parser) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.parser = Objects.requireNonNull(parser, "parser must not be null");
    }

    @Override
    public SleeperJsonParser.SleeperLeague fetchLeague(String leagueId) throws IOException, InterruptedException {
        return parser.parseLeague(client.getLeague(leagueId));
    }

    @Override
    public List<SleeperJsonParser.SleeperUser> fetchUsers(String leagueId) throws IOException, InterruptedException {
        return parser.parseUsers(client.getLeagueUsers(leagueId));
    }

    @Override
    public List<SleeperJsonParser.SleeperRoster> fetchRosters(String leagueId) throws IOException, InterruptedException {
        return parser.parseRosters(client.getLeagueRosters(leagueId));
    }

    @Override
    public List<SleeperJsonParser.SleeperLeague> fetchUserLeagues(String userId, int season)
        throws IOException, InterruptedException {
        return parser.parseLeagues(client.getUserLeagues(userId, Integer.toString(season)));
    }

    @Override
    public List<SleeperMatchupParser.SleeperMatchup> fetchMatchups(String leagueId, int week)
        throws IOException, InterruptedException {
        return new SleeperMatchupParser().parse(client.getLeagueMatchups(leagueId, week));
    }

    @Override
    public List<SleeperJsonParser.SleeperTradedPick> fetchTradedPicks(String leagueId) throws IOException, InterruptedException {
        return parser.parseTradedPicks(client.getLeagueTradedPicks(leagueId));
    }

    @Override
    public Map<String, SleeperJsonParser.SleeperPlayer> fetchPlayers() throws IOException, InterruptedException {
        return parser.parsePlayers(client.getNflPlayers());
    }
}
