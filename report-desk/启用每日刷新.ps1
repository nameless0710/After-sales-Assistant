param(
    [string]$Time='08:30',
    [ValidateSet('Enable','Disable','Status')][string]$Mode='Enable'
)
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$name='AfterSalesDesk-Daily'
$command=Join-Path $PSScriptRoot 'daily.cmd'
$oldArguments='/d /c ""'+$command+'""'
$arguments='/d /c ""'+$command+'" --scheduled"'
$existing=Get-ScheduledTask -ErrorAction Stop | Where-Object {$_.TaskName -eq $name -and $_.TaskPath -eq '\'}
if($existing -and ($existing.Actions.Count -ne 1 -or $existing.Actions[0].Execute -ne $env:ComSpec -or
    $existing.Actions[0].Arguments -notin @($oldArguments,$arguments))){
    throw '当前每日刷新任务来自另一个安装目录，请在原安装目录的查询台中管理。'
}
switch($Mode){
    'Enable' {
        if($existing.State -eq 'Running'){throw '每日刷新正在运行，完成后可重新设置计划。'}
        $action=New-ScheduledTaskAction -Execute $env:ComSpec -Argument $arguments
        $at=[datetime]::ParseExact($Time,'HH:mm',[Globalization.CultureInfo]::InvariantCulture)
        $trigger=New-ScheduledTaskTrigger -Daily -At $at
        $settings=New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Hours 12)
        $user=[System.Security.Principal.WindowsIdentity]::GetCurrent().Name
        $principal=New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Limited
        $existing=Register-ScheduledTask -TaskName $name -Action $action -Trigger $trigger -Principal $principal -Settings $settings -Force
    }
    'Disable' {
        if($existing -and $existing.Settings.Enabled){$existing=Disable-ScheduledTask -InputObject $existing}
    }
}
if(!$existing -or !$existing.Settings.Enabled){
    Write-Output '每日自动刷新已关闭。'
}else{
    $timeOfDay=([datetimeoffset]::Parse($existing.Triggers[0].StartBoundary)).ToLocalTime().ToString('HH:mm')
    $catchUp=if($existing.Settings.StartWhenAvailable){'错过后会补跑。'}else{'错过当天的时间后不补跑。'}
    $running=if($existing.State -eq 'Running'){' 当前正在运行。'}else{''}
    Write-Output "每日自动刷新已开启，每天 $timeOfDay。$catchUp$running"
}
