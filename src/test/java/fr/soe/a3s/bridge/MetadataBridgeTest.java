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
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import fr.soe.a3s.dao.A3SFilesAccessor;
import fr.soe.a3s.domain.repository.AutoConfig;
import fr.soe.a3s.domain.repository.Changelog;
import fr.soe.a3s.domain.repository.Changelogs;
import fr.soe.a3s.domain.repository.Event;
import fr.soe.a3s.domain.repository.Events;
import fr.soe.a3s.domain.repository.ServerInfo;
import fr.soe.a3s.domain.repository.SyncTreeDirectory;
import fr.soe.a3s.domain.repository.SyncTreeLeaf;
import fr.soe.a3s.domain.repository.SyncTreeNode;

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

	@ParameterizedTest
	@EnumSource(MetadataKind.class)
	void roundTripsEachKindThroughJsonWrite(MetadataKind kind) throws Exception {
		Path fixtures = fixtureRoot();
		MetadataSource source = MetadataSource.fromLocalPath(fixtures);
		MetadataBlobIO io = new MetadataBlobIO();
		MetadataJsonCodec codec = new MetadataJsonCodec();

		Object original = io.readFromSource(source, kind);
		String json = codec.toJson(kind, original);

		Path target = tempDir.resolve("repo-" + kind.getFileName());
		Files.createDirectories(target);
		Path jsonFile = tempDir.resolve(kind.getFileName() + ".json");
		Files.writeString(jsonFile, json, StandardCharsets.UTF_8);

		ByteArrayOutputStream stdout = new ByteArrayOutputStream();
		ByteArrayOutputStream stderr = new ByteArrayOutputStream();
		int code = new MetadataBridgeMain(new PrintStream(stdout, true, StandardCharsets.UTF_8),
				new PrintStream(stderr, true, StandardCharsets.UTF_8))
				.run(new String[] {
						"write",
						"--target", target.toString(),
						"--kind", kind.getFileName(),
						"--in", jsonFile.toString()
				});
		assertEquals(0, code, stderr.toString());

		Path written = target.resolve(".a3s").resolve(kind.getFileName());
		assertTrue(Files.isRegularFile(written));
		Object restored = A3SFilesAccessor.read(written.toFile());
		assertDomainEqual(kind, original, restored);
	}

	@Test
	void writesAllKindsFromFilesEnvelope() throws Exception {
		Path fixtures = fixtureRoot();
		MetadataSource source = MetadataSource.fromLocalPath(fixtures);
		MetadataBlobIO io = new MetadataBlobIO();
		MetadataJsonCodec codec = new MetadataJsonCodec();

		java.util.EnumMap<MetadataKind, Object> files = new java.util.EnumMap<>(MetadataKind.class);
		for (MetadataKind kind : MetadataKind.values()) {
			files.put(kind, io.readFromSource(source, kind));
		}
		String json = codec.toJsonAll(files);

		Path target = tempDir.resolve("multi");
		Path jsonFile = tempDir.resolve("all.json");
		Files.writeString(jsonFile, json, StandardCharsets.UTF_8);

		ByteArrayOutputStream stdout = new ByteArrayOutputStream();
		ByteArrayOutputStream stderr = new ByteArrayOutputStream();
		int code = new MetadataBridgeMain(new PrintStream(stdout, true, StandardCharsets.UTF_8),
				new PrintStream(stderr, true, StandardCharsets.UTF_8))
				.run(new String[] {
						"write",
						"--target", target.toString(),
						"--kind", "all",
						"--in", jsonFile.toString()
				});
		assertEquals(0, code, stderr.toString());

		for (MetadataKind kind : MetadataKind.values()) {
			Path written = target.resolve(".a3s").resolve(kind.getFileName());
			assertTrue(Files.isRegularFile(written), kind.getFileName());
			assertDomainEqual(kind, files.get(kind), A3SFilesAccessor.read(written.toFile()));
		}
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
	void copiesAllKindsFromFixtures() throws Exception {
		Path fixtures = fixtureRoot();
		Path target = tempDir.resolve("mirror-all");
		ByteArrayOutputStream stdout = new ByteArrayOutputStream();
		ByteArrayOutputStream stderr = new ByteArrayOutputStream();

		int code = new MetadataBridgeMain(new PrintStream(stdout, true, StandardCharsets.UTF_8),
				new PrintStream(stderr, true, StandardCharsets.UTF_8))
				.run(new String[] {
						"copy",
						"--from", fixtures.toString(),
						"--to", target.toString(),
						"--kind", "all"
				});
		assertEquals(0, code, stderr.toString());
		for (MetadataKind kind : MetadataKind.values()) {
			assertTrue(Files.isRegularFile(target.resolve(".a3s").resolve(kind.getFileName())),
					kind.getFileName());
		}
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
	void missingJsonPayloadReturnsDataExitCode() throws Exception {
		Path target = tempDir.resolve("data-error");
		Files.createDirectories(target);
		Path jsonFile = tempDir.resolve("incomplete.json");
		Files.writeString(jsonFile,
				"{\"bridgeVersion\":1,\"kind\":\"serverinfo\"}",
				StandardCharsets.UTF_8);

		ByteArrayOutputStream stdout = new ByteArrayOutputStream();
		ByteArrayOutputStream stderr = new ByteArrayOutputStream();
		int code = new MetadataBridgeMain(new PrintStream(stdout, true, StandardCharsets.UTF_8),
				new PrintStream(stderr, true, StandardCharsets.UTF_8))
				.run(new String[] {
						"write",
						"--target", target.toString(),
						"--kind", "serverinfo",
						"--in", jsonFile.toString()
				});
		assertEquals(BridgeExitCodes.DATA_ERROR, code, stderr.toString());
	}

	@Test
	void malformedJsonReturnsDataExitCode() throws Exception {
		Path target = tempDir.resolve("malformed");
		Files.createDirectories(target);
		Path jsonFile = tempDir.resolve("broken.json");
		Files.writeString(jsonFile, "not-json", StandardCharsets.UTF_8);

		ByteArrayOutputStream stdout = new ByteArrayOutputStream();
		ByteArrayOutputStream stderr = new ByteArrayOutputStream();
		int code = new MetadataBridgeMain(new PrintStream(stdout, true, StandardCharsets.UTF_8),
				new PrintStream(stderr, true, StandardCharsets.UTF_8))
				.run(new String[] {
						"write",
						"--target", target.toString(),
						"--kind", "serverinfo",
						"--in", jsonFile.toString()
				});
		assertEquals(BridgeExitCodes.DATA_ERROR, code, stderr.toString());
	}

	@Test
	void normalizesRemoteAutoconfigUrl() throws Exception {
		String base = MetadataSource.normalizeRemoteA3sBase(
				"https://arma.game.reichel.network/.a3s/autoconfig");
		assertEquals("https://arma.game.reichel.network/.a3s/", base);
	}

	private void assertDomainEqual(MetadataKind kind, Object original, Object restored) {
		switch (kind) {
			case AUTOCONFIG -> {
				AutoConfig a = (AutoConfig) original;
				AutoConfig b = (AutoConfig) restored;
				assertEquals(a.getRepositoryName(), b.getRepositoryName());
				assertEquals(a.getProtocole().getProtocolType(), b.getProtocole().getProtocolType());
				assertEquals(a.getProtocole().getUrl(), b.getProtocole().getUrl());
				assertEquals(a.getProtocole().getPort(), b.getProtocole().getPort());
				assertEquals(a.getFavoriteServers().size(), b.getFavoriteServers().size());
			}
			case SERVERINFO -> {
				ServerInfo a = (ServerInfo) original;
				ServerInfo b = (ServerInfo) restored;
				assertEquals(a.getRevision(), b.getRevision());
				assertEquals(a.getNumberOfFiles(), b.getNumberOfFiles());
				assertEquals(a.getTotalFilesSize(), b.getTotalFilesSize());
				assertEquals(a.getNumberOfConnections(), b.getNumberOfConnections());
				assertEquals(a.isCompressedPboFilesOnly(), b.isCompressedPboFilesOnly());
				assertEquals(a.isNoPartialFileTransfer(), b.isNoPartialFileTransfer());
				assertEquals(a.isRepositoryContentUpdated(), b.isRepositoryContentUpdated());
				assertEquals(a.getHiddenFolderPaths(), b.getHiddenFolderPaths());
				if (a.getBuildDate() != null) {
					assertEquals(a.getBuildDate().getTime(), b.getBuildDate().getTime());
				}
			}
			case CHANGELOGS -> {
				List<Changelog> a = ((Changelogs) original).getList();
				List<Changelog> b = ((Changelogs) restored).getList();
				assertEquals(a.size(), b.size());
				for (int i = 0; i < a.size(); i++) {
					assertEquals(a.get(i).getRevision(), b.get(i).getRevision());
					assertEquals(a.get(i).isContentUpdated(), b.get(i).isContentUpdated());
					assertEquals(a.get(i).getNewAddons(), b.get(i).getNewAddons());
					assertEquals(a.get(i).getUpdatedAddons(), b.get(i).getUpdatedAddons());
					assertEquals(a.get(i).getDeletedAddons(), b.get(i).getDeletedAddons());
					assertEquals(a.get(i).getAddons(), b.get(i).getAddons());
				}
			}
			case EVENTS -> {
				List<Event> a = ((Events) original).getList();
				List<Event> b = ((Events) restored).getList();
				assertEquals(a.size(), b.size());
				for (int i = 0; i < a.size(); i++) {
					assertEquals(a.get(i).getName(), b.get(i).getName());
					assertEquals(a.get(i).getDescription(), b.get(i).getDescription());
					assertEquals(a.get(i).getAddonNames(), b.get(i).getAddonNames());
					assertEquals(a.get(i).getDlcNames(), b.get(i).getDlcNames());
					assertEquals(a.get(i).getUserconfigFolderNames(), b.get(i).getUserconfigFolderNames());
				}
			}
			case SYNC -> assertSyncEqual((SyncTreeDirectory) original, (SyncTreeDirectory) restored);
		}
	}

	private void assertSyncEqual(SyncTreeDirectory expected, SyncTreeDirectory actual) {
		assertEquals(collectLeaves(expected), collectLeaves(actual));
	}

	private List<String> collectLeaves(SyncTreeDirectory directory) {
		List<String> leaves = new ArrayList<>();
		collectLeaves(directory, leaves);
		return leaves;
	}

	private void collectLeaves(SyncTreeDirectory directory, List<String> leaves) {
		for (SyncTreeNode node : directory.getList()) {
			if (node instanceof SyncTreeDirectory child) {
				collectLeaves(child, leaves);
			} else if (node instanceof SyncTreeLeaf leaf) {
				leaves.add(leaf.getRelativePath() + "|" + leaf.getSha1() + "|" + leaf.getSize() + "|"
						+ leaf.getCompressedSize() + "|" + leaf.isCompressed());
			}
		}
	}

	private Path fixtureRoot() throws Exception {
		URL resource = getClass().getResource("/a3s-fixtures/.a3s/autoconfig");
		assertNotNull(resource, "Fixture autoconfig missing from test resources");
		Path autoconfig = Paths.get(resource.toURI());
		return autoconfig.getParent().getParent();
	}
}
