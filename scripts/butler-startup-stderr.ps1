# Drain redirected child stderr independently of PowerShell runspaces. Keep only
# a bounded tail in memory; persist it locally only when startup actually fails.
if (-not ('ButlerStartupStderrTail' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.IO;
using System.Text;
using System.Threading.Tasks;
public sealed class ButlerStartupStderrTail {
    private readonly object gate = new object();
    private readonly StringBuilder tail = new StringBuilder();
    private readonly int limit;
    private readonly Task drain;
    public ButlerStartupStderrTail(StreamReader reader, int limit) {
        if (limit < 1) throw new ArgumentOutOfRangeException("limit");
        this.limit = limit;
        drain = Task.Factory.StartNew(() => {
            var buffer = new char[1024];
            try {
                int count;
                while ((count = reader.Read(buffer, 0, buffer.Length)) > 0) {
                    lock (gate) {
                        tail.Append(buffer, 0, count);
                        if (tail.Length > limit) tail.Remove(0, tail.Length - limit);
                    }
                }
            }
            catch (IOException) { }
            catch (ObjectDisposedException) { }
        }, TaskCreationOptions.LongRunning);
    }
    public string Snapshot(int waitMilliseconds) {
        drain.Wait(waitMilliseconds);
        lock (gate) { return tail.ToString(); }
    }
}
'@
}

function Save-ButlerStartupFailure {
    param([Parameter(Mandatory=$true)]$Process)
    try {
        $directory = Join-Path $env:LOCALAPPDATA 'Butler\diagnostics'
        [void][IO.Directory]::CreateDirectory($directory)
        $path = Join-Path $directory 'app-core-startup-latest.txt'
        $detail = $Process.ButlerStartupStderr.Snapshot(500)
        if ([string]::IsNullOrWhiteSpace($detail)) { $detail = 'No child stderr was captured.' }
        $exitCode = if ($Process.HasExited) { [string]$Process.ExitCode } else { 'unavailable (child still running)' }
        $header = 'PRIVATE LOCAL STARTUP DIAGNOSTIC - review before sharing.' + [Environment]::NewLine +
            'Failure captured UTC: ' + [DateTimeOffset]::UtcNow.ToString('o') + [Environment]::NewLine +
            'Child process: ' + $Process.Id + [Environment]::NewLine +
            'Core exit code: ' + $exitCode + [Environment]::NewLine +
            'Bounded stderr tail (up to 16384 characters):' + [Environment]::NewLine
        [IO.File]::WriteAllText($path, $header + $detail, [Text.UTF8Encoding]::new($false))
        return ' Local startup details saved to: ' + $path
    }
    catch {
        # A diagnostic storage failure must not replace the startup failure.
        return ' Local startup details could not be saved.'
    }
}
