# Recovery and verification on Windows

Run verification from your source checkout; Fast Lane builds and runs in its
registered clean checkout at `C:\ButlerDev\fastlane`, outside OneDrive.

```powershell
.\scripts\butler-fastlane-verify.cmd <branch> -JavaHome "C:\path\to\jdk-25"
```

`-JavaHome` is optional when JAVA_HOME or PATH already resolves a suitable JDK.
The selected JDK must include Java 25 or newer and javac.exe. Selection affects
only the command process and its children; it does not install Java or change
Windows settings. An invalid explicit selection fails rather than silently
choosing another installation.

For confirmed roster drift, explicitly request recovery:

```powershell
.\scripts\butler-fastlane-verify.cmd <branch> -RecoverRoster -JavaHome "C:\path\to\jdk-25"
```

Fast Lane fetches the branch, checks worktree registration and cleanliness,
checks out its exact SHA, and builds before invoking the existing governed
recovery script from that checkout. The recovery script only refreshes evidence
when its exact BF-610 drift check permits it. Recovery and its BF-698 acceptance
must succeed before the BF-885/BF-912 journey runs. Default verification never
requests evidence recovery. Neither mode submits a Sleeper transaction.

A fresh checkout no longer needs pre-existing runtime jars: preflight prepares
and validates them before any recovery command runs.

The optional Gradle HTML problems report is disabled for these commands; console
errors and build failures remain enabled. Failed preflight output is saved in
`%TEMP%\Butler\diagnostics\preflight-<unique-id>.log`, with the path printed in
the terminal. This retains the first failure as well as a failed retry. Logs are
local and are not uploaded automatically.

If Git reports `FETCH_HEAD: Permission denied`, run the same command in your
normal Windows session with access to the repository. The scripts do not alter
ACLs, Git trust settings, or authentication. A verified recovery followed by an
acceptance failure is still a failure; it is not sufficient for a Fast Lane PASS.

BF-890's HTTP recovery-page diagnostic changes are separate from this launcher
and build-preflight change.
