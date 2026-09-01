package io.butler.bet.sleeper;

import java.io.IOException;
import java.util.List;
import java.util.Map;

interface SleeperGateway {
    SleeperJsonParser.SleeperLeague fetchLeague(String leagueId) throws IOException, InterruptedException;
    List<SleeperJsonParser.SleeperUser> fetchUsers(String leagueId) throws IOException, InterruptedException;
    List<SleeperJsonParser.SleeperRoster> fetchRosters(String leagueId) throws IOException, InterruptedException;
    Map<String, SleeperJsonParser.SleeperPlayer> fetchPlayers() throws IOException, InterruptedException;
}
