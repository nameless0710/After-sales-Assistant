param([string]$OutputFile)
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.IO.Compression,System.IO.Compression.FileSystem
$project=(Get-Item -LiteralPath $PSScriptRoot).Parent.FullName
if(!$OutputFile){$OutputFile=Join-Path $project '商品售后查询台-Windows.zip'}
$files=@('启动商品售后查询台.cmd','更新工具.cmd','update.ps1','lib/gson-2.13.2.jar',
    'report-desk/report-desk-desktop.jar','report-desk/browser.cjs','report-desk/browser-settings.json',
    'report-desk/remark-aliases.json','report-desk/daily.cmd','report-desk/启用每日刷新.ps1',
    'report-desk/验证商品归属.ps1','report-desk/使用说明.md')
$files+=@(foreach($folder in @('runtime','report-desk/driver')){
    foreach($file in (Get-ChildItem -LiteralPath (Join-Path $project $folder) -Recurse -File)){
        $file.FullName.Substring($project.Length+1)
    }
})
$tmp=$OutputFile+'.'+[guid]::NewGuid().ToString('N')+'.tmp'
try{
    $zip=[IO.Compression.ZipFile]::Open($tmp,[IO.Compression.ZipArchiveMode]::Create)
    try{
        foreach($relative in $files){
            $entry=$relative.Replace('\','/')
            [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,(Join-Path $project $relative),$entry,[IO.Compression.CompressionLevel]::Optimal) | Out-Null
        }
    }finally{$zip.Dispose()}
    if([IO.File]::Exists($OutputFile)){
        $previous=$tmp+'.previous'
        [IO.File]::Replace($tmp,$OutputFile,$previous)
        [IO.File]::Delete($previous)
    }else{[IO.File]::Move($tmp,$OutputFile)}
    Get-Item -LiteralPath $OutputFile | Select-Object FullName,Length
}finally{if(Test-Path -LiteralPath $tmp){[IO.File]::Delete($tmp)}}
