package io.butler.bet.data;

import io.butler.bet.domain.PlayerWeekProduction;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Persists raw week-level production snapshots without applying fantasy scoring. */
public final class PlayerWeekProductionRepository {
    private final Database database;
    public PlayerWeekProductionRepository(Database database) { this.database = Objects.requireNonNull(database, "database must not be null"); }

    public void save(PlayerWeekProduction p) throws SQLException {
        Objects.requireNonNull(p, "production must not be null");
        try (Connection c = database.openConnection()) {
            ensureTable(c);
            String sql = "INSERT INTO player_week_production(id,player_id,season,week,passing_yards,passing_touchdowns,interceptions,rushing_yards,rushing_touchdowns,receptions,receiving_yards,receiving_touchdowns,fumbles_lost,passing_two_point_conversions,rushing_attempts,rushing_two_point_conversions,receiving_two_point_conversions,fumble_recovery_touchdowns,special_teams_touchdowns,sacks_suffered,raw_scoring_schema_version,source,as_of_date) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(player_id,season,week,source,as_of_date) DO UPDATE SET passing_yards=excluded.passing_yards,passing_touchdowns=excluded.passing_touchdowns,interceptions=excluded.interceptions,rushing_yards=excluded.rushing_yards,rushing_touchdowns=excluded.rushing_touchdowns,receptions=excluded.receptions,receiving_yards=excluded.receiving_yards,receiving_touchdowns=excluded.receiving_touchdowns,fumbles_lost=excluded.fumbles_lost,passing_two_point_conversions=excluded.passing_two_point_conversions,rushing_attempts=excluded.rushing_attempts,rushing_two_point_conversions=excluded.rushing_two_point_conversions,receiving_two_point_conversions=excluded.receiving_two_point_conversions,fumble_recovery_touchdowns=excluded.fumble_recovery_touchdowns,special_teams_touchdowns=excluded.special_teams_touchdowns,sacks_suffered=excluded.sacks_suffered,raw_scoring_schema_version=excluded.raw_scoring_schema_version";
            try (PreparedStatement s = c.prepareStatement(sql)) {
                s.setString(1,p.id()); s.setString(2,p.playerId()); s.setInt(3,p.season()); s.setInt(4,p.week());
                s.setInt(5,p.passingYards()); s.setInt(6,p.passingTouchdowns()); s.setInt(7,p.interceptions());
                s.setInt(8,p.rushingYards()); s.setInt(9,p.rushingTouchdowns()); s.setInt(10,p.receptions());
                s.setInt(11,p.receivingYards()); s.setInt(12,p.receivingTouchdowns()); s.setInt(13,p.fumblesLost());
                s.setInt(14,p.passingTwoPointConversions()); s.setInt(15,p.rushingAttempts()); s.setInt(16,p.rushingTwoPointConversions());
                s.setInt(17,p.receivingTwoPointConversions()); s.setInt(18,p.fumbleRecoveryTouchdowns()); s.setInt(19,p.specialTeamsTouchdowns());
                s.setInt(20,p.sacksSuffered()); s.setInt(21,p.rawScoringSchemaVersion()); s.setString(22,p.source()); s.setString(23,p.asOfDate().toString()); s.executeUpdate();
            }
        }
    }

    public Optional<PlayerWeekProduction> findLatest(String playerId,int season,int week,String source) throws SQLException {
        require(playerId,"playerId"); require(source,"source"); if(season<=0||week<=0) throw new IllegalArgumentException("season and week must be positive");
        try(Connection c=database.openConnection()){ ensureTable(c); try(PreparedStatement s=c.prepareStatement("SELECT * FROM player_week_production WHERE player_id=? AND season=? AND week=? AND source=? ORDER BY as_of_date DESC,id DESC LIMIT 1")){s.setString(1,playerId.trim());s.setInt(2,season);s.setInt(3,week);s.setString(4,source.trim());try(ResultSet r=s.executeQuery()){return r.next()?Optional.of(map(r)):Optional.empty();}}}
    }

    public Optional<PlayerWeekProduction> findAtAsOf(String playerId,int season,int week,String source,LocalDate asOfDate) throws SQLException {
        require(playerId,"playerId"); require(source,"source"); Objects.requireNonNull(asOfDate,"asOfDate must not be null"); if(season<=0||week<=0) throw new IllegalArgumentException("season and week must be positive");
        try(Connection c=database.openConnection()){ensureTable(c);try(PreparedStatement s=c.prepareStatement("SELECT * FROM player_week_production WHERE player_id=? AND season=? AND week=? AND source=? AND as_of_date=? LIMIT 1")){s.setString(1,playerId.trim());s.setInt(2,season);s.setInt(3,week);s.setString(4,source.trim());s.setString(5,asOfDate.toString());try(ResultSet r=s.executeQuery()){return r.next()?Optional.of(map(r)):Optional.empty();}}}
    }

    public List<PlayerWeekProduction> findByPlayerSeason(String playerId,int season,String source) throws SQLException {
        require(playerId,"playerId");require(source,"source");if(season<=0)throw new IllegalArgumentException("season must be positive");List<PlayerWeekProduction> out=new ArrayList<>();
        try(Connection c=database.openConnection()){ensureTable(c);try(PreparedStatement s=c.prepareStatement("SELECT * FROM player_week_production WHERE player_id=? AND season=? AND source=? ORDER BY week,as_of_date DESC,id DESC")){s.setString(1,playerId.trim());s.setInt(2,season);s.setString(3,source.trim());try(ResultSet r=s.executeQuery()){while(r.next())out.add(map(r));}}}return List.copyOf(out);
    }

    private static void ensureTable(Connection c) throws SQLException {
        try(var s=c.createStatement()){
            s.executeUpdate("CREATE TABLE IF NOT EXISTS player_week_production (id TEXT PRIMARY KEY,player_id TEXT NOT NULL,season INTEGER NOT NULL,week INTEGER NOT NULL,passing_yards INTEGER NOT NULL,passing_touchdowns INTEGER NOT NULL,interceptions INTEGER NOT NULL,rushing_yards INTEGER NOT NULL,rushing_touchdowns INTEGER NOT NULL,receptions INTEGER NOT NULL,receiving_yards INTEGER NOT NULL,receiving_touchdowns INTEGER NOT NULL,fumbles_lost INTEGER NOT NULL,passing_two_point_conversions INTEGER NOT NULL DEFAULT 0,rushing_attempts INTEGER NOT NULL DEFAULT 0,rushing_two_point_conversions INTEGER NOT NULL DEFAULT 0,receiving_two_point_conversions INTEGER NOT NULL DEFAULT 0,fumble_recovery_touchdowns INTEGER NOT NULL DEFAULT 0,special_teams_touchdowns INTEGER NOT NULL DEFAULT 0,sacks_suffered INTEGER NOT NULL DEFAULT 0,raw_scoring_schema_version INTEGER NOT NULL DEFAULT 1,source TEXT NOT NULL,as_of_date TEXT NOT NULL,UNIQUE(player_id,season,week,source,as_of_date),FOREIGN KEY(player_id) REFERENCES players(id) ON DELETE CASCADE,CHECK(season>0),CHECK(week>0))");
            String[][] cols={{"passing_two_point_conversions","INTEGER NOT NULL DEFAULT 0"},{"rushing_attempts","INTEGER NOT NULL DEFAULT 0"},{"rushing_two_point_conversions","INTEGER NOT NULL DEFAULT 0"},{"receiving_two_point_conversions","INTEGER NOT NULL DEFAULT 0"},{"fumble_recovery_touchdowns","INTEGER NOT NULL DEFAULT 0"},{"special_teams_touchdowns","INTEGER NOT NULL DEFAULT 0"},{"sacks_suffered","INTEGER NOT NULL DEFAULT 0"},{"raw_scoring_schema_version","INTEGER NOT NULL DEFAULT 1"}};
            for(String[] col:cols) ensureColumn(c,col[0],col[1]);
            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_player_week_production_lookup ON player_week_production(player_id,season,week,source,as_of_date DESC)");
        }
    }
    private static void ensureColumn(Connection c,String column,String def)throws SQLException{boolean found=false;try(var s=c.createStatement();var r=s.executeQuery("PRAGMA table_info(player_week_production)")){while(r.next())if(column.equalsIgnoreCase(r.getString("name"))){found=true;break;}}if(!found)try(var s=c.createStatement()){s.executeUpdate("ALTER TABLE player_week_production ADD COLUMN "+column+" "+def);}}
    private static PlayerWeekProduction map(ResultSet r)throws SQLException{return new PlayerWeekProduction(r.getString("id"),r.getString("player_id"),r.getInt("season"),r.getInt("week"),r.getInt("passing_yards"),r.getInt("passing_touchdowns"),r.getInt("interceptions"),r.getInt("rushing_yards"),r.getInt("rushing_touchdowns"),r.getInt("receptions"),r.getInt("receiving_yards"),r.getInt("receiving_touchdowns"),r.getInt("fumbles_lost"),r.getInt("passing_two_point_conversions"),r.getInt("rushing_attempts"),r.getInt("rushing_two_point_conversions"),r.getInt("receiving_two_point_conversions"),r.getInt("fumble_recovery_touchdowns"),r.getInt("special_teams_touchdowns"),r.getInt("sacks_suffered"),r.getInt("raw_scoring_schema_version"),r.getString("source"),LocalDate.parse(r.getString("as_of_date")));}
    private static void require(String v,String f){if(v==null||v.isBlank())throw new IllegalArgumentException(f+" must not be blank");}
}
