param(
    [string]$MavenCommand = '.\mvnw.cmd',
    [string]$MavenRepository = ''
)
$ErrorActionPreference = 'Stop'
$fixtureDatabase = 'notification_disposable_' + [Guid]::NewGuid().ToString('N')
$fixtureIdentity = [Guid]::NewGuid().ToString('N')
$fixtureContainer = $null
Push-Location (Join-Path $PSScriptRoot '..')
try {
    # Always create a fresh container. Never accept a pre-existing container, host or database.
    $createdContainer = & docker run --detach --rm --publish 127.0.0.1::5432 --env "POSTGRES_DB=$fixtureDatabase" --env POSTGRES_USER=notification_test --env POSTGRES_PASSWORD=notification_test_only postgres:16-alpine
    if ($LASTEXITCODE -ne 0 -or $createdContainer -notmatch '^[a-f0-9]{64}$') { throw 'Disposable container creation failed' }
    $fixtureContainer = $createdContainer.Trim()
    $ready = $false
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        & docker exec $fixtureContainer pg_isready -U notification_test -d $fixtureDatabase *> $null
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        Start-Sleep -Seconds 1
    }
    if (-not $ready) { throw 'Disposable PostgreSQL did not become ready' }
    # Marker provisioned only through docker exec on the freshly returned container ID.
    # UUID-only values are generated above, not user-supplied SQL or credentials.
    & docker exec $fixtureContainer psql -v ON_ERROR_STOP=1 -U notification_test -d $fixtureDatabase -c "COMMENT ON DATABASE $fixtureDatabase IS 'vigo-notification-disposable:$fixtureIdentity'"
    if ($LASTEXITCODE -ne 0) { throw 'Fixture marker provisioning failed' }
    $binding = & docker port $fixtureContainer 5432/tcp
    if ($LASTEXITCODE -ne 0 -or $binding -notmatch '^127\.0\.0\.1:([0-9]+)$') { throw 'Unexpected disposable port binding' }
    $fixturePort = $Matches[1]
    $mavenArguments = @('-B', '-ntp', "-Dnotification.test.postgres.port=$fixturePort",
        "-Dnotification.test.postgres.database=$fixtureDatabase", "-Dnotification.test.postgres.identity=$fixtureIdentity", 'verify')
    if ($MavenRepository) { $mavenArguments = @("-Dmaven.repo.local=$MavenRepository") + $mavenArguments }
    & $MavenCommand @mavenArguments
    if ($LASTEXITCODE -ne 0) { throw 'Disposable PostgreSQL verification failed' }
} finally {
    if ($fixtureContainer) {
        & docker stop $fixtureContainer
        if ($LASTEXITCODE -ne 0) { Write-Warning "Could not remove disposable container $fixtureContainer; remove it manually." }
    }
    Pop-Location
}
