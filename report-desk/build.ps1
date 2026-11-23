param([string]$Jdk=$env:JAVA_HOME,[string]$NodeModules=$env:AFTERSALES_NODE_MODULES)
$ErrorActionPreference='Stop'
Push-Location -LiteralPath $PSScriptRoot
try {
    if (!$Jdk) { $Jdk=Split-Path (Split-Path (Get-Command javac).Source) }
    $classes=Join-Path ([IO.Path]::GetTempPath()) ('report-desk-'+[guid]::NewGuid())
    New-Item -ItemType Directory -Path $classes | Out-Null
    $sources=(Get-ChildItem -LiteralPath . -Filter '*.java').FullName
    & "$Jdk/bin/javac.exe" --release 17 -encoding UTF-8 -cp '../lib/*' -d $classes $sources
    if($LASTEXITCODE){throw 'Java compilation failed'}
    & "$Jdk/bin/jar.exe" --create --file report-desk-desktop.jar -C $classes .
    if($LASTEXITCODE){throw 'JAR packaging failed'}
    if($NodeModules) {
        $modules=Join-Path $PSScriptRoot 'driver/node_modules'
        New-Item -ItemType Directory -Force -Path $modules | Out-Null
        $nodeExe=(Get-Command node).Source
        Copy-Item -LiteralPath $nodeExe -Destination driver/node.exe -Force
        Get-ChildItem -LiteralPath (Split-Path $nodeExe) -Filter '*.dll' -File | Copy-Item -Destination driver -Force
        Copy-Item -LiteralPath (Join-Path $NodeModules 'playwright-core') -Destination $modules -Recurse -Force
    }
} finally {Pop-Location}
