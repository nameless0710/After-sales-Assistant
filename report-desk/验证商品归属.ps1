param([Parameter(Mandatory=$true)][datetime]$Day,[string]$Warehouse='所有仓库')
$ErrorActionPreference='Stop'
$root=Join-Path $env:LOCALAPPDATA 'AfterSalesDesk'
$batch=Join-Path $root ('audit-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $batch | Out-Null
try {
    $date=$Day.ToString('yyyy-MM-dd')
    $request=@{code='*';start=$date;end=$date;warehouse=$Warehouse} | ConvertTo-Json
    $requestFile=Join-Path $batch 'query.json'
    [IO.File]::WriteAllText($requestFile,$request,(New-Object Text.UTF8Encoding($false)))
    & (Join-Path $PSScriptRoot 'driver/node.exe') (Join-Path $PSScriptRoot 'browser.cjs') $requestFile --audit-grain
    if($LASTEXITCODE){throw '未通过商品归属验证，按商品查询模式仍可使用。'}
} finally {
    foreach($name in @('query.json','sales.json','sales.xlsx','a.json','a.xlsx','b.json','b.xlsx','ab.json','ab.xlsx')){
        $file=Join-Path $batch $name
        if(Test-Path -LiteralPath $file){Remove-Item -LiteralPath $file -Force}
    }
    [IO.Directory]::Delete($batch)
}
