param(
    [string]$MavenCommand = '.\mvnw.cmd',
    [string]$MavenRepository = '',
    [string]$Test = '',
    [switch]$WithKafka
)
$ErrorActionPreference = 'Stop'
$fixtureDatabase = 'notification_disposable_' + [Guid]::NewGuid().ToString('N')
$fixtureIdentity = [Guid]::NewGuid().ToString('N')
$fixtureContainer = $null
$kafkaContainer = $null
Push-Location (Join-Path $PSScriptRoot '..')
try {
    # Always create a fresh container. Never accept a pre-existing container, host or database.
    $createdContainer = & docker run --detach --rm --publish 127.0.0.1::5432 --env "POSTGRES_DB=$fixtureDatabase" --env POSTGRES_USER=notification_test --env POSTGRES_PASSWORD=notification_test_only postgres:16-alpine
    if ($LASTEXITCODE -ne 0 -or $createdContainer -notmatch '^[a-f0-9]{64}$') { throw 'Disposable container creation failed' }
    $fixtureContainer = $createdContainer.Trim()
    $ready = $false
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        # The image uses a socket-only PostgreSQL instance during initialization.
        & docker exec $fixtureContainer pg_isready -h 127.0.0.1 -U notification_test -d $fixtureDatabase *> $null
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
    if ($Test) { $mavenArguments = @("-Dtest=$Test") + $mavenArguments }
    if ($WithKafka) {
        # Choose a loopback port for Kafka's advertised listener. Docker fails if it is taken
        # before bind, and the test verifies the generated cluster identity before any writes.
        $socket = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
        $socket.Start()
        try { $kafkaPort = $socket.LocalEndpoint.Port } finally { $socket.Stop() }
        $cluster = [Convert]::ToBase64String([Guid]::NewGuid().ToByteArray()).TrimEnd('=').Replace('+', '-').Replace('/', '_')
        $kafka = & docker run --detach --rm --publish "127.0.0.1:${kafkaPort}:${kafkaPort}" `
            --env "CLUSTER_ID=$cluster" --env KAFKA_NODE_ID=1 --env KAFKA_PROCESS_ROLES=broker,controller `
            --env "KAFKA_LISTENERS=PLAINTEXT://0.0.0.0:${kafkaPort},CONTROLLER://0.0.0.0:9093" `
            --env "KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://127.0.0.1:$kafkaPort" `
            --env KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER --env KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9093 `
            --env KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 --env KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS=0 `
            --env KAFKA_AUTO_CREATE_TOPICS_ENABLE=false apache/kafka:4.2.1
        if ($LASTEXITCODE -ne 0 -or $kafka -notmatch '^[a-f0-9]{64}$') { throw 'Disposable Kafka creation failed' }
        $kafkaContainer = $kafka.Trim()
        $mavenArguments = @("-Dnotification.test.kafka.port=$kafkaPort", "-Dnotification.test.kafka.cluster=$cluster") + $mavenArguments
    }
    if ($MavenRepository) { $mavenArguments = @("-Dmaven.repo.local=$MavenRepository") + $mavenArguments }
    & $MavenCommand @mavenArguments
    if ($LASTEXITCODE -ne 0) { throw 'Disposable PostgreSQL verification failed' }
} finally {
    if ($kafkaContainer) {
        & docker stop $kafkaContainer
        if ($LASTEXITCODE -ne 0) { Write-Warning "Could not remove disposable Kafka container $kafkaContainer." }
    }
    if ($fixtureContainer) {
        & docker stop $fixtureContainer
        if ($LASTEXITCODE -ne 0) { Write-Warning "Could not remove disposable container $fixtureContainer; remove it manually." }
    }
    Pop-Location
}
