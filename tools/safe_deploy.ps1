param(
    [string]$GameDir='F:\避役这一块\避役科工作端\.minecraft\versions\1.20.1-Forge_47.4.8'
)
$ErrorActionPreference='Stop'
$atlasRoot=Split-Path -Parent $PSScriptRoot
$atlasSource=(Resolve-Path -LiteralPath (Join-Path $atlasRoot 'build\libs\biyi_atlas-0.1.0.jar')).Path
$atlasGame=(Resolve-Path -LiteralPath $GameDir).Path
$atlasMods=(Resolve-Path -LiteralPath (Join-Path $atlasGame 'mods')).Path
$atlasTarget=Join-Path $atlasMods 'biyi_atlas-0.1.0.jar'
function Assert-AtlasStopped {
    # 必须能检查真实进程；权限不足时直接失败，不能把查询失败当成游戏已退出。
    $atlasProcesses=Get-CimInstance Win32_Process -Filter "Name='javaw.exe' OR Name='java.exe'" -ErrorAction Stop
    foreach($atlasProcess in $atlasProcesses){
        if([string]::IsNullOrWhiteSpace($atlasProcess.CommandLine)){throw '无法核对Java进程命令行，拒绝部署。请检查权限。'}
        $atlasCommand=$atlasProcess.CommandLine.Replace('/','\')
        if($atlasCommand.IndexOf($atlasGame,[StringComparison]::OrdinalIgnoreCase)-ge 0){
            throw "检测到测试游戏仍在运行（PID $($atlasProcess.ProcessId)），拒绝替换模组。请完全退出游戏。"
        }
    }
}
Assert-AtlasStopped
& python -c 'import sys,zipfile; z=zipfile.ZipFile(sys.argv[1]); assert z.testzip() is None; assert "com/biyi/atlas/server/AtlasRegionRegistry.class" in z.namelist(); print("JAR integrity verified")' $atlasSource
if($LASTEXITCODE-ne 0){throw 'JAR完整性检查失败'}
$atlasStaged=Join-Path $atlasMods 'biyi_atlas-0.1.0.staged'
Copy-Item -LiteralPath $atlasSource -Destination $atlasStaged -Force
$atlasHash=(Get-FileHash -LiteralPath $atlasSource -Algorithm SHA256).Hash
if($atlasHash-ne(Get-FileHash -LiteralPath $atlasStaged -Algorithm SHA256).Hash){throw '暂存包哈希不一致'}
Assert-AtlasStopped
if(Test-Path -LiteralPath $atlasTarget){
    $atlasBackup=$atlasTarget+'.backup-'+(Get-Date -Format 'yyyyMMdd-HHmmss')
    [IO.File]::Replace($atlasStaged,$atlasTarget,$atlasBackup)
}else{[IO.File]::Move($atlasStaged,$atlasTarget)}
if($atlasHash-ne(Get-FileHash -LiteralPath $atlasTarget -Algorithm SHA256).Hash){throw '部署后哈希不一致'}
[pscustomobject]@{SHA256=$atlasHash;Target=$atlasTarget;Backup=$atlasBackup}|ConvertTo-Json -Compress
