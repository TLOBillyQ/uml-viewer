# 后台启动器：manifest 只含数据，原始参数始终按 argv 传递。
$ErrorActionPreference = 'Stop'
$manifest = $args[0]
try {
    $launch = Get-Content -LiteralPath $manifest -Raw | ConvertFrom-Json
    Remove-Item -LiteralPath $manifest
    $log = [IO.File]::Open($launch.log, [IO.FileMode]::Append, [IO.FileAccess]::Write, [IO.FileShare]::ReadWrite)
    $writer = [IO.StreamWriter]::new($log)
    $writer.WriteLine("----- $([DateTime]::Now.ToString('yyyy-MM-dd HH:mm:ss')) starting uml-viewer")
    $writer.Flush()
    $info = [Diagnostics.ProcessStartInfo]::new($launch.executable)
    $info.UseShellExecute = $false
    $info.WorkingDirectory = $launch.cwd
    $info.RedirectStandardInput = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    foreach ($arg in $launch.argv) { $info.ArgumentList.Add([string]$arg) }
    $process = [Diagnostics.Process]::Start($info)
    $process.StandardInput.Close()
    # 两个流并行排空，写入同一日志时由同步 writer 串行化。
    Add-Type -TypeDefinition @'
using System.IO;
using System.Threading.Tasks;
public static class UmlLog {
    public static async Task Pump(StreamReader reader, TextWriter writer) {
        string line;
        while ((line = await reader.ReadLineAsync()) != null) {
            writer.WriteLine(line);
            writer.Flush();
        }
    }
}
'@
    $sync = [IO.TextWriter]::Synchronized($writer)
    $stdout = [UmlLog]::Pump($process.StandardOutput, $sync)
    $stderr = [UmlLog]::Pump($process.StandardError, $sync)
    $process.WaitForExit()
    [Threading.Tasks.Task]::WaitAll(@($stdout, $stderr))
    $writer.Dispose()
    exit $process.ExitCode
} catch {
    [Console]::Error.WriteLine("uml launcher: $_")
    exit 1
}
