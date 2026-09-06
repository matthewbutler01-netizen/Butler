package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.PlayerSeasonProductionRepository;
import io.butler.bet.domain.Player;
import io.butler.bet.domain.PlayerSeasonProduction;
import io.butler.bet.domain.RawScoringProduction;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Imports raw regular-season production from nflverse using exact GSIS-to-Sleeper identity mapping. */
public final class NflversePlayerSeasonProductionImporter {
    public static final String SOURCE = "nflverse";
    public static final URI PLAYER_IDS_URI = URI.create("https://raw.githubusercontent.com/dynastyprocess/data/master/files/db_playerids.csv");
    private static final Set<String> EXTENDED_COLUMNS = Set.of(
        "passing_2pt_conversions", "carries", "rushing_2pt_conversions",
        "receiving_2pt_conversions", "fumble_recovery_tds", "special_teams_tds");

    private final PlayerRepository players;
    private final PlayerSeasonProductionRepository production;
    private final HttpClient http;

    public NflversePlayerSeasonProductionImporter(Database database) {
        this(database, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build());
    }
    NflversePlayerSeasonProductionImporter(Database database, HttpClient http) {
        Objects.requireNonNull(database, "database must not be null");
        this.players=new PlayerRepository(database); this.production=new PlayerSeasonProductionRepository(database); this.http=Objects.requireNonNull(http,"http must not be null");
    }
    public ImportResult refresh(int season)throws IOException,InterruptedException,SQLException{return fetchAndProcess(season,true);}
    public ImportResult preview(int season)throws IOException,InterruptedException,SQLException{return fetchAndProcess(season,false);}
    private ImportResult fetchAndProcess(int season,boolean persist)throws IOException,InterruptedException,SQLException{
        requireSeason(season); return processCsv(season,download(statsUri(season),"nflverse player stats for "+season),download(PLAYER_IDS_URI,"fantasy player id crosswalk"),LocalDate.now(),persist);
    }
    public ImportResult importCsv(int season,String statsCsv,String idsCsv,LocalDate asOfDate)throws SQLException{return processCsv(season,statsCsv,idsCsv,asOfDate,true);}
    public ImportResult previewCsv(int season,String statsCsv,String idsCsv,LocalDate asOfDate)throws SQLException{return processCsv(season,statsCsv,idsCsv,asOfDate,false);}

    private ImportResult processCsv(int season,String statsCsv,String idsCsv,LocalDate asOfDate,boolean persist)throws SQLException{
        requireSeason(season);Objects.requireNonNull(asOfDate,"asOfDate must not be null");
        List<Map<String,String>> statsRows=Csv.parse(requireText(statsCsv,"statsCsv")); List<Map<String,String>> idRows=Csv.parse(requireText(idsCsv,"idsCsv"));
        if(statsRows.isEmpty())throw new IllegalArgumentException("nflverse stats contain no data rows"); if(idRows.isEmpty())throw new IllegalArgumentException("player-id crosswalk contains no data rows");
        int rawSchema=detectRawScoringSchema(statsRows.getFirst());
        Map<String,String>sleeperByGsis=buildCrosswalk(idRows);Map<String,ProviderProduction>bySleeper=new LinkedHashMap<>();int providerRowsForSeason=0,providerRowsMapped=0;
        for(Map<String,String>row:statsRows){int rowSeason=parseNonNegativeInt(required(row,"season"),"season","provider row");if(rowSeason!=season)continue;providerRowsForSeason++;String gsisId=normalizeId(required(row,"player_id"));String sleeperId=sleeperByGsis.get(gsisId);if(sleeperId==null)continue;providerRowsMapped++;
            ProviderProduction p=new ProviderProduction(gsisId,sleeperId,
                parseNonNegativeInt(value(row,"games"),"games",gsisId),parseSignedInt(value(row,"passing_yards"),"passing_yards",gsisId),parseNonNegativeInt(value(row,"passing_tds"),"passing_tds",gsisId),parseNonNegativeInt(value(row,"passing_interceptions"),"passing_interceptions",gsisId),parseSignedInt(value(row,"rushing_yards"),"rushing_yards",gsisId),parseNonNegativeInt(value(row,"rushing_tds"),"rushing_tds",gsisId),parseNonNegativeInt(value(row,"receptions"),"receptions",gsisId),parseSignedInt(value(row,"receiving_yards"),"receiving_yards",gsisId),parseNonNegativeInt(value(row,"receiving_tds"),"receiving_tds",gsisId),parseNonNegativeInt(value(row,"sack_fumbles_lost"),"sack_fumbles_lost",gsisId)+parseNonNegativeInt(value(row,"rushing_fumbles_lost"),"rushing_fumbles_lost",gsisId)+parseNonNegativeInt(value(row,"receiving_fumbles_lost"),"receiving_fumbles_lost",gsisId),
                rawSchema==2?parseNonNegativeInt(value(row,"passing_2pt_conversions"),"passing_2pt_conversions",gsisId):0,
                rawSchema==2?parseNonNegativeInt(value(row,"carries"),"carries",gsisId):0,
                rawSchema==2?parseNonNegativeInt(value(row,"rushing_2pt_conversions"),"rushing_2pt_conversions",gsisId):0,
                rawSchema==2?parseNonNegativeInt(value(row,"receiving_2pt_conversions"),"receiving_2pt_conversions",gsisId):0,
                rawSchema==2?parseNonNegativeInt(value(row,"fumble_recovery_tds"),"fumble_recovery_tds",gsisId):0,
                rawSchema==2?parseNonNegativeInt(value(row,"special_teams_tds"),"special_teams_tds",gsisId):0,rawSchema);
            ProviderProduction old=bySleeper.putIfAbsent(sleeperId,p);if(old!=null&&!old.equals(p))throw new IllegalArgumentException("ambiguous nflverse production mapping for Sleeper id: "+sleeperId);
        }
        if(providerRowsForSeason==0)throw new IllegalArgumentException("nflverse stats contain no rows for season: "+season);
        List<UnmatchedPlayer>unmatched=new ArrayList<>();int eligiblePlayers=0,matchedPlayers=0,snapshotsWritten=0;
        for(Player player:players.findAll()){String sleeperId=normalizeId(player.getExternalId());if(sleeperId==null)continue;eligiblePlayers++;ProviderProduction p=bySleeper.get(sleeperId);if(p==null){unmatched.add(new UnmatchedPlayer(player.getId(),sleeperId,player.getDisplayName()));continue;}matchedPlayers++;
            if(persist){PlayerSeasonProduction snapshot=p.rawSchema()==RawScoringProduction.EXTENDED_SCHEMA_VERSION
                ?PlayerSeasonProduction.createExactScoringV2(player.getId(),season,p.gamesPlayed(),p.passingYards(),p.passingTouchdowns(),p.interceptions(),p.rushingYards(),p.rushingTouchdowns(),p.receptions(),p.receivingYards(),p.receivingTouchdowns(),p.fumblesLost(),p.passingTwoPointConversions(),p.rushingAttempts(),p.rushingTwoPointConversions(),p.receivingTwoPointConversions(),p.fumbleRecoveryTouchdowns(),p.specialTeamsTouchdowns(),SOURCE,asOfDate)
                :PlayerSeasonProduction.create(player.getId(),season,p.gamesPlayed(),p.passingYards(),p.passingTouchdowns(),p.interceptions(),p.rushingYards(),p.rushingTouchdowns(),p.receptions(),p.receivingYards(),p.receivingTouchdowns(),p.fumblesLost(),SOURCE,asOfDate);production.save(snapshot);snapshotsWritten++;}}
        return new ImportResult(season,asOfDate,persist,statsRows.size(),providerRowsForSeason,sleeperByGsis.size(),providerRowsMapped,eligiblePlayers,matchedPlayers,unmatched.size(),snapshotsWritten,List.copyOf(unmatched));
    }

    private static int detectRawScoringSchema(Map<String,String>row){long present=EXTENDED_COLUMNS.stream().filter(row::containsKey).count();if(present==0)return RawScoringProduction.LEGACY_SCHEMA_VERSION;if(present==EXTENDED_COLUMNS.size())return RawScoringProduction.EXTENDED_SCHEMA_VERSION;throw new IllegalArgumentException("partial nflverse extended scoring schema; expected all columns "+EXTENDED_COLUMNS);}
    public static URI statsUri(int season){requireSeason(season);return URI.create("https://github.com/nflverse/nflverse-data/releases/download/stats_player/stats_player_reg_"+season+".csv");}
    private Map<String,String>buildCrosswalk(List<Map<String,String>>rows){Map<String,String>r=new LinkedHashMap<>();for(Map<String,String>row:rows){String g=normalizeId(row.get("gsis_id")),s=normalizeId(row.get("sleeper_id"));if(g==null||s==null)continue;String old=r.putIfAbsent(g,s);if(old!=null&&!old.equals(s))throw new IllegalArgumentException("ambiguous GSIS-to-Sleeper mapping for GSIS id: "+g);}if(r.isEmpty())throw new IllegalArgumentException("player-id crosswalk contains no GSIS-to-Sleeper mappings");return r;}
    private String download(URI uri,String description)throws IOException,InterruptedException{HttpResponse<String>r=http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60)).header("User-Agent","Butler-FF/0.1").GET().build(),HttpResponse.BodyHandlers.ofString());if(r.statusCode()!=200)throw new IOException(description+" unavailable (HTTP "+r.statusCode()+"): "+uri);return r.body();}
    private static String required(Map<String,String>row,String column){if(!row.containsKey(column))throw new IllegalArgumentException("missing nflverse column: "+column);return row.get(column);}private static String value(Map<String,String>row,String column){if(!row.containsKey(column))throw new IllegalArgumentException("missing nflverse column: "+column);String v=row.get(column);return v==null||v.isBlank()||v.equalsIgnoreCase("NA")?"0":v;}
    private static int parseNonNegativeInt(String t,String f,String id){int p=parseIntegralInt(t,f,id);if(p<0)throw new IllegalArgumentException("invalid nflverse "+f+" for "+id+": "+t);return p;}private static int parseSignedInt(String t,String f,String id){return parseIntegralInt(t,f,id);}private static int parseIntegralInt(String t,String f,String id){try{double v=Double.parseDouble(t.trim());if(!Double.isFinite(v)||v!=Math.rint(v)||v<Integer.MIN_VALUE||v>Integer.MAX_VALUE)throw new NumberFormatException();return(int)v;}catch(RuntimeException e){throw new IllegalArgumentException("invalid nflverse "+f+" for "+id+": "+t,e);}}
    private static String normalizeId(String v){if(v==null||v.isBlank()||v.equalsIgnoreCase("NA"))return null;String n=v.trim();if(n.matches("[0-9]+\\.0"))n=n.substring(0,n.length()-2);return n;}private static void requireSeason(int s){if(s<1999||s>2100)throw new IllegalArgumentException("season must be between 1999 and 2100");}private static String requireText(String v,String f){if(v==null||v.isBlank())throw new IllegalArgumentException(f+" must not be blank");return v;}
    public record ImportResult(int season,LocalDate asOfDate,boolean persisted,int providerRows,int providerRowsForSeason,int crosswalkEntries,int providerRowsMapped,int eligiblePlayers,int matchedPlayers,int unmatchedPlayers,int snapshotsWritten,List<UnmatchedPlayer>unmatched){}public record UnmatchedPlayer(String playerId,String sleeperId,String playerName){}
    private record ProviderProduction(String gsisId,String sleeperId,int gamesPlayed,int passingYards,int passingTouchdowns,int interceptions,int rushingYards,int rushingTouchdowns,int receptions,int receivingYards,int receivingTouchdowns,int fumblesLost,int passingTwoPointConversions,int rushingAttempts,int rushingTwoPointConversions,int receivingTwoPointConversions,int fumbleRecoveryTouchdowns,int specialTeamsTouchdowns,int rawSchema){}
    private static final class Csv{private Csv(){}static List<Map<String,String>>parse(String csv){List<List<String>>rows=rows(csv);if(rows.isEmpty())return List.of();List<String>h=rows.get(0);List<Map<String,String>>out=new ArrayList<>();for(int i=1;i<rows.size();i++){List<String>v=rows.get(i);if(v.size()==1&&v.get(0).isBlank())continue;Map<String,String>r=new LinkedHashMap<>();for(int j=0;j<h.size();j++)r.put(h.get(j).trim(),j<v.size()?v.get(j):"");out.add(r);}return out;}private static List<List<String>>rows(String csv){List<List<String>>out=new ArrayList<>();List<String>row=new ArrayList<>();StringBuilder cell=new StringBuilder();boolean quoted=false;for(int i=0;i<csv.length();i++){char c=csv.charAt(i);if(c=='"'){if(quoted&&i+1<csv.length()&&csv.charAt(i+1)=='"'){cell.append('"');i++;}else quoted=!quoted;}else if(c==','&&!quoted){row.add(cell.toString());cell.setLength(0);}else if((c=='\n'||c=='\r')&&!quoted){if(c=='\r'&&i+1<csv.length()&&csv.charAt(i+1)=='\n')i++;row.add(cell.toString());cell.setLength(0);out.add(row);row=new ArrayList<>();}else cell.append(c);}if(cell.length()>0||!row.isEmpty()){row.add(cell.toString());out.add(row);}return out;}}
}
