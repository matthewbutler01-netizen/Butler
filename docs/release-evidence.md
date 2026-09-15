# Butler Verified Release Evidence

BF-787 adds a portable, code/runtime-only evidence archive after the normal Butler release gate has already passed BF-778 verification.

The authoritative release command remains:

```text
.\scripts\butler-release-acceptance.cmd
```

After BF-778 independently verifies the current BF-777 record, BF-787 packages exactly these four files:

```text
Butler-runtime-<shortsha>.zip
Butler-runtime-<shortsha>.zip.sha256
Butler-runtime-<shortsha>.manifest.txt
Butler-release-<shortsha>.verified.txt
```

The resulting portable evidence files are:

```text
Butler-release-evidence-<shortsha>.zip
Butler-release-evidence-<shortsha>.zip.sha256
```

The evidence archive is not a fresh-machine installer, runtime-data backup, or publication artifact. It contains no Butler database, credentials, provider payloads, logs, or user runtime data. It does not upload anything, create a Git tag, or create a GitHub Release.

BF-787 does not change the BF-777 verification-record schema. Historical V1 records remain independently verifiable with BF-778:

```text
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File ".\scripts\butler-release-verification-check.ps1" -RecordPath ".\release-output\Butler-release-<shortsha>.verified.txt"
```

A successful unified release run now ends with the existing BF-786/BF-776/BF-777/BF-780 markers and:

```text
BF-787 RELEASE EVIDENCE ARCHIVE: PASS
```

Boundary: `VERIFIED_RELEASE_EVIDENCE_ONLY_NO_RUNTIME_DATA_NO_PUBLICATION`.
