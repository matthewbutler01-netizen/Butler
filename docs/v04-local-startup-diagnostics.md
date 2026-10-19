# Local startup diagnostics

If the inner app core exits before readiness, or the startup wait expires, the
outer app shell saves `Butler\diagnostics\app-core-startup-latest.txt` under the
current Windows LocalAppData directory. Its existing BF-670 error names the file.
No manual PowerShell command is required to create it.

The file records capture time in UTC, child process identity, exit code when
available, and the final 16,384 characters of stderr. A still-running child is
explicitly labeled; observing a timeout does not terminate it. An empty error
stream is recorded as empty rather than treated as a successful launch.

The stream is drained continuously to avoid a full stderr pipe blocking startup.
Only a bounded tail remains in memory. A healthy launch creates no diagnostic
file and does not refresh an older one; check the captured time before using an
existing report. A storage error cannot replace the original startup failure.

Raw child errors stay in the local file, not HTTP pages or the outer exception.
They may contain private paths or provider context; review before sharing.
This does not add provider requests, alter recommendations or submit transactions.
