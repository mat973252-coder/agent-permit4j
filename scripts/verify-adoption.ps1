param([string]$MavenRepository)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$work = Join-Path ([System.IO.Path]::GetTempPath()) ('agent-permit-adoption-' + [guid]::NewGuid().ToString('N'))
$consumer = Join-Path $work 'consumer'
if (-not $MavenRepository) { $MavenRepository = Join-Path $work 'repository' }
$MavenRepository = [System.IO.Path]::GetFullPath($MavenRepository)
$wrapper = if ([Environment]::OSVersion.Platform -eq 'Win32NT') { 'mvnw.cmd' } else { 'mvnw' }
$wrapper = Join-Path $projectRoot $wrapper
$source = Join-Path $projectRoot 'examples/spring-ai-adoption'
[xml]$sdkPom = Get-Content -LiteralPath (Join-Path $projectRoot 'pom.xml') -Raw
$sdkVersion = $sdkPom.project.version
[xml]$consumerPom = Get-Content -LiteralPath (Join-Path $source 'pom.xml') -Raw
if ($consumerPom.project.parent) { throw 'The adoption consumer must not inherit a parent POM.' }
if ($consumerPom.project.dependencies.dependency.artifactId -contains 'agent-permit-playground') {
    throw 'The adoption consumer must not depend on Playground.'
}

# A new consumer directory prevents existing target/classes from masking artifact problems.
New-Item -ItemType Directory -Path $consumer -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $source 'pom.xml') -Destination $consumer
Copy-Item -LiteralPath (Join-Path $source 'src') -Destination $consumer -Recurse
$repositoryArgument = "-Dmaven.repo.local=$MavenRepository"
& $wrapper -B -ntp -f (Join-Path $projectRoot 'pom.xml') $repositoryArgument -Pcandidate -DskipTests -pl '!agent-permit-playground' clean install
if ($LASTEXITCODE -ne 0) { throw "SDK candidate build failed; evidence retained at $work" }

$artifactRoot = Join-Path $projectRoot 'target/candidate'
$bundle = Join-Path $artifactRoot $sdkVersion
New-Item -ItemType Directory -Path $bundle -Force | Out-Null
$parentPom = Join-Path $MavenRepository "io/github/mat973252/agent-permit4j/$sdkVersion/agent-permit4j-$sdkVersion.pom"
Copy-Item -LiteralPath $parentPom -Destination $bundle
foreach ($module in $sdkPom.project.modules.module) {
    if ($module -eq 'agent-permit-playground') { continue }
    foreach ($suffix in @('.pom', '.jar', '-sources.jar', '-javadoc.jar')) {
        $name = "$module-$sdkVersion$suffix"
        $artifact = Join-Path $MavenRepository "io/github/mat973252/$module/$sdkVersion/$name"
        if (-not (Test-Path -LiteralPath $artifact -PathType Leaf)) { throw "Missing candidate artifact: $name" }
        Copy-Item -LiteralPath $artifact -Destination $bundle
    }
}
Get-ChildItem -LiteralPath $bundle -File | Sort-Object Name | ForEach-Object {
    $digest = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    "$digest  $($_.Name)"
} | Set-Content -LiteralPath (Join-Path $bundle 'SHA256SUMS') -Encoding ascii

# This second Maven invocation has only the standalone POM and installed SDK artifacts.
$consumerArguments = @('-B', '-ntp', '-f', (Join-Path $consumer 'pom.xml'), $repositoryArgument, "-Dagent-permit.version=$sdkVersion")
& $wrapper @consumerArguments verify
if ($LASTEXITCODE -ne 0) { throw "Consumer verification failed; evidence retained at $work" }
& $wrapper @consumerArguments -o verify
if ($LASTEXITCODE -ne 0) { throw "Offline consumer verification failed; evidence retained at $work" }
Write-Output "ADOPTION VERIFIED version=$sdkVersion repository=$MavenRepository consumer=$consumer artifacts=$bundle"
