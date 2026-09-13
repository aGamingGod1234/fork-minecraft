[CmdletBinding()]
param(
	[switch] $SetupFailureOnly,
	[switch] $KeepFixture
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptPath = Join-Path $PSScriptRoot 'run-headless-provider-matrix.ps1'
if (-not (Test-Path -LiteralPath $scriptPath -PathType Leaf)) {
	throw 'Expected lifecycle wrapper to exist'
}
$wrapperSource = Get-Content -Raw -LiteralPath $scriptPath
if ($wrapperSource -notmatch "'cursor'\s*\{\s*return") {
	throw 'Expected lifecycle wrapper to preflight the native Cursor agent'
}
if ($wrapperSource -notmatch '\$MaxSelectedScenarios\s*=\s*24') {
	throw 'Expected lifecycle wrapper to allow the complete 18-profile provider matrix within a 24-scenario bound'
}
if ($wrapperSource -notmatch 'Test-CoordinatorReady\s+\$protocolAudit\s+\$Scenario') {
	throw 'Expected lifecycle runs to wait for the scenario provider catalog before summoning'
}

function Assert-Fails([scriptblock] $Action, [string] $Pattern) {
	try {
		& $Action 2>&1 | Out-Null
		throw "Expected failure matching '$Pattern'"
	} catch {
		if ($_.Exception.Message -notmatch $Pattern) {
			throw "Failure did not match '$Pattern': $($_.Exception.Message)"
		}
	}
}

function Set-TestEnvironment([string] $Name, [string] $Value) {
	[Environment]::SetEnvironmentVariable($Name, $Value)
}

function New-Fixture([string] $Root) {
	New-Item -ItemType Directory -Path (Join-Path $Root 'build\libs'), (Join-Path $Root 'runtime\server-template\mods'), (Join-Path $Root 'runtime\server-template\logs'), (Join-Path $Root 'runtime\server-template\world'), (Join-Path $Root 'coordinator\config') -Force | Out-Null
	Copy-Item -LiteralPath (Join-Path $PSScriptRoot '..\coordinator\src') -Destination (Join-Path $Root 'coordinator\src') -Recurse -Force
	Copy-Item -LiteralPath (Join-Path $PSScriptRoot '..\coordinator\config\minecraft-agent') -Destination (Join-Path $Root 'coordinator\config\minecraft-agent') -Recurse -Force
	Copy-Item -LiteralPath (Join-Path $PSScriptRoot '..\coordinator\node_modules\acorn') -Destination (Join-Path $Root 'coordinator\node_modules\acorn') -Recurse -Force
	$fakeCodex = Join-Path $Root 'fake-appdata\npm\node_modules\@openai\codex\bin\codex.js'
	New-Item -ItemType Directory -Path (Split-Path -Parent $fakeCodex) -Force | Out-Null
@'
import readline from 'node:readline';
import { existsSync } from 'node:fs';
const input = readline.createInterface({ input: process.stdin });
input.on('line', (line) => {
    try {
        const request = JSON.parse(line);
        if (!Number.isInteger(request.id)) return;
        if (request.method === 'model/list' && existsSync(`${process.env.APPDATA}/no-catalog`)) return;
        const result = request.method === 'model/list' ? {
            data: [{
                id: 'fixture',
                model: 'fixture',
                supportedReasoningEfforts: [{ reasoningEffort: 'low' }],
                serviceTiers: [{ id: 'fast' }, { id: 'priority' }],
            }],
            nextCursor: null,
        } : {};
        process.stdout.write(`${JSON.stringify({ id: request.id, result })}\n`);
    } catch {}
});
'@ | Set-Content -LiteralPath $fakeCodex -NoNewline
	Set-Content -LiteralPath (Join-Path $Root 'build\libs\arena-agents-0.2.0.jar') -Value 'fixture' -NoNewline
	Set-Content -LiteralPath (Join-Path $Root 'runtime\server-template\fabric-server-launch.jar') -Value 'not-a-real-jar' -NoNewline
	Set-Content -LiteralPath (Join-Path $Root 'runtime\server-template\server.properties') -Value "online-mode=false`nserver-ip=127.0.0.1`nserver\-ip:0.0.0.0`n" -NoNewline
	Set-Content -LiteralPath (Join-Path $Root 'runtime\server-template\world\stale.dat') -Value 'must not be copied' -NoNewline
	$dynamicConfig = [pscustomobject]@{
		bridge = [pscustomobject]@{ host = '127.0.0.1'; port = 25570; secretEnvironmentVariable = 'ARENA_AGENT_BRIDGE_SECRET' }
		codex = [pscustomobject]@{
			launchProfile = [pscustomobject]@{ model = 'fixture'; reasoningEffort = 'low'; serviceTier = 'fast' }
		}
		limits = [pscustomobject]@{ agentCap = 1; goalQueueCap = 1; planningConcurrency = 1 }
	}
	Set-Content -LiteralPath (Join-Path $Root 'coordinator\config\dynamic-agents.json') -Value ($dynamicConfig | ConvertTo-Json -Depth 8) -NoNewline
	# The default fixture deliberately expects ERROR so the first run proves the
	# wrapper records a runner failure and still performs complete cleanup. The
	# normal-path fixture below expects COMPLETED.
	Set-Content -LiteralPath (Join-Path $Root 'matrix.json') -Value '{"version":1,"scenarios":[{"id":"fixture","provider":"codex","model":"fixture","reasoningEffort":"low","serviceTier":"fast","task":"fixture","timeoutMs":1000,"assert":[{"type":"lifecycle","state":"ERROR"}]}]}' -NoNewline
}

function Enable-FakeServer([string] $Root) {
	$source = Join-Path $Root 'FakeServer.java'
	$classes = Join-Path $Root 'fake-classes'
	$jar = Join-Path $Root 'runtime\server-template\fabric-server-launch.jar'
	New-Item -ItemType Directory -Path $classes -Force | Out-Null
@'
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class FakeServer {
    private static int readLittleEndian(InputStream input) throws IOException {
        int b0 = input.read();
        if (b0 < 0) return -1;
        int b1 = input.read();
        int b2 = input.read();
        int b3 = input.read();
        if ((b1 | b2 | b3) < 0) throw new IOException("truncated packet length");
        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }

    private static int readLittleEndian(byte[] payload, int offset) {
        return (payload[offset] & 0xff) | ((payload[offset + 1] & 0xff) << 8)
            | ((payload[offset + 2] & 0xff) << 16) | ((payload[offset + 3] & 0xff) << 24);
    }

    private static void writeLittleEndian(OutputStream output, int value) throws IOException {
        output.write(value & 0xff);
        output.write((value >>> 8) & 0xff);
        output.write((value >>> 16) & 0xff);
        output.write((value >>> 24) & 0xff);
    }

    private static void writeLittleEndian(byte[] target, int offset, int value) {
        target[offset] = (byte) value;
        target[offset + 1] = (byte) (value >>> 8);
        target[offset + 2] = (byte) (value >>> 16);
        target[offset + 3] = (byte) (value >>> 24);
    }

    private static void writeResponse(OutputStream output, int id, int type, String text) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[10 + body.length];
        writeLittleEndian(payload, 0, id);
        writeLittleEndian(payload, 4, type);
        System.arraycopy(body, 0, payload, 8, body.length);
        writeLittleEndian(output, payload.length);
        output.write(payload);
    }

    private static void serveRcon(Socket socket, AtomicBoolean running) {
        try (socket; InputStream input = socket.getInputStream(); OutputStream output = socket.getOutputStream()) {
            while (running.get()) {
                int length = readLittleEndian(input);
                if (length < 0) return;
                if (length < 10 || length > 1_048_576) throw new IOException("invalid RCON packet");
                byte[] payload = input.readNBytes(length);
                if (payload.length != length) throw new IOException("truncated RCON packet");
                int id = readLittleEndian(payload, 0);
                int type = readLittleEndian(payload, 4);
                String command = new String(payload, 8, length - 10, StandardCharsets.UTF_8);
                String summonResponse = System.getenv("ARENA_HEADLESS_FAKE_SUMMON_RESPONSE");
                String response = type == 3 ? "" : command.contains("summon-configured") ? (summonResponse == null ? "Created fixture_agent. It is ready for a task." : summonResponse) : command.startsWith("codex status") ? "state=COMPLETED" : "OK";
                writeResponse(output, id, type == 3 ? 2 : 0, response);
                output.flush();
            }
        } catch (IOException ignored) {
        }
    }

    private static int bridgePort() throws IOException {
        String config = Files.readString(Path.of("..", "coordinator-config.json"));
        Matcher matcher = Pattern.compile("\\\"bridge\\\"\\s*:\\s*\\{[^}]*\\\"port\\\"\\s*:\\s*(\\d+)").matcher(config);
        if (!matcher.find()) throw new IOException("bridge port missing from fixture config");
        return Integer.parseInt(matcher.group(1));
    }

    private static String jsonString(String json, String field) throws IOException {
        Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(field) + "\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(json);
        if (!matcher.find()) throw new IOException("missing JSON field: " + field);
        return matcher.group(1);
    }

    private static String optionalJsonString(String json, String field) {
        Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(field) + "\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String authenticationProof(String secret, String role, String clientNonce,
                                               String serverNonce, String serverInstanceId, String launchId) throws Exception {
        String context = String.join("\0", "arena-agents-v2", role, clientNonce, serverNonce, serverInstanceId)
            + ("coordinator".equals(role) ? "\0" + (launchId == null ? "" : launchId) : "");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(context.getBytes(StandardCharsets.UTF_8)));
    }

    private static void serveBridge(Socket socket, AtomicBoolean running) {
        try (socket; BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)); OutputStream output = socket.getOutputStream()) {
            String challenge = reader.readLine();
            if (challenge == null) return;
            String challengeId = jsonString(challenge, "messageId");
            String clientNonce = jsonString(challenge, "clientNonce");
            String serverNonce = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest("fake-server".getBytes(StandardCharsets.UTF_8))
            );
            String secret = Files.readString(Path.of(System.getProperty("arenaagents.bridgeSecretFile"))).trim();
            String serverProof = authenticationProof(secret, "server", clientNonce, serverNonce, "fake-server", null);
            String authResponse = "{\"protocolVersion\":2,\"serverInstanceId\":\"fake-server\",\"agentId\":\"server\",\"type\":\"auth_response\",\"messageId\":\"fake-auth\",\"payload\":{\"replyTo\":\"" + challengeId + "\",\"clientNonce\":\"" + clientNonce + "\",\"serverNonce\":\"" + serverNonce + "\",\"proof\":\"" + serverProof + "\"}}\n";
            output.write(authResponse.getBytes(StandardCharsets.UTF_8));
            output.flush();
            String hello = reader.readLine();
            if (hello == null) return;
            if ("1".equals(System.getenv("ARENA_HEADLESS_FAKE_NO_HELLO_ACK"))) return;
            String helloId = jsonString(hello, "messageId");
            String launchId = optionalJsonString(hello, "launchId");
            String coordinatorProof = jsonString(hello, "proof");
            String expectedProof = authenticationProof(secret, "coordinator", clientNonce, serverNonce, "fake-server", launchId);
            if (!MessageDigest.isEqual(coordinatorProof.getBytes(StandardCharsets.UTF_8), expectedProof.getBytes(StandardCharsets.UTF_8))) return;
            String launchField = launchId == null ? "" : ",\"launchId\":\"" + launchId + "\"";
            String response = "{\"protocolVersion\":2,\"serverInstanceId\":\"fake-server\",\"agentId\":\"server\",\"type\":\"hello_ack\",\"messageId\":\"fake-ack\",\"payload\":{\"replyTo\":\"" + helloId + "\",\"authenticated\":true,\"registry\":[]" + launchField + "}}\n";
            output.write(response.getBytes(StandardCharsets.UTF_8));
            String catalogRequest = "{\"protocolVersion\":2,\"serverInstanceId\":\"fake-server\",\"agentId\":\"server\",\"type\":\"catalog_request\",\"messageId\":\"fake-catalog-request\",\"payload\":{}}\n";
            output.write(catalogRequest.getBytes(StandardCharsets.UTF_8));
            output.flush();
            while (running.get() && reader.readLine() != null) { }
        } catch (Exception exception) {
            exception.printStackTrace();
        }
    }

    public static void main(String[] args) throws Exception {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(Path.of("server.properties"))) { properties.load(input); }
        int serverPort = Integer.parseInt(properties.getProperty("server-port"));
        int rconPort = Integer.parseInt(properties.getProperty("rcon.port"));
        int bridgePort = bridgePort();
        if (!Integer.toString(bridgePort).equals(System.getProperty("arenaagents.bridgePort"))) {
            throw new IOException("headless bridge port JVM property did not match coordinator config");
        }
        if (!"false".equals(System.getProperty("arenaagents.coordinatorAutoStart"))) {
            throw new IOException("headless coordinator auto-start was not disabled");
        }
        AtomicBoolean running = new AtomicBoolean(true);
        ServerSocket minecraft = new ServerSocket(serverPort, 16, InetAddress.getLoopbackAddress());
        ServerSocket rcon = new ServerSocket(rconPort, 16, InetAddress.getLoopbackAddress());
        ServerSocket bridge = new ServerSocket(bridgePort, 16, InetAddress.getLoopbackAddress());
        Files.createDirectories(Path.of("logs"));
        Files.createDirectories(Path.of(properties.getProperty("level-name"), "dimensions", "minecraft", "overworld", "data", "arenaagents"));
        OutputStream latestLog = Files.newOutputStream(Path.of("logs", "latest.log"), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        latestLog.write("Done (0.1s)!\n".getBytes(StandardCharsets.UTF_8));
        latestLog.flush();
        new ProcessBuilder("powershell.exe", "-NoProfile", "-Command", "Start-Sleep -Seconds 3").inheritIO().start();
        Thread acceptor = new Thread(() -> {
            while (running.get()) {
                try { serveRcon(rcon.accept(), running); } catch (IOException ignored) { return; }
            }
        });
        acceptor.setDaemon(true);
        acceptor.start();
        Thread bridgeAcceptor = new Thread(() -> {
            while (running.get()) {
                try {
                    Socket client = bridge.accept();
                    new Thread(() -> serveBridge(client, running)).start();
                } catch (IOException ignored) { return; }
            }
        });
        bridgeAcceptor.setDaemon(true);
        bridgeAcceptor.start();
        Thread stopReader = new Thread(() -> {
            try { new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine(); } catch (IOException ignored) { }
            running.set(false);
            try { minecraft.close(); } catch (IOException ignored) { }
            try { rcon.close(); } catch (IOException ignored) { }
            try { bridge.close(); } catch (IOException ignored) { }
        });
        stopReader.setDaemon(true);
        stopReader.start();
        while (running.get()) Thread.sleep(100L);
        latestLog.close();
    }
}
'@ | Set-Content -LiteralPath $source -NoNewline
	$pathJavac = (Get-Command javac -ErrorAction Stop).Source
	$registryJdkHome = try {
		$jdk = Get-ItemProperty 'HKLM:\SOFTWARE\JavaSoft\JDK' -ErrorAction Stop
		(Get-ItemProperty ("HKLM:\SOFTWARE\JavaSoft\JDK\" + $jdk.CurrentVersion) -ErrorAction Stop).JavaHome
	} catch { $null }
	$toolchainBins = @(
		(Split-Path -Parent $pathJavac),
		$(if (-not [string]::IsNullOrWhiteSpace($registryJdkHome)) { Join-Path $registryJdkHome 'bin' }),
		$(if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) { Join-Path $env:JAVA_HOME 'bin' })
	) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Unique
	$toolchainBin = $toolchainBins | Where-Object {
		(Test-Path -LiteralPath (Join-Path $_ 'java.exe') -PathType Leaf) -and
		(Test-Path -LiteralPath (Join-Path $_ 'javac.exe') -PathType Leaf) -and
		(Test-Path -LiteralPath (Join-Path $_ 'jar.exe') -PathType Leaf)
	} | Select-Object -First 1
	if ($null -eq $toolchainBin) { throw 'Could not find a complete test JDK containing java.exe, javac.exe, and jar.exe' }
	$javac = Join-Path $toolchainBin 'javac.exe'
	$jarTool = Join-Path $toolchainBin 'jar.exe'
	& $javac -d $classes $source
	if ($LASTEXITCODE -ne 0) { throw 'Could not compile dummy Fabric server fixture' }
	& $jarTool --create --file $jar --main-class FakeServer -C $classes FakeServer.class
	if ($LASTEXITCODE -ne 0) { throw 'Could not package dummy Fabric server fixture' }
	$java = Join-Path $toolchainBin 'java.exe'
	Set-TestEnvironment 'ARENA_HEADLESS_JAVA' $java
}

function Stop-TestProcessTree([int] $ProcessId) {
	$children = @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$ProcessId" -ErrorAction SilentlyContinue)
	foreach ($child in $children) { Stop-TestProcessTree ([int] $child.ProcessId) }
	Stop-Process -Id $ProcessId -Force -ErrorAction SilentlyContinue
}

function Test-PortClosed([int] $Port) {
	return $null -eq (Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue | Select-Object -First 1)
}

function Import-WrapperFunction([string] $Name) {
	$tokens = $null
	$errors = $null
	$ast = [Management.Automation.Language.Parser]::ParseInput($wrapperSource, [ref] $tokens, [ref] $errors)
	$definition = $ast.FindAll({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $Name }, $true) | Select-Object -First 1
	if ($null -eq $definition) { throw "Could not load wrapper function '$Name'" }
	$extent = $definition.Extent.Text
	$body = $definition.Body.Extent.Text
	$parameterStart = $extent.IndexOf('(')
	$bodyStart = $extent.IndexOf('{')
	$parameters = $extent.Substring($parameterStart, $bodyStart - $parameterStart)
	Set-Item -Path "Function:\script:$Name" -Value ([scriptblock]::Create("param$parameters`n" + $body.Substring(1, $body.Length - 2)))
}

function Test-FastExitResourceSampling([string] $WorkingDirectory) {
	foreach ($name in @('ConvertTo-ProcessCreationKey', 'Get-ProcessSnapshot', 'Test-ProcessIdentityMatch', 'Test-ChildCreationAfterParent', 'Add-ProcessTreeSnapshot', 'Add-TrackedProcessIdentity', 'Get-TrackedResourceSnapshot', 'Measure-RunnerResourcesUntilExit', 'Stop-TrackedProcessIds', 'Assert-TrackedProcessIdsGone', 'Start-RedirectedProcess')) {
		Import-WrapperFunction $name
	}
	$script:PollMilliseconds = 10
	$script:CleanupTimeoutSeconds = 30
	$stdout = Join-Path $WorkingDirectory 'fast-runner.stdout.log'
	$stderr = Join-Path $WorkingDirectory 'fast-runner.stderr.log'
	$handle = Start-RedirectedProcess powershell.exe '-NoProfile -Command "Start-Sleep -Milliseconds 150"' $WorkingDirectory $stdout $stderr @{}
	$tracked = [System.Collections.Generic.List[object]]::new()
	try {
		Start-Sleep -Milliseconds 250
		$measurement = Measure-RunnerResourcesUntilExit $handle @($handle) $tracked ([DateTime]::UtcNow.AddSeconds(5))
		if ([int] $measurement.processCount -lt 1) { throw 'Fast-exit runner was not sampled before completion' }
	} finally {
		if (-not $handle.Process.HasExited) { $handle.Process.Kill() }
		$handle.Process.WaitForExit()
		$null = $handle.StdoutTask.Wait(1000)
		$null = $handle.StderrTask.Wait(1000)
		$handle.Process.Dispose()
	}

	# The launched root can exit before the first CIM sample while a child
	# remains alive. The captured root identity must still seed the tree walk.
	$childScript = Join-Path $WorkingDirectory 'fast-child.ps1'
	$rootScript = Join-Path $WorkingDirectory 'fast-root.ps1'
	Set-Content -LiteralPath $childScript -Value 'Start-Sleep -Seconds 30' -NoNewline
	Set-Content -LiteralPath $rootScript -Value "Start-Process powershell -WindowStyle Hidden -ArgumentList '-NoProfile','-File','$childScript'; Start-Sleep -Milliseconds 100" -NoNewline
	$treeHandle = Start-RedirectedProcess powershell.exe ("-NoProfile -File `"$rootScript`"") $WorkingDirectory (Join-Path $WorkingDirectory 'fast-tree.stdout.log') (Join-Path $WorkingDirectory 'fast-tree.stderr.log') @{}
	$treeTracked = [System.Collections.Generic.List[object]]::new()
	try {
		$treeHandle.Process.WaitForExit(2000) | Out-Null
		$treeMeasurement = Measure-RunnerResourcesUntilExit $treeHandle @($treeHandle) $treeTracked ([DateTime]::UtcNow.AddSeconds(5))
		if ([int] $treeMeasurement.processCount -lt 1) { throw 'Fast-exit root descendant was not sampled after its root exited' }
		Stop-TrackedProcessIds $treeTracked
		Assert-TrackedProcessIdsGone $treeTracked
	} catch {
		Write-Output "FAST_TREE_FAILURE: $($_.Exception.Message) tracked=$($treeTracked.Count)"
		throw
	} finally {
		foreach ($child in @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object { [string] $_.CommandLine -like "*$childScript*" })) {
			Stop-Process -Id ([int] $child.ProcessId) -Force -ErrorAction SilentlyContinue
		}
		if (-not $treeHandle.Process.HasExited) { $treeHandle.Process.Kill() }
		$treeHandle.Process.WaitForExit()
		$null = $treeHandle.StdoutTask.Wait(1000)
		$null = $treeHandle.StderrTask.Wait(1000)
		$treeHandle.Process.Dispose()
	}
}

$project = Join-Path ([IO.Path]::GetTempPath()) "arena-headless-wrapper-test-$([Guid]::NewGuid().ToString('N'))"
$originalAppData = [Environment]::GetEnvironmentVariable('APPDATA')
$originalLocalAppData = [Environment]::GetEnvironmentVariable('LOCALAPPDATA')
New-Item -ItemType Directory -Path $project -Force | Out-Null
try {
	Set-TestEnvironment 'ARENA_HEADLESS_JAVA' (Join-Path $project 'not-java.exe')
	Assert-Fails { & $scriptPath -ProjectRoot $project } 'Java 25|prerequisite|missing'
	Set-TestEnvironment 'ARENA_HEADLESS_JAVA' $null

	$missingTemplateProject = Join-Path $project 'missing-template'
	New-Item -ItemType Directory -Path $missingTemplateProject -Force | Out-Null
	Assert-Fails { & $scriptPath -ProjectRoot $missingTemplateProject -ServerTemplate (Join-Path $missingTemplateProject 'no-server') } 'template|server'

	$fixture = Join-Path $project 'fixture'
	New-Fixture $fixture
	Test-FastExitResourceSampling $fixture
	Write-Output 'PASS PowerShell wrapper samples fast-exit roots and surviving descendants'
	Set-TestEnvironment 'APPDATA' (Join-Path $fixture 'fake-appdata')
	Set-TestEnvironment 'LOCALAPPDATA' (Join-Path $fixture 'fake-localappdata')
	Set-TestEnvironment 'ARENA_HEADLESS_SKIP_PROVIDER_PREFLIGHT' '1'
	Set-TestEnvironment 'ARENA_HEADLESS_MINECRAFT_PORT' '39165'
	Set-TestEnvironment 'ARENA_HEADLESS_RCON_PORT' '39166'
	Set-TestEnvironment 'ARENA_HEADLESS_BRIDGE_PORT' '39167'
	Set-TestEnvironment 'ARENA_HEADLESS_STARTUP_TIMEOUT_SECONDS' '1'
	Set-TestEnvironment 'ARENA_HEADLESS_CLEANUP_TIMEOUT_SECONDS' '1'
	Set-TestEnvironment 'ARENA_HEADLESS_RUNNER_GRACE_SECONDS' '2'
	Set-TestEnvironment 'ARENA_HEADLESS_GRACEFUL_STOP_TIMEOUT_SECONDS' '1'
	Set-TestEnvironment 'ARENA_HEADLESS_OUTPUT_DRAIN_TIMEOUT_MILLISECONDS' '100'
	$listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 39165)
	$listener.Start()
	try {
		Assert-Fails { & $scriptPath -ProjectRoot $fixture -MatrixPath (Join-Path $fixture 'matrix.json') -ServerTemplate (Join-Path $fixture 'runtime\server-template') } 'occupied|already|port'
	} finally {
		$listener.Stop()
	}
	Write-Output 'PASS occupied-port validation starts no provider process'

	Set-TestEnvironment 'ARENA_HEADLESS_RCON_PORT' '39165'
	Assert-Fails { & $scriptPath -ProjectRoot $fixture -MatrixPath (Join-Path $fixture 'matrix.json') -ServerTemplate (Join-Path $fixture 'runtime\server-template') } 'distinct|duplicate|port'
	Set-TestEnvironment 'ARENA_HEADLESS_RCON_PORT' '39166'
	Write-Output 'PASS duplicate configured ports are rejected'
	$unsafeMatrix = Join-Path $fixture 'unsafe-matrix.json'
	Set-Content -LiteralPath $unsafeMatrix -Value '{"version":1,"scenarios":[{"id":"../escape","provider":"codex","model":"fixture","reasoningEffort":"low","serviceTier":"fast","task":"fixture","timeoutMs":1000,"assert":[{"type":"lifecycle","state":"COMPLETED"}]}]}' -NoNewline
	Assert-Fails { & $scriptPath -ProjectRoot $fixture -MatrixPath $unsafeMatrix -ServerTemplate (Join-Path $fixture 'runtime\server-template') } 'safe|separator|scenario ID|control'
	foreach ($invalidCharacter in @(':', '*', '?', '<', '>', '|')) {
		$invalidIdMatrix = Join-Path $fixture "invalid-id-$([int][char]$invalidCharacter).json"
		$invalidId = "fixture${invalidCharacter}name"
		$invalidScenario = [pscustomobject]@{ id = $invalidId; provider = 'codex'; model = 'fixture'; reasoningEffort = 'low'; serviceTier = 'fast'; task = 'fixture'; timeoutMs = 1000; assert = @([pscustomobject]@{ type = 'lifecycle'; state = 'COMPLETED' }) }
		Set-Content -LiteralPath $invalidIdMatrix -Value ([pscustomobject]@{ version = 1; scenarios = @($invalidScenario) } | ConvertTo-Json -Depth 8) -NoNewline
		Assert-Fails { & $scriptPath -ProjectRoot $fixture -MatrixPath $invalidIdMatrix -ServerTemplate (Join-Path $fixture 'runtime\server-template') } 'safe|separator|scenario ID|control'
	}
	Write-Output 'PASS Windows-invalid scenario ID characters are rejected before setup'
	$largeMatrix = Join-Path $fixture 'large-matrix.json'
	$largeScenarios = @(1..25 | ForEach-Object { [pscustomobject]@{ id = "fixture-$_"; provider = 'codex'; model = 'fixture'; reasoningEffort = 'low'; serviceTier = 'fast'; task = 'fixture'; timeoutMs = 1000; assert = @([pscustomobject]@{ type = 'lifecycle'; state = 'COMPLETED' }) } })
	Set-Content -LiteralPath $largeMatrix -Value ([pscustomobject]@{ version = 1; scenarios = $largeScenarios } | ConvertTo-Json -Depth 8) -NoNewline
	Assert-Fails { & $scriptPath -ProjectRoot $fixture -MatrixPath $largeMatrix -ServerTemplate (Join-Path $fixture 'runtime\server-template') } 'Selected scenario count|bounded maximum|maximum'
	Write-Output 'PASS selected scenario count is bounded'

	if ($SetupFailureOnly) {
		$sourceConfigPath = Join-Path $fixture 'coordinator\config\dynamic-agents.json'
		$validSourceConfig = Get-Content -Raw -LiteralPath $sourceConfigPath
		Set-Content -LiteralPath $sourceConfigPath -Value '{ malformed coordinator config' -NoNewline
		try {
			Assert-Fails { & $scriptPath -ProjectRoot $fixture -MatrixPath (Join-Path $fixture 'matrix.json') -ServerTemplate (Join-Path $fixture 'runtime\server-template') } 'failed|required|JSON|parse|Unexpected'
			$setupFailureMatrixReport = Get-ChildItem -LiteralPath (Join-Path $fixture 'runtime\headless-runs') -Recurse -Filter matrix-report.json | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
			if ($null -eq $setupFailureMatrixReport) { throw 'Setup failure did not write a matrix report' }
			$setupFailureMatrix = Get-Content -Raw -LiteralPath $setupFailureMatrixReport.FullName | ConvertFrom-Json
			if ($setupFailureMatrix.status -ne 'FAILED' -or @($setupFailureMatrix.scenarios).Count -ne 1 -or $setupFailureMatrix.scenarios[0].status -ne 'FAILED') { throw 'Setup failure matrix report did not record a failed scenario' }
			$setupFailureScenarioReport = Get-ChildItem -LiteralPath (Split-Path -Parent $setupFailureMatrixReport.FullName) -Recurse -Filter report.json -ErrorAction SilentlyContinue | Select-Object -First 1
			if ($null -eq $setupFailureScenarioReport) { throw 'Setup failure did not write a scenario report' }
			$setupFailureScenarioDirectory = Split-Path -Parent $setupFailureScenarioReport.FullName
			foreach ($leakedArtifact in @('rcon-password.txt', 'server', 'provider-workspaces')) {
				if (Test-Path -LiteralPath (Join-Path $setupFailureScenarioDirectory $leakedArtifact)) { throw "Setup failure retained $leakedArtifact" }
			}
			$setupReportText = Get-Content -Raw -LiteralPath $setupFailureScenarioReport.FullName
			if ($setupReportText -match 'malformed coordinator config') { throw 'Setup failure report retained raw configuration content' }
			Write-Output 'PASS setup failure cleanup and bounded reports'
		} finally {
			Set-Content -LiteralPath $sourceConfigPath -Value $validSourceConfig -NoNewline
		}
		return
	}

	Set-TestEnvironment 'ARENA_HEADLESS_STARTUP_TIMEOUT_SECONDS' '5'
	Enable-FakeServer $fixture
	Set-TestEnvironment 'ARENA_HEADLESS_FAKE_NO_HELLO_ACK' '1'
	Assert-Fails { & $scriptPath -ProjectRoot $fixture -MatrixPath (Join-Path $fixture 'matrix.json') -ServerTemplate (Join-Path $fixture 'runtime\server-template') } 'Coordinator (?:bridge did not become ready|exited before bridge readiness)'
	Set-TestEnvironment 'ARENA_HEADLESS_FAKE_NO_HELLO_ACK' $null
	Write-Output 'PASS open bridge port without authenticated handshake is not ready'
	$catalogMatrix = Join-Path $fixture 'catalog-matrix.json'
	Set-Content -LiteralPath $catalogMatrix -Value '{"version":1,"scenarios":[{"id":"fixture","provider":"codex","model":"fixture","reasoningEffort":"low","serviceTier":"fast","task":"fixture","timeoutMs":1000,"assert":[{"type":"lifecycle","state":"COMPLETED"}]}]}' -NoNewline
	Set-Content -LiteralPath (Join-Path $fixture 'fake-appdata\no-catalog') -Value 'hold model/list' -NoNewline
	try {
		Assert-Fails { & $scriptPath -ProjectRoot $fixture -MatrixPath $catalogMatrix -ServerTemplate (Join-Path $fixture 'runtime\server-template') } 'Coordinator (?:bridge did not become ready|exited before bridge readiness)'
	} finally {
		Remove-Item -LiteralPath (Join-Path $fixture 'fake-appdata\no-catalog') -Force -ErrorAction SilentlyContinue
	}
	Write-Output 'PASS empty-roster startup waits for advertised provider settings before launching a task'
	Assert-Fails { & $scriptPath -ProjectRoot $fixture -MatrixPath (Join-Path $fixture 'matrix.json') -ServerTemplate (Join-Path $fixture 'runtime\server-template') } 'ready|timed out|failed|required'
	if (-not (Test-PortClosed 39165) -or -not (Test-PortClosed 39166) -or -not (Test-PortClosed 39167)) { throw 'Allocated ports remained open after timeout cleanup' }
	$runRoot = Join-Path $fixture 'runtime\headless-runs'
	$reports = @(Get-ChildItem -LiteralPath $runRoot -Recurse -Filter report.json -ErrorAction SilentlyContinue)
	if ($reports.Count -lt 1) { throw 'Timeout cleanup did not write a scenario report' }
	if (@(Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -match 'FakeServer|Start-Sleep -Seconds 3' }).Count -gt 0) { throw 'Wrapper cleanup left dummy server descendants running' }
	$latestMatrixReport = Get-ChildItem -LiteralPath $runRoot -Recurse -Filter matrix-report.json | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
	$matrixObject = Get-Content -Raw -LiteralPath $latestMatrixReport.FullName | ConvertFrom-Json
	if ($matrixObject.status -ne 'FAILED' -or @($matrixObject.scenarios).Count -ne 1 -or $matrixObject.scenarios[0].status -ne 'FAILED') { throw 'matrix-report.json did not forward the failed runner status' }
	$latestManifest = Get-ChildItem -LiteralPath $runRoot -Recurse -Filter matrix-manifest.json | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
	$manifestText = Get-Content -Raw -LiteralPath $latestManifest.FullName
	if ($manifestText -match '"(task|message|args|state)"\s*:') { throw 'matrix-manifest.json retained unbounded task or assertion payload' }
	$manifestObject = $manifestText | ConvertFrom-Json
	if ($manifestObject.scenarioCount -ne 1 -or @($manifestObject.scenarios).Count -ne 1) { throw 'matrix-manifest.json was not a bounded scenario summary' }
	$latestScenarioReport = Get-ChildItem -LiteralPath $runRoot -Recurse -Filter report.json | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
	$defaultScenarioDirectory = Split-Path -Parent $latestScenarioReport.FullName
	if (Test-Path -LiteralPath (Join-Path $defaultScenarioDirectory 'rcon-password.txt')) { throw 'Default cleanup retained the RCON credential' }
	if (Test-Path -LiteralPath (Join-Path $defaultScenarioDirectory 'server')) { throw 'Default cleanup retained the copied server' }
	if (Test-Path -LiteralPath (Join-Path $defaultScenarioDirectory 'provider-workspaces')) { throw 'Default cleanup retained provider workspaces' }
	if (Test-Path -LiteralPath (Join-Path $defaultScenarioDirectory 'coordinator-private.jsonl')) { throw 'Default cleanup retained the private coordinator trace' }

	# Exercise a successful normal path with the same fake server and verify the
	# wrapper's graceful-stop snapshot also removes the server's child helper.
	Set-TestEnvironment 'ARENA_HEADLESS_STARTUP_TIMEOUT_SECONDS' '15'
	$successMatrix = Join-Path $fixture 'success-matrix.json'
	Set-Content -LiteralPath $successMatrix -Value '{"version":1,"scenarios":[{"id":"fixture","provider":"codex","model":"fixture","reasoningEffort":"low","task":"fixture","timeoutMs":1000,"assert":[{"type":"lifecycle","state":"COMPLETED"}]}]}' -NoNewline
	Set-TestEnvironment 'ARENA_HEADLESS_MINECRAFT_PORT' '39168'
	Set-TestEnvironment 'ARENA_HEADLESS_RCON_PORT' '39169'
	Set-TestEnvironment 'ARENA_HEADLESS_BRIDGE_PORT' '39170'
	& $scriptPath -ProjectRoot $fixture -MatrixPath $successMatrix -ServerTemplate (Join-Path $fixture 'runtime\server-template') -KeepArtifacts:$KeepFixture | Out-Null
	$successMatrixReportPath = Join-Path (Get-ChildItem -LiteralPath $runRoot -Directory | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1).FullName 'matrix-report.json'
	$successMatrixReport = Get-Content -Raw -LiteralPath $successMatrixReportPath | ConvertFrom-Json
	if ($successMatrixReport.status -ne 'PASSED' -or $successMatrixReport.scenarios[0].status -ne 'PASSED' -or $successMatrixReport.scenarios[0].cleanup.status -ne 'CLEAN') { throw 'Successful normal-cleanup fixture did not pass cleanly' }
	if ($successMatrixReport.scenarios[0].profile.serviceTier -ne 'priority') { throw 'Omitted serviceTier did not default to priority through the wrapper' }
	if (@(Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -match 'FakeServer|Start-Sleep -Seconds 3' }).Count -gt 0) { throw 'Successful normal cleanup left dummy server descendants running' }
	Write-Output 'PASS successful normal cleanup and descendant verification'

	$repetitionMatrix = Join-Path $fixture 'repetition-matrix.json'
	Set-Content -LiteralPath $repetitionMatrix -Value '{"version":1,"scenarios":[{"id":"fixture","provider":"codex","model":"fixture","reasoningEffort":"low","task":"fixture","timeoutMs":1000,"repetitions":2,"assert":[{"type":"lifecycle","state":"COMPLETED"}]}]}' -NoNewline
	Set-TestEnvironment 'ARENA_HEADLESS_MINECRAFT_PORT' '39174'
	Set-TestEnvironment 'ARENA_HEADLESS_RCON_PORT' '39175'
	Set-TestEnvironment 'ARENA_HEADLESS_BRIDGE_PORT' '39176'
	& $scriptPath -ProjectRoot $fixture -MatrixPath $repetitionMatrix -ServerTemplate (Join-Path $fixture 'runtime\server-template') | Out-Null
	$repetitionReportPath = Join-Path (Get-ChildItem -LiteralPath $runRoot -Directory | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1).FullName 'matrix-report.json'
	$repetitionReport = Get-Content -Raw -LiteralPath $repetitionReportPath | ConvertFrom-Json
	$innerRepetitions = @($repetitionReport.scenarios[0].scenarios)
	if ($repetitionReport.status -ne 'PASSED' -or $repetitionReport.scenarios[0].status -ne 'PASSED' -or $innerRepetitions.Count -ne 2 -or @($innerRepetitions | Where-Object { $_.status -ne 'PASSED' }).Count -gt 0) { throw 'Repeated scenario reports were not aggregated correctly' }
	Write-Output 'PASS repeated scenario report aggregation'

	$skipMatrix = Join-Path $fixture 'skip-matrix.json'
	Set-Content -LiteralPath $skipMatrix -Value '{"version":1,"scenarios":[{"id":"fixture","provider":"codex","model":"missing","reasoningEffort":"low","task":"fixture","timeoutMs":1000,"assert":[{"type":"lifecycle","state":"COMPLETED"}]}]}' -NoNewline
	Set-TestEnvironment 'ARENA_HEADLESS_FAKE_SUMMON_RESPONSE' 'Coordinator catalog rejected codex/missing/low (provider profiles: 0)'
	Set-TestEnvironment 'ARENA_HEADLESS_MINECRAFT_PORT' '39171'
	Set-TestEnvironment 'ARENA_HEADLESS_RCON_PORT' '39172'
	Set-TestEnvironment 'ARENA_HEADLESS_BRIDGE_PORT' '39173'
	try {
		& $scriptPath -ProjectRoot $fixture -MatrixPath $skipMatrix -ServerTemplate (Join-Path $fixture 'runtime\server-template') | Out-Null
	} finally {
		Set-TestEnvironment 'ARENA_HEADLESS_FAKE_SUMMON_RESPONSE' $null
	}
	$skipReportPath = Join-Path (Get-ChildItem -LiteralPath $runRoot -Directory | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1).FullName 'matrix-report.json'
	$skipReport = Get-Content -Raw -LiteralPath $skipReportPath | ConvertFrom-Json
	if ($skipReport.status -ne 'SKIPPED' -or $skipReport.scenarios[0].status -ne 'SKIPPED' -or $skipReport.scenarios[0].classification -ne 'SKIPPED_PROFILE') { throw 'Wrapper did not preserve the runner SKIPPED classification' }
	Write-Output 'PASS optional catalog rejection remains SKIPPED through wrapper reporting'

	Set-TestEnvironment 'ARENA_HEADLESS_MINECRAFT_PORT' '39168'
	Set-TestEnvironment 'ARENA_HEADLESS_RCON_PORT' '39169'
	Set-TestEnvironment 'ARENA_HEADLESS_BRIDGE_PORT' '39170'
	Assert-Fails { & $scriptPath -ProjectRoot $fixture -MatrixPath (Join-Path $fixture 'matrix.json') -ServerTemplate (Join-Path $fixture 'runtime\server-template') -KeepArtifacts } 'ready|timed out|failed|required'
	$keptRunDirectory = Get-ChildItem -LiteralPath $runRoot -Directory | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
	$keptMatrixReport = Get-Item -LiteralPath (Join-Path $keptRunDirectory.FullName 'matrix-report.json')
	$keptScenarioReport = Get-ChildItem -LiteralPath $keptRunDirectory.FullName -Directory | ForEach-Object { Get-Item -LiteralPath (Join-Path $_.FullName 'report.json') -ErrorAction SilentlyContinue } | Select-Object -First 1
	$keptScenarioDirectory = Split-Path -Parent $keptScenarioReport.FullName
	if (-not (Test-Path -LiteralPath (Join-Path $keptScenarioDirectory 'rcon-password.txt'))) { throw 'KeepArtifacts did not retain the RCON credential artifact' }
	if (-not (Test-Path -LiteralPath (Join-Path $keptScenarioDirectory 'coordinator.jsonl'))) { throw 'KeepArtifacts did not retain the coordinator evidence trace at the runner path' }
	if (-not (Test-Path -LiteralPath (Join-Path $keptScenarioDirectory 'coordinator-private.jsonl'))) { throw 'KeepArtifacts did not retain the derived private coordinator trace' }
	$keptSecret = (Get-Content -Raw -LiteralPath (Join-Path $keptScenarioDirectory 'rcon-password.txt')).Trim()
	$keptAudit = Get-Content -Raw -LiteralPath (Join-Path $keptScenarioDirectory 'protocol.jsonl')
	if ($keptAudit.Contains($keptSecret)) { throw 'Protocol audit retained the bridge/RCON secret' }
	$properties = Get-Content -Raw -LiteralPath (Join-Path $keptScenarioDirectory 'server\server.properties')
	if ($properties -notmatch '(?m)^rcon\.ip=127\.0\.0\.1\r?$') { throw "RCON loopback binding was not configured: $properties" }
	if ($properties -notmatch '(?m)^server-ip=127\.0\.0\.1\r?$') { throw "Gameplay loopback binding was not configured: $properties" }
	if ($properties -match '(?m)^server(?:\\)?-ip[:=]0\.0\.0\.0\r?$') { throw "Unsafe template gameplay binding survived: $properties" }
	if ([regex]::Matches($properties, '(?m)^server-ip=127\.0\.0\.1\r?$').Count -ne 1) { throw "Gameplay loopback binding was not canonicalized exactly once: $properties" }
	Write-Output 'PASS matrix report forwarding, cleanup retention, KeepArtifacts, and loopback listeners'
	foreach ($runDirectory in @(Get-ChildItem -LiteralPath $runRoot -Directory)) {
		foreach ($scenarioDirectory in @(Get-ChildItem -LiteralPath $runDirectory.FullName -Directory)) {
			if (Test-Path -LiteralPath (Join-Path $scenarioDirectory.FullName 'server\world')) { throw 'Server template world was copied into a scenario' }
		}
	}

	$dummyScript = Join-Path $project 'dummy-child-tree.ps1'
	Set-Content -LiteralPath $dummyScript -Value "Start-Process powershell -WindowStyle Hidden -ArgumentList '-NoProfile','-Command','Start-Sleep -Seconds 30'`nStart-Sleep -Seconds 30" -NoNewline
	$dummyRoot = Start-Process powershell -WindowStyle Hidden -ArgumentList '-NoProfile','-File',$dummyScript -PassThru
	try {
		Start-Sleep -Milliseconds 500
		Stop-TestProcessTree $dummyRoot.Id
		Start-Sleep -Milliseconds 250
		if (-not $dummyRoot.HasExited) { throw 'Dummy child-tree root survived cleanup' }
	} finally {
		Stop-TestProcessTree $dummyRoot.Id
	}

	$wrapperText = Get-Content -Raw -LiteralPath $scriptPath
	foreach ($requiredPattern in @('Stop-ProcessTree', 'Add-ProcessTreeSnapshot', 'Test-ProcessIdentityMatch', 'Get-TrackedResourceSnapshot', 'CreationDate', 'ParentProcessId', 'Get-CimInstance Win32_Process', 'Wait-Condition', 'Test-Port', 'ARENA_AGENT_BRIDGE_SECRET', 'provider-workspaces', 'cleanupFailure', 'artifactsKept', 'rcon.ip', 'server-ip')) {
		if ($wrapperText -notmatch [regex]::Escape($requiredPattern)) { throw "Lifecycle wrapper missing cleanup/isolation hook '$requiredPattern'" }
	}
	Write-Output 'PASS timeout cleanup, port verification, child-tree cleanup, and provider isolation hooks'
} finally {
	foreach ($name in @('ARENA_HEADLESS_JAVA','ARENA_HEADLESS_SKIP_PROVIDER_PREFLIGHT','ARENA_HEADLESS_MINECRAFT_PORT','ARENA_HEADLESS_RCON_PORT','ARENA_HEADLESS_BRIDGE_PORT','ARENA_HEADLESS_FAKE_NO_HELLO_ACK','ARENA_HEADLESS_FAKE_SUMMON_RESPONSE','ARENA_HEADLESS_STARTUP_TIMEOUT_SECONDS','ARENA_HEADLESS_CLEANUP_TIMEOUT_SECONDS','ARENA_HEADLESS_RUNNER_GRACE_SECONDS','ARENA_HEADLESS_GRACEFUL_STOP_TIMEOUT_SECONDS','ARENA_HEADLESS_OUTPUT_DRAIN_TIMEOUT_MILLISECONDS')) { Set-TestEnvironment $name $null }
	Set-TestEnvironment 'APPDATA' $originalAppData
	Set-TestEnvironment 'LOCALAPPDATA' $originalLocalAppData
	if ($KeepFixture) {
		Write-Output "Kept diagnostic fixture: $project"
	} elseif (Test-Path -LiteralPath $project) {
		$extendedProject = if ($project.StartsWith('\\')) { '\\?\UNC\' + $project.Substring(2) } else { '\\?\' + [IO.Path]::GetFullPath($project) }
		[IO.Directory]::Delete($extendedProject, $true)
	}
}
