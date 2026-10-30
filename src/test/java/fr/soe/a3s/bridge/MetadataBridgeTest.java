package fr.soe.a3s.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import fr.soe.a3s.dao.A3SFilesAccessor;
import fr.soe.a3s.domain.repository.AutoConfig;
import fr.soe.a3s.domain.repository.ServerInfo;
import fr.soe.a3s.domain.repository.SyncTreeDirectory;

class MetadataBridgeTest {

	@TempDir
	Path tempDir;

	@Test
	void readsLocalAutoconfigAsJson() throws Exception {
		Path fixtures = fixtureRoot();
		ByteArrayOutputStream stdout = new ByteArrayOutputStream();
		ByteArrayOutputStream stderr = new ByteArrayOutputStream();
		int code = new MetadataBridgeMain(new PrintStream(stdout, true, StandardCharsets.UTF_8),
				new PrintStream(stderr, true, StandardCharsets.UTF_8))
				.run(new String[] {
						"read",
						"--source", fixtures.toString(),
						"--kind", "autoconfig",
						"--out", "-"
				});

		assertEquals(0, code, stderr.toString());
		JsonObject root = JsonParser.parseString(stdout.toString(StandardCharsets.UTF_8)).getAsJsonObject();
		assertEquals(1, root.get("bridgeVersion").getAsInt());
		assertEquals("autoconfig", root.get("kind").getAsString());
		assertTrue(root.getAsJsonObject("autoconfig").has("repositoryName"));
		assertTrue(root.getAsJsonObject("autoconfig").has("protocol"));
	}

	@Test
	void readsLocalServerinfoAndSync() throws Exception {
		Path fixtures = fixtureRoot();
		MetadataSource source = MetadataSource.fromLocalPath(fixtures);
		MetadataBlobIO io = new MetadataBlobIO();
		ServerInfo serverInfo = (ServerInfo) io.readFromSource(source, MetadataKind.SERVERINFO);
		SyncTreeDirectory sync = (SyncTreeDirectory) io.readFromSource(source, MetadataKind.SYNC);

		assertTrue(serverInfo.getRevision() > 0);
		assertNotNull(sync.getName());
		assertFalse(sync.getList().isEmpty());
	}

	@Test
	void roundTripsServerinfoThroughJsonWrite() throws Exception {
		Path fixtures = fixtureRoot();
		MetadataSource source = MetadataSource.fromLocalPath(fixtures);
		MetadataBlobIO io = new MetadataBlobIO();
		MetadataJsonCodec codec = new MetadataJsonCodec();

		ServerInfo original = (ServerInfo) io.readFromSource(source, MetadataKind.SERVERINFO);
		String json = codec.toJson(MetadataKind.SERVERINFO, original);

		Path target = tempDir.resolve("repo");
		Files.createDirectories(target);
		ByteArrayOutputStream stdout = new ByteArrayOutputStream();
		ByteArrayOutputStream stderr = new ByteArrayOutputStream();
		Path jsonFile = tempDir.resolve("serverinfo.json");
		Files.writeString(jsonFile, json, StandardCharsets.UTF_8);

		int code = new MetadataBridgeMain(new PrintStream(stdout, true, StandardCharsets.UTF_8),
				new PrintStream(stderr, true, StandardCharsets.UTF_8))
				.run(new String[] {
						"write",
						"--target", target.toString(),
						"--kind", "serverinfo",
						"--in", jsonFile.toString()
				});
		assertEquals(0, code, stderr.toString());

		Path written = target.resolve(".a3s").resolve("serverinfo");
		assertTrue(Files.isRegularFile(written));
		ServerInfo restored = (ServerInfo) A3SFilesAccessor.read(written.toFile());
		assertEquals(original.getRevision(), restored.getRevision());
		assertEquals(original.getNumberOfFiles(), restored.getNumberOfFiles());
		assertEquals(original.getTotalFilesSize(), restored.getTotalFilesSize());
		assertEquals(original.isCompressedPboFilesOnly(), restored.isCompressedPboFilesOnly());
	}

	@Test
	void copiesAutoconfigPreservingDomainFields() throws Exception {
		Path fixtures = fixtureRoot();
		Path target = tempDir.resolve("mirror");
		ByteArrayOutputStream stdout = new ByteArrayOutputStream();
		ByteArrayOutputStream stderr = new ByteArrayOutputStream();

		int code = new MetadataBridgeMain(new PrintStream(stdout, true, StandardCharsets.UTF_8),
				new PrintStream(stderr, true, StandardCharsets.UTF_8))
				.run(new String[] {
						"copy",
						"--from", fixtures.toString(),
						"--to", target.toString(),
						"--kind", "autoconfig"
				});
		assertEquals(0, code, stderr.toString());

		AutoConfig original = (AutoConfig) A3SFilesAccessor
				.read(fixtures.resolve(".a3s").resolve("autoconfig").toFile());
		AutoConfig copied = (AutoConfig) A3SFilesAccessor
				.read(target.resolve(".a3s").resolve("autoconfig").toFile());
		assertEquals(original.getRepositoryName(), copied.getRepositoryName());
		assertEquals(original.getProtocole().getProtocolType(), copied.getProtocole().getProtocolType());
		assertEquals(original.getProtocole().getUrl(), copied.getProtocole().getUrl());
	}

	@Test
	void usageErrorReturnsExitCodeOne() {
		ByteArrayOutputStream stdout = new ByteArrayOutputStream();
		ByteArrayOutputStream stderr = new ByteArrayOutputStream();
		int code = new MetadataBridgeMain(new PrintStream(stdout, true, StandardCharsets.UTF_8),
				new PrintStream(stderr, true, StandardCharsets.UTF_8))
				.run(new String[] { "read" });
		assertEquals(BridgeExitCodes.USAGE, code);
	}

	@Test
	void missingKindReturnsIoExitCode() throws Exception {
		Path emptyRoot = tempDir.resolve("empty-repo");
		Files.createDirectories(emptyRoot.resolve(".a3s"));
		ByteArrayOutputStream stdout = new ByteArrayOutputStream();
		ByteArrayOutputStream stderr = new ByteArrayOutputStream();
		int code = new MetadataBridgeMain(new PrintStream(stdout, true, StandardCharsets.UTF_8),
				new PrintStream(stderr, true, StandardCharsets.UTF_8))
				.run(new String[] {
						"read",
						"--source", emptyRoot.toString(),
						"--kind", "sync"
				});
		assertEquals(BridgeExitCodes.IO_ERROR, code);
	}

	@Test
	void normalizesRemoteAutoconfigUrl() throws Exception {
		String base = MetadataSource.normalizeRemoteA3sBase(
				"https://arma.game.reichel.network/.a3s/autoconfig");
		assertEquals("https://arma.game.reichel.network/.a3s/", base);
	}

	private Path fixtureRoot() throws Exception {
		URL resource = getClass().getResource("/a3s-fixtures/.a3s/autoconfig");
		assertNotNull(resource, "Fixture autoconfig missing from test resources");
		Path autoconfig = Paths.get(resource.toURI());
		return autoconfig.getParent().getParent();
	}
}
