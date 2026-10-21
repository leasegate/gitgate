# Build gitgate.exe and, only when asked, publish it as a GitHub release and write its update file.
# Run from this folder:
#
#     .\release.ps1 -Version 0.2.1-beta.1             build and checksum only; nothing leaves this machine
#     .\release.ps1 -Version 0.2.1-beta.1 -Publish    also publish the GitHub release and write the update file
#
# -Version X.Y.Z is a stable release; X.Y.Z-label (0.2.1-beta.1) is a pre-release on the beta channel, which
# the stable channel, followed by every installed X.Y.Z, never sees.
#
# The build is made from a copy of HEAD (git archive), in a temporary folder, so it matches a public commit
# and never touches target\ here: a running gitgate reads its jar from there. Nothing is installed anywhere.
#
# The release is v<Version> in github.com/leasegate/gitgate with: gitgate.exe, gitgate-<sha8>.jar,
# version.txt, SHA256SUMS. The update file gitgate.json is written into the site working copy
# ..\leasegate.github.io\gitgate\update\ only after the release exists and every asset url answers; the script
# never commits or pushes the site. $env:GH names the GitHub CLI to use (default: gh).

param([Parameter(Mandatory)][string]$Version, [switch]$Publish)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

$gh = if ($env:GH) { $env:GH } else { 'gh' }
$name = 'gitgate'
$repo = 'leasegate/gitgate'
$tag = "v$Version"
$pre = $Version -match '-'
$site = Join-Path $PSScriptRoot "..\leasegate.github.io\gitgate\update"
$update = Join-Path $site "$name.json"

# Only lines shaped like git's own output are trusted: a git wrapper that picks an identity may print a banner.
function Sha([string]$ref) { @(git rev-parse $ref) | Where-Object { $_ -match '^[0-9a-f]{40}$' } | Select-Object -Last 1 }

if ($Publish) {
    $dirty = @(git status --porcelain) | Where-Object { $_ -match '^[ MADRCU?!]{2} ' }
    if ($dirty) { throw "the working tree is not clean - commit first, so the release matches a public commit:`n$($dirty -join "`n")" }
    git fetch -q origin
    if ((Sha HEAD) -ne (Sha '@{u}')) { throw 'HEAD is not pushed - push first' }
    if (-not (Test-Path (Join-Path $PSScriptRoot '..\leasegate.github.io\.git'))) { throw "no site working copy at ..\leasegate.github.io - the update file goes there" }
}

$work = Join-Path ([IO.Path]::GetTempPath()) "$name-release-$Version"
if (Test-Path $work) { Remove-Item -Recurse -Force $work }
New-Item -ItemType Directory -Path $work | Out-Null
$tar = Join-Path $work 'src.tar'
# A dry run may build uncommitted changes to tracked files (git stash create makes a commit object for them
# without touching the tree); a publish is refused above unless the tree is clean, so it builds HEAD exactly.
$ref = 'HEAD'
if (-not $Publish) {
    # git prints a line-ending warning on stderr, which Windows PowerShell 5.1 turns into an error under 'Stop'
    $ErrorActionPreference = 'Continue'
    $wip = Sha (@(git stash create) | Where-Object { $_ -match '^[0-9a-f]{40}$' } | Select-Object -Last 1)
    $ErrorActionPreference = 'Stop'
    if ($wip) { $ref = $wip; Write-Host 'dry run: building the uncommitted working tree' }
}
git archive --format=tar -o $tar $ref
if ($LASTEXITCODE -ne 0) { throw 'git archive failed' }
if ($LASTEXITCODE -ne 0) { throw 'git archive failed' }
$src = Join-Path $work 'src'
New-Item -ItemType Directory -Path $src | Out-Null
tar -xf $tar -C $src
if ($LASTEXITCODE -ne 0) { throw 'tar failed' }

# -Djrexe.version names the exe's own version; -Djrexe.channel keeps a beta off the stable channel;
# -Djr.updateMergeFrom keeps the releases and channels already in the site's update file.
$mvnArgs = @('-B', '-q', '-DskipTests', '-pl', 'core,app', '-Djrexe', '-Djr.source=url', "-Djrexe.version=$Version")
if ($pre) { $mvnArgs += '-Djrexe.channel=beta' }
if (Test-Path $update) { $mvnArgs += "-Djr.updateMergeFrom=$((Resolve-Path $update).Path)" }
Write-Host "building $tag from $(Sha HEAD)"
Push-Location $src
try {
    # The network here drops connections mid-download often enough that one retry is not enough.
    for ($i = 1; $i -le 4; $i++) {
        & mvn @mvnArgs package
        if ($LASTEXITCODE -eq 0) { break }
        if ($i -eq 4) { throw 'mvn package failed' }
        Write-Host "build attempt $i failed, retrying"
    }
} finally { Pop-Location }

$out = Join-Path $src 'app\target\jr\release'
foreach ($f in @("$name.exe", "$name-????????.jar", 'version.txt', 'SHA256SUMS', "$name.update.json")) {
    if (-not (Test-Path (Join-Path $out $f))) { throw "the release folder has no $f - see the jr:exe output above" }
}
Get-ChildItem $out | ForEach-Object { Write-Host ('  {0,-28} {1,7:N1} MB' -f $_.Name, ($_.Length / 1MB)) }
if (-not $Publish) { Write-Host "dry run - nothing published. Assets are in $out"; return }

$assets = (Get-ChildItem $out -File | Where-Object Name -ne "$name.update.json").FullName
$notes = "$name $Version. Windows: download $name.exe and run it; it fetches its jar from this release, checked against the sha256 it carries. Update later with Check for updates in its window."
if ($pre) { $notes += " This is a pre-release: installed stable copies are not offered it." }
$notesFile = Join-Path $work 'release-notes.md'
[IO.File]::WriteAllText($notesFile, $notes + "`n")
$latest = if ($pre) { @('--prerelease', '--latest=false') } else { @('--latest=true') }
# gh prints an ACCOUNT notice on stderr, which Windows PowerShell 5.1 turns into an error under 'Stop'
$ErrorActionPreference = 'Continue'
& $gh release create $tag $assets --repo $repo --target (Sha HEAD) --title "$name $Version" --notes-file $notesFile @latest
$ghExit = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
if ($ghExit -ne 0) { throw 'gh release create failed' }
Write-Host "published $tag"

# GET every asset url BEFORE the update file goes anywhere: GitHub has served an asset with HTTP 500 on its
# public url while the API had it (cfucli 0.5.1).
$base = "https://github.com/$repo/releases/download/$tag"
foreach ($a in $assets) {
    $n = Split-Path $a -Leaf
    $tmp = Join-Path $work "check-$n"
    & curl.exe -fsSL -o $tmp "$base/$n"
    if ($LASTEXITCODE -ne 0) { throw "GET $base/$n failed - do not publish the update file" }
    if ((Get-Item $tmp).Length -ne (Get-Item $a).Length) { throw "$n differs in size from what was uploaded" }
    Remove-Item $tmp
}
Write-Host 'every asset url answers'

New-Item -ItemType Directory -Force -Path $site | Out-Null
Copy-Item (Join-Path $out "$name.update.json") $update -Force
Write-Host "update file written to $update - commit and push the site repo to offer $Version to Check for updates"
