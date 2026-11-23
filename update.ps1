param([switch]$Launch,[string]$Proxy=$env:AFTERSALES_UPDATE_PROXY)
$ErrorActionPreference='Stop'
$root=$PSScriptRoot
$repo='an_602/After-sales-Assistant'
$api="https://gitee.com/api/v5/repos/$repo"
$branch='master'
$requiredApp=@('lib/gson-2.13.2.jar','report-desk/report-desk-desktop.jar','report-desk/browser.cjs',
    'report-desk/browser-settings.json','report-desk/remark-aliases.json','report-desk/driver/node.exe',
    'report-desk/driver/node_modules/playwright-core/package.json','runtime/bin/java.exe',
    'runtime/bin/javaw.exe','runtime/lib/modules')
$entryPoints=@('启动商品售后查询台.cmd','更新工具.cmd','update.ps1')
$managed=$requiredApp+$entryPoints+@('report-desk/daily.cmd','report-desk/使用说明.md',
    'report-desk/启用每日刷新.ps1','report-desk/验证商品归属.ps1')

function Test-ManagedPath([string]$path) {
    return $path -cin $managed -or $path -cmatch '^(runtime|report-desk/driver)/[^/]+'
}

function Get-LocalPath([string]$relative) {
    if($relative -match '[\x00-\x1f\\:<>\"|?*]|(^|/)[. ]+(/|$)|[. ](/|$)|^/|//|/$' -or
        $relative -match '(^|/)(CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³])(\.|/|$)'){
        throw "文件路径无效：$relative"
    }
    $path=$root
    foreach($segment in $relative.Split('/')){
        $path=Join-Path $path $segment
        $item=Get-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue
        if($item -and ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)){
            throw "安装目录包含符号链接或联接：$path"
        }
    }
    return $path
}

function Invoke-UpdateRequest([string]$url,[int]$timeoutSec=10) {
    $address=if($mirror){$mirror+$url}else{$url}
    $request=@{Uri=$address;UseBasicParsing=$true;TimeoutSec=$timeoutSec;MaximumRedirection=0;
        ErrorAction='Stop';UserAgent='After-sales-Assistant-Updater';Headers=@{Accept='application/json'}}
    if($Proxy){$request.Proxy=$Proxy}
    $ProgressPreference='SilentlyContinue'
    return (Invoke-WebRequest @request).Content | ConvertFrom-Json
}

function Get-RepositoryFiles {
    $head=Invoke-UpdateRequest "$api/branches/$branch"
    $commit=$head.commit.sha
    if($commit -cnotmatch '^[a-f0-9]{40}$'){
        throw 'Gitee 返回的提交标识无效。'
    }
    $tree=Invoke-UpdateRequest "$api/git/trees/$($commit)?recursive=1"
    if($tree.sha -cne $commit -or $tree.truncated -ne $false){throw '远端文件清单不完整。'}

    $paths=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    $files=@(foreach($entry in $tree.tree){
        $path=[string]$entry.path
        if($entry.type -eq 'tree' -or !(Test-ManagedPath $path)){continue}
        if($entry.type -ne 'blob' -or $entry.mode -notin @('100644','100755') -or
            $entry.sha -cnotmatch '^[a-f0-9]{40}$' -or $null -eq $entry.size -or $entry.size -lt 0 -or !$paths.Add($path)){
            throw "远端文件记录无效：$path"
        }
        [pscustomobject]@{path=$path;sha=$entry.sha;size=[long]$entry.size}
    })
    foreach($path in $requiredApp+$entryPoints){
        if(!$paths.Contains($path)){throw "远端分支缺少 $path。请确认完整可运行版本已提交到 $branch。"}
    }
    return @{Commit=$commit;Files=$files}
}

function Save-RepositoryBlob($entry,[string]$destination) {
    $blob=Invoke-UpdateRequest "$api/git/blobs/$($entry.sha)" 600
    if($blob.encoding -ne 'base64' -or $null -eq $blob.content){
        throw "Gitee 返回的文件内容无效：$($entry.path)"
    }
    [IO.File]::WriteAllBytes($destination,[Convert]::FromBase64String($blob.content))
}

function Test-GitBlob([string]$path,$entry) {
    if(!(Test-Path -LiteralPath $path -PathType Leaf)){return $false}
    $file=[IO.File]::OpenRead($path)
    $algorithm=[Security.Cryptography.SHA1]::Create()
    try {
        if($file.Length -ne $entry.size){return $false}
        $hashStream=[Security.Cryptography.CryptoStream]::new(
            [IO.Stream]::Null,$algorithm,[Security.Cryptography.CryptoStreamMode]::Write)
        try {
            $header=[Text.Encoding]::ASCII.GetBytes(('blob '+$file.Length+[char]0))
            $hashStream.Write($header,0,$header.Length)
            $file.CopyTo($hashStream)
            $hashStream.FlushFinalBlock()
            return ([BitConverter]::ToString($algorithm.Hash).Replace('-','')) -eq $entry.sha
        } finally {$hashStream.Dispose()}
    } finally {
        $file.Dispose()
        $algorithm.Dispose()
    }
}

function Test-AppRunning {
    $prefix=$root.TrimEnd('\')+'\'
    return [bool](Get-Process -Name java,javaw,node -ErrorAction SilentlyContinue | Where-Object {
        $_.Path -and $_.Path.StartsWith($prefix,[StringComparison]::OrdinalIgnoreCase)
    })
}

function Start-Desk {
    foreach($path in $requiredApp){
        if(!(Test-Path -LiteralPath (Get-LocalPath $path) -PathType Leaf)){
            throw "本机缺少 $path，请联网完成首次安装。"
        }
    }
    $options=@{FilePath=(Get-LocalPath 'runtime/bin/javaw.exe');WorkingDirectory=(Get-LocalPath 'report-desk');
        ArgumentList=@('-Dfile.encoding=UTF-8','-cp','report-desk-desktop.jar;..\lib\*','Desk')}
    Start-Process @options
}

function New-FileChange([string]$target,[string]$incoming) {
    return [pscustomobject]@{Target=$target;Incoming=$incoming;Backup=$null;Applied=$false}
}

function Install-Changes($changes,[string]$work) {
    try {
        foreach($change in $changes){
            $target=$change.Target
            [IO.Directory]::CreateDirectory((Split-Path -Parent $target)) | Out-Null
            if([IO.File]::Exists($target)){
                $change.Backup=Join-Path $work ([guid]::NewGuid().ToString('N')+'.bak')
                if($change.Incoming){[IO.File]::Replace($change.Incoming,$target,$change.Backup)}
                else {[IO.File]::Move($target,$change.Backup)}
            } else {
                [IO.File]::Move($change.Incoming,$target)
            }
            $change.Applied=$true
        }
    } catch {
        for($index=$changes.Count-1;$index -ge 0;$index--){
            $change=$changes[$index]
            try {
                if($change.Backup -and [IO.File]::Exists($change.Backup)){
                    if([IO.File]::Exists($change.Target)){
                        [IO.File]::Replace($change.Backup,$change.Target,[NullString]::Value)
                    }
                    else {[IO.File]::Move($change.Backup,$change.Target)}
                } elseif($change.Applied){
                    [IO.File]::Delete($change.Target)
                }
            } catch {
                $script:keepBackup=$true
                Write-Warning "无法恢复 $($change.Target)：$($_.Exception.Message)"
            }
        }
        if($keepBackup){Write-Warning "请保留备份目录 $work 并联系维护者。"}
        throw
    }
}

function Sync-Desk {
    $statePath=Get-LocalPath 'installed-files.json'
    $previous=$null
    if(Test-Path -LiteralPath $statePath -PathType Leaf){
        try {$previous=Get-Content -LiteralPath $statePath -Raw -Encoding UTF8 | ConvertFrom-Json}
        catch {Write-Warning '本地更新记录已损坏，将重新检查文件。'}
    }
    Write-Host "正在检查 Gitee 仓库 $repo 的 $branch 分支 ..."
    $remote=Get-RepositoryFiles
    $changes=[Collections.Generic.List[object]]::new()
    $downloads=[Collections.Generic.List[object]]::new()
    $current=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach($entry in $remote.Files){
        [void]$current.Add($entry.path)
        $target=Get-LocalPath $entry.path
        if(!(Test-GitBlob $target $entry)){$downloads.Add(@{Entry=$entry;Target=$target})}
    }
    foreach($entry in $previous.files){
        $path=[string]$entry.path
        if(!(Test-ManagedPath $path) -or $current.Contains($path)){continue}
        $target=Get-LocalPath $path
        if(Test-GitBlob $target $entry){$changes.Add((New-FileChange $target $null))}
    }

    if(!$downloads.Count -and !$changes.Count -and $previous.commit -eq $remote.Commit){
        Write-Host "已同步到 $($remote.Commit.Substring(0,12))，无需更新。"
        return
    }
    if(Test-AppRunning){throw '正在运行查询台或采集任务，关闭后再更新。'}
    $script:work=Get-LocalPath ('.update-'+[guid]::NewGuid().ToString('N'))
    [IO.Directory]::CreateDirectory($work) | Out-Null
    # Download and verify every changed file before replacing any installed file.
    $index=0
    foreach($download in $downloads){
        $entry=$download.Entry
        $index++
        Write-Progress -Activity '正在同步查询台' -Status $entry.path -PercentComplete (100*$index/$downloads.Count)
        $incoming=Join-Path $work "$index.download"
        Save-RepositoryBlob $entry $incoming
        if(!(Test-GitBlob $incoming $entry)){throw "下载文件与 Gitee 记录不一致：$($entry.path)"}
        $changes.Add((New-FileChange $download.Target $incoming))
    }
    Write-Progress -Activity '正在同步查询台' -Completed

    $stagedState=Join-Path $work 'state.json'
    @{commit=$remote.Commit;files=$remote.Files} | ConvertTo-Json -Depth 4 |
        Set-Content -LiteralPath $stagedState -Encoding UTF8
    $changes.Add((New-FileChange $statePath $stagedState))
    if(Test-AppRunning){throw '下载期间启动了查询台或采集任务，关闭后再更新。'}
    Install-Changes $changes $work
    Write-Host "已同步到 $($remote.Commit.Substring(0,12))，下载了 $($downloads.Count) 个文件。"
}

$work=$null
$lock=$null
$keepBackup=$false
$mirror=$null
$status=0
try {
    $lockPath=Get-LocalPath '.update.lock'
    try {$lock=[IO.File]::Open($lockPath,'OpenOrCreate','ReadWrite','None')}
    catch {throw '无法锁定安装目录。请确认目录可写，或等待另一次启动或更新完成。'}

    try {
        [Net.ServicePointManager]::SecurityProtocol=[Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
        $source=Get-LocalPath 'update-source.txt'
        if(Test-Path -LiteralPath $source -PathType Leaf){
            $value=(Get-Content -LiteralPath $source -Raw -Encoding UTF8).Trim()
            $uri=$null
            if(![uri]::TryCreate($value,[UriKind]::Absolute,[ref]$uri) -or $uri.Scheme -ne 'https' -or
                $uri.UserInfo -or $uri.Query -or $uri.Fragment -or $uri.AbsolutePath.EndsWith('.json')){
                throw 'update-source.txt 须填写可信的 HTTPS 转发前缀。'
            }
            $mirror=$uri.AbsoluteUri.TrimEnd('/')+'/'
        }
        Sync-Desk
    } catch {
        if(!$Launch -or $keepBackup){throw}
        Write-Warning "未能同步最新版本，将尝试打开已安装版本。原因：$($_.Exception.Message)"
    }
    if($Launch -and !(Test-AppRunning)){Start-Desk}
} catch {
    $status=1
    Write-Host "更新或启动失败：$($_.Exception.Message)" -ForegroundColor Red
} finally {
    Write-Progress -Activity '正在同步查询台' -Completed
    if($work -and !$keepBackup){
        try {
            $item=Get-Item -LiteralPath $work -Force
            if($item.Parent.FullName -ne $root -or $item.Name -notmatch '^\.update-[a-f0-9]{32}$' -or
                ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)){throw '临时目录路径不正确。'}
            [IO.Directory]::Delete($item.FullName,$true)
        } catch {Write-Warning "无法清理临时目录 $work。"}
    }
    if($lock){$lock.Dispose()}
}
exit $status
