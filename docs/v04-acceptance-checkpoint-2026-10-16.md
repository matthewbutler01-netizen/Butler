# v0.4 acceptance checkpoint - October 16, 2026

App target: cbaf8687fca950593ddd2db1836fa60be121a587.
Packaging candidate exercised: 2416c9d401376cf238525f53d50e78d3d318a8fd.

Both app Java/full Windows and packaging Java/full Windows completed successfully.
Packaging Windows run: https://github.com/matthewbutler01-netizen/Butler/actions/runs/38987797264

Downloaded `butler-v04-beta-one-click` from that run. All three launcher files
matched source SHA256. Packaged browser checklist present. One-click preflight
passed against the existing clean, trusted development checkout with free 18080.
The updater fetched the expected source branch and verified the full app SHA.

Actual startup attempt: HELD after the old launcher's 120-second budget.
The app's Gradle installDist completed successfully, but no public web service
was ready in that budget. A direct repeat of installDist passed in 12 seconds.
Browser navigation reported connection refused. No browser rollover acceptance
or current-week recommendation is claimed. Owner acceptance remains BLOCKED.

This exposed a launcher observability problem: the startup-timeout path had no
saved status report. The follow-up adds a bounded five-minute startup budget,
progress notices, a prompt exit check and safe PENDING/HELD reports. Isolated
Windows tests cover delayed health, exited starter, bounded timeout and report
replacement without launching an app or calling a provider. This does not prove
that a longer wait repairs the underlying app startup.

No Sleeper transaction was requested or submitted. No v0.3 files were changed.
Do not merge/release based on CI or checkout identity alone.
