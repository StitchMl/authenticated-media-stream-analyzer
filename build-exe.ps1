# Crea l'installer Windows (.exe) con jpackage.
# Requisiti: JDK 17+ (con jpackage), Maven, WiX Toolset 3.x nel PATH.
param(
    [string]$Version = ""
)
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

if (-not $Version) {
    [xml]$pom = Get-Content pom.xml
    $Version = $pom.project.version
}

mvn -B clean package -DskipTests
if ($LASTEXITCODE -ne 0) { throw "Build Maven fallita" }

$jar = "teams-stream-lecture-downloader-$Version.jar"
$staging = "target\jpackage-input"
New-Item -ItemType Directory -Force $staging | Out-Null
Copy-Item "target\$jar" $staging

jpackage --type exe `
    --name "Teams Stream Lecture Downloader" `
    --app-version $Version `
    --vendor "LaGioia Production" `
    --input $staging `
    --main-jar $jar `
    --main-class it.lagioiaproduction.app.TeamsLectureDownloaderApp `
    --icon src\main\resources\icon\app.ico `
    --win-upgrade-uuid "6f4f3c1e-2b1a-4c47-9d0e-7a1b5e3c9d21" `
    --win-menu --win-shortcut --win-dir-chooser `
    --dest target\dist
if ($LASTEXITCODE -ne 0) { throw "jpackage fallito" }

Get-ChildItem target\dist\*.exe
