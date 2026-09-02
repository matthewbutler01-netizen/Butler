# Butler

Bootstrap project for Butler Forge.

## Next Step

Generate the Gradle Wrapper:

Windows:
```
gradle wrapper
```

Then build:
```
.\gradlew.bat build
```

## External fantasy-football data

Butler can import dynasty player values from the open-data repository maintained by DynastyProcess:

- Project: https://github.com/dynastyprocess/data
- Player values: `files/values-players.csv`
- Cross-platform player IDs: `files/db_playerids.csv`
- Upstream license: GNU General Public License v3.0 (GPL-3.0)

Butler fetches the upstream CSV files at runtime rather than vendoring a copy of the dataset. Imported 1QB and 2QB values are persisted separately as `dynastyprocess-1qb` and `dynastyprocess-2qb`, with the upstream `scrape_date` retained as the value snapshot date. Players that cannot be mapped from FantasyPros ID to Sleeper ID are reported as unmatched rather than guessed.
