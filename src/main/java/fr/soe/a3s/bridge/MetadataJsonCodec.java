package fr.soe.a3s.bridge;

import java.io.Serializable;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import fr.soe.a3s.constant.ProtocolType;
import fr.soe.a3s.domain.AbstractProtocole;
import fr.soe.a3s.domain.AbstractProtocoleFactory;
import fr.soe.a3s.domain.configration.FavoriteServer;
import fr.soe.a3s.domain.repository.AutoConfig;
import fr.soe.a3s.domain.repository.Changelog;
import fr.soe.a3s.domain.repository.Changelogs;
import fr.soe.a3s.domain.repository.Event;
import fr.soe.a3s.domain.repository.Events;
import fr.soe.a3s.domain.repository.ServerInfo;
import fr.soe.a3s.domain.repository.SyncTreeDirectory;
import fr.soe.a3s.domain.repository.SyncTreeLeaf;
import fr.soe.a3s.domain.repository.SyncTreeNode;

/**
 * Bidirectional JSON codec for classic {@code .a3s} domain graphs.
 * Parent links on sync trees are omitted on write and rebuilt on read.
 */
public final class MetadataJsonCodec {

	public static final int BRIDGE_VERSION = 1;
	private static final String ISO_PATTERN = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'";

	private final Gson gson = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

	public String toJson(MetadataKind kind, Object domainObject) {
		JsonObject root = new JsonObject();
		root.addProperty("bridgeVersion", BRIDGE_VERSION);
		root.addProperty("kind", kind.getFileName());
		root.add(kind.getFileName(), toJsonElement(kind, domainObject));
		return gson.toJson(root);
	}

	public String toJsonAll(Map<MetadataKind, Object> files) {
		JsonObject root = new JsonObject();
		root.addProperty("bridgeVersion", BRIDGE_VERSION);
		root.addProperty("kind", "all");
		JsonObject payload = new JsonObject();
		for (MetadataKind kind : MetadataKind.values()) {
			Object value = files.get(kind);
			if (value == null) {
				payload.add(kind.getFileName(), null);
			} else {
				payload.add(kind.getFileName(), toJsonElement(kind, value));
			}
		}
		root.add("files", payload);
		return gson.toJson(root);
	}

	public Serializable fromJson(MetadataKind kind, String json) {
		JsonObject root = JsonParser.parseString(json).getAsJsonObject();
		JsonElement payload = root.get(kind.getFileName());
		if (payload == null || payload.isJsonNull()) {
			if (root.has("files") && root.get("files").isJsonObject()) {
				payload = root.getAsJsonObject("files").get(kind.getFileName());
			}
		}
		if (payload == null || payload.isJsonNull()) {
			throw new IllegalArgumentException("JSON is missing payload for kind: " + kind.getFileName());
		}
		if (!payload.isJsonObject()) {
			throw new IllegalArgumentException("Expected object payload for kind: " + kind.getFileName());
		}
		return fromJsonElement(kind, payload.getAsJsonObject());
	}

	private JsonElement toJsonElement(MetadataKind kind, Object domainObject) {
		return switch (kind) {
			case AUTOCONFIG -> encodeAutoConfig((AutoConfig) domainObject);
			case SYNC -> encodeSyncTree((SyncTreeDirectory) domainObject);
			case SERVERINFO -> encodeServerInfo((ServerInfo) domainObject);
			case CHANGELOGS -> encodeChangelogs((Changelogs) domainObject);
			case EVENTS -> encodeEvents((Events) domainObject);
		};
	}

	private Serializable fromJsonElement(MetadataKind kind, JsonObject json) {
		return switch (kind) {
			case AUTOCONFIG -> decodeAutoConfig(json);
			case SYNC -> decodeSyncDirectory(json, null);
			case SERVERINFO -> decodeServerInfo(json);
			case CHANGELOGS -> decodeChangelogs(json);
			case EVENTS -> decodeEvents(json);
		};
	}

	private JsonObject encodeAutoConfig(AutoConfig autoConfig) {
		JsonObject json = new JsonObject();
		json.addProperty("repositoryName", autoConfig.getRepositoryName());
		json.add("protocol", encodeProtocol(autoConfig.getProtocole()));
		JsonArray favorites = new JsonArray();
		for (FavoriteServer server : autoConfig.getFavoriteServers()) {
			favorites.add(encodeFavoriteServer(server));
		}
		json.add("favoriteServers", favorites);
		return json;
	}

	private AutoConfig decodeAutoConfig(JsonObject json) {
		AutoConfig autoConfig = new AutoConfig();
		autoConfig.setRepositoryName(asString(json, "repositoryName"));
		if (json.has("protocol") && json.get("protocol").isJsonObject()) {
			autoConfig.setProtocole(decodeProtocol(json.getAsJsonObject("protocol")));
		}
		if (json.has("favoriteServers") && json.get("favoriteServers").isJsonArray()) {
			for (JsonElement element : json.getAsJsonArray("favoriteServers")) {
				if (element.isJsonObject()) {
					autoConfig.getFavoriteServers().add(decodeFavoriteServer(element.getAsJsonObject()));
				}
			}
		}
		return autoConfig;
	}

	private JsonObject encodeProtocol(AbstractProtocole protocol) {
		JsonObject json = new JsonObject();
		if (protocol == null) {
			return json;
		}
		ProtocolType type = protocol.getProtocolType();
		json.addProperty("type", type == null ? null : type.name());
		json.addProperty("url", protocol.getUrl());
		json.addProperty("port", protocol.getPort());
		json.addProperty("login", protocol.getLogin());
		json.addProperty("password", protocol.getPassword());
		json.addProperty("connectionTimeOut", protocol.getConnectionTimeOut());
		json.addProperty("readTimeOut", protocol.getReadTimeOut());
		json.addProperty("validateSSLCertificate", protocol.isValidateSSLCertificate());
		return json;
	}

	private AbstractProtocole decodeProtocol(JsonObject json) {
		String typeName = asString(json, "type");
		if (typeName == null || typeName.isBlank()) {
			throw new IllegalArgumentException("protocol.type is required");
		}
		ProtocolType protocolType;
		try {
			protocolType = ProtocolType.valueOf(typeName.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			ProtocolType byDescription = ProtocolType.getEnum(typeName);
			if (byDescription == null) {
				throw new IllegalArgumentException("Unknown protocol type: " + typeName);
			}
			protocolType = byDescription;
		}

		AbstractProtocole protocol = AbstractProtocoleFactory.getProtocol(
				asString(json, "url"),
				asString(json, "port"),
				asString(json, "login") == null ? "anonymous" : asString(json, "login"),
				asString(json, "password") == null ? "" : asString(json, "password"),
				protocolType,
				asBoolean(json, "validateSSLCertificate", protocolType == ProtocolType.HTTPS
						|| protocolType == ProtocolType.HTTPS_WEBDAV
						|| protocolType == ProtocolType.SFTP
						|| protocolType == ProtocolType.FTPS));
		if (protocol == null) {
			throw new IllegalArgumentException("Unsupported protocol type: " + typeName);
		}
		if (json.has("connectionTimeOut") && !json.get("connectionTimeOut").isJsonNull()) {
			protocol.setConnectionTimeOut(asString(json, "connectionTimeOut"));
		}
		if (json.has("readTimeOut") && !json.get("readTimeOut").isJsonNull()) {
			protocol.setReadTimeOut(asString(json, "readTimeOut"));
		}
		return protocol;
	}

	private JsonObject encodeFavoriteServer(FavoriteServer server) {
		JsonObject json = new JsonObject();
		json.addProperty("name", server.getDescription());
		json.addProperty("ipAddress", server.getIpAddress());
		json.addProperty("port", server.getPort());
		json.addProperty("password", server.getPassword());
		json.addProperty("selected", server.isSelected());
		json.addProperty("modsetName", server.getModsetName());
		json.addProperty("repositoryName", server.getRepositoryName());
		return json;
	}

	private FavoriteServer decodeFavoriteServer(JsonObject json) {
		FavoriteServer server = new FavoriteServer();
		server.setDescription(asString(json, "name"));
		server.setIpAddress(asString(json, "ipAddress"));
		server.setPort(asInt(json, "port", 0));
		server.setPassword(asString(json, "password"));
		server.setSelected(asBoolean(json, "selected", false));
		server.setModsetName(asString(json, "modsetName"));
		server.setRepositoryName(asString(json, "repositoryName"));
		return server;
	}

	private JsonObject encodeServerInfo(ServerInfo serverInfo) {
		JsonObject json = new JsonObject();
		json.addProperty("revision", serverInfo.getRevision());
		json.addProperty("buildDate", formatDate(serverInfo.getBuildDate()));
		json.addProperty("numberOfFiles", serverInfo.getNumberOfFiles());
		json.addProperty("totalFilesSize", serverInfo.getTotalFilesSize());
		json.addProperty("numberOfConnections", serverInfo.getNumberOfConnections());
		json.addProperty("noPartialFileTransfer", serverInfo.isNoPartialFileTransfer());
		json.addProperty("repositoryContentUpdated", serverInfo.isRepositoryContentUpdated());
		json.addProperty("compressedPboFilesOnly", serverInfo.isCompressedPboFilesOnly());
		JsonArray hidden = new JsonArray();
		for (String path : serverInfo.getHiddenFolderPaths()) {
			hidden.add(path);
		}
		json.add("hiddenFolderPaths", hidden);
		return json;
	}

	private ServerInfo decodeServerInfo(JsonObject json) {
		ServerInfo serverInfo = new ServerInfo();
		serverInfo.setRevision(asInt(json, "revision", 0));
		serverInfo.setBuildDate(parseDate(asString(json, "buildDate")));
		serverInfo.setNumberOfFiles(asLong(json, "numberOfFiles", 0L));
		serverInfo.setTotalFilesSize(asLong(json, "totalFilesSize", 0L));
		serverInfo.setNumberOfConnections(asInt(json, "numberOfConnections", 1));
		serverInfo.setNoPartialFileTransfer(asBoolean(json, "noPartialFileTransfer", false));
		serverInfo.setRepositoryContentUpdated(asBoolean(json, "repositoryContentUpdated", false));
		serverInfo.setCompressedPboFilesOnly(asBoolean(json, "compressedPboFilesOnly", false));
		Set<String> hidden = new HashSet<>();
		if (json.has("hiddenFolderPaths") && json.get("hiddenFolderPaths").isJsonArray()) {
			for (JsonElement element : json.getAsJsonArray("hiddenFolderPaths")) {
				if (element.isJsonPrimitive()) {
					hidden.add(element.getAsString());
				}
			}
		}
		serverInfo.getHiddenFolderPaths().addAll(hidden);
		return serverInfo;
	}

	private JsonObject encodeChangelogs(Changelogs changelogs) {
		JsonObject json = new JsonObject();
		JsonArray list = new JsonArray();
		for (Changelog changelog : changelogs.getList()) {
			JsonObject entry = new JsonObject();
			entry.addProperty("revision", changelog.getRevision());
			entry.addProperty("buildDate", formatDate(changelog.getBuildDate()));
			entry.addProperty("contentUpdated", changelog.isContentUpdated());
			entry.add("newAddons", stringArray(changelog.getNewAddons()));
			entry.add("updatedAddons", stringArray(changelog.getUpdatedAddons()));
			entry.add("deletedAddons", stringArray(changelog.getDeletedAddons()));
			entry.add("addons", stringArray(changelog.getAddons()));
			list.add(entry);
		}
		json.add("list", list);
		return json;
	}

	private Changelogs decodeChangelogs(JsonObject json) {
		Changelogs changelogs = new Changelogs();
		if (json.has("list") && json.get("list").isJsonArray()) {
			for (JsonElement element : json.getAsJsonArray("list")) {
				if (!element.isJsonObject()) {
					continue;
				}
				JsonObject entry = element.getAsJsonObject();
				Changelog changelog = new Changelog();
				changelog.setRevision(asInt(entry, "revision", 0));
				changelog.setBuildDate(parseDate(asString(entry, "buildDate")));
				changelog.setContentUpdated(asBoolean(entry, "contentUpdated", false));
				changelog.getNewAddons().addAll(stringList(entry, "newAddons"));
				changelog.getUpdatedAddons().addAll(stringList(entry, "updatedAddons"));
				changelog.getDeletedAddons().addAll(stringList(entry, "deletedAddons"));
				changelog.getAddons().addAll(stringList(entry, "addons"));
				changelogs.getList().add(changelog);
			}
		}
		return changelogs;
	}

	private JsonObject encodeEvents(Events events) {
		JsonObject json = new JsonObject();
		JsonArray list = new JsonArray();
		for (Event event : events.getList()) {
			JsonObject entry = new JsonObject();
			entry.addProperty("name", event.getName());
			entry.addProperty("description", event.getDescription());
			entry.add("addonNames", booleanMap(event.getAddonNames()));
			entry.add("dlcNames", booleanMap(event.getDlcNames()));
			entry.add("userconfigFolderNames", booleanMap(event.getUserconfigFolderNames()));
			list.add(entry);
		}
		json.add("list", list);
		return json;
	}

	private Events decodeEvents(JsonObject json) {
		Events events = new Events();
		if (json.has("list") && json.get("list").isJsonArray()) {
			for (JsonElement element : json.getAsJsonArray("list")) {
				if (!element.isJsonObject()) {
					continue;
				}
				JsonObject entry = element.getAsJsonObject();
				String name = asString(entry, "name");
				Event event = new Event(name == null ? "" : name);
				event.setDescription(asString(entry, "description"));
				event.getAddonNames().putAll(booleanMap(entry, "addonNames"));
				event.getDlcNames().putAll(booleanMap(entry, "dlcNames"));
				event.getUserconfigFolderNames().putAll(booleanMap(entry, "userconfigFolderNames"));
				events.getList().add(event);
			}
		}
		return events;
	}

	private JsonObject encodeSyncTree(SyncTreeDirectory directory) {
		JsonObject json = new JsonObject();
		json.addProperty("type", "directory");
		json.addProperty("name", directory.getName());
		json.addProperty("markAsAddon", directory.isMarkAsAddon());
		json.addProperty("updated", directory.isUpdated());
		json.addProperty("deleted", directory.isDeleted());
		json.addProperty("hidden", directory.isHidden());
		JsonArray children = new JsonArray();
		for (SyncTreeNode node : directory.getList()) {
			if (node instanceof SyncTreeDirectory childDir) {
				children.add(encodeSyncTree(childDir));
			} else if (node instanceof SyncTreeLeaf leaf) {
				children.add(encodeSyncLeaf(leaf));
			}
		}
		json.add("children", children);
		return json;
	}

	private JsonObject encodeSyncLeaf(SyncTreeLeaf leaf) {
		JsonObject json = new JsonObject();
		json.addProperty("type", "leaf");
		json.addProperty("name", leaf.getName());
		json.addProperty("sha1", leaf.getSha1());
		json.addProperty("size", leaf.getSize());
		json.addProperty("compressedSize", leaf.getCompressedSize());
		json.addProperty("compressed", leaf.isCompressed());
		json.addProperty("updated", leaf.isUpdated());
		json.addProperty("deleted", leaf.isDeleted());
		return json;
	}

	private SyncTreeDirectory decodeSyncDirectory(JsonObject json, SyncTreeDirectory parent) {
		String name = asString(json, "name");
		if (name == null || name.isBlank()) {
			name = SyncTreeNode.RACINE;
		}
		SyncTreeDirectory directory = new SyncTreeDirectory(name, parent);
		directory.setMarkAsAddon(asBoolean(json, "markAsAddon", false));
		directory.setUpdated(asBoolean(json, "updated", false));
		directory.setDeleted(asBoolean(json, "deleted", false));
		directory.setHidden(asBoolean(json, "hidden", false));
		if (json.has("children") && json.get("children").isJsonArray()) {
			for (JsonElement element : json.getAsJsonArray("children")) {
				if (!element.isJsonObject()) {
					continue;
				}
				JsonObject child = element.getAsJsonObject();
				String type = asString(child, "type");
				if ("leaf".equalsIgnoreCase(type)) {
					directory.addTreeNode(decodeSyncLeaf(child, directory));
				} else {
					directory.addTreeNode(decodeSyncDirectory(child, directory));
				}
			}
		}
		return directory;
	}

	private SyncTreeLeaf decodeSyncLeaf(JsonObject json, SyncTreeDirectory parent) {
		String name = asString(json, "name");
		SyncTreeLeaf leaf = new SyncTreeLeaf(name == null ? "" : name, parent);
		leaf.setSha1(asString(json, "sha1"));
		leaf.setSize(asLong(json, "size", 0L));
		leaf.setCompressedSize(asLong(json, "compressedSize", 0L));
		leaf.setCompressed(asBoolean(json, "compressed", false));
		leaf.setUpdated(asBoolean(json, "updated", false));
		leaf.setDeleted(asBoolean(json, "deleted", false));
		return leaf;
	}

	private JsonArray stringArray(List<String> values) {
		JsonArray array = new JsonArray();
		for (String value : values) {
			array.add(value);
		}
		return array;
	}

	private List<String> stringList(JsonObject json, String field) {
		List<String> values = new ArrayList<>();
		if (!json.has(field) || !json.get(field).isJsonArray()) {
			return values;
		}
		for (JsonElement element : json.getAsJsonArray(field)) {
			if (element.isJsonPrimitive()) {
				values.add(element.getAsString());
			}
		}
		return values;
	}

	private JsonObject booleanMap(Map<String, Boolean> map) {
		JsonObject json = new JsonObject();
		if (map == null) {
			return json;
		}
		for (Map.Entry<String, Boolean> entry : map.entrySet()) {
			json.addProperty(entry.getKey(), Boolean.TRUE.equals(entry.getValue()));
		}
		return json;
	}

	private Map<String, Boolean> booleanMap(JsonObject parent, String field) {
		Map<String, Boolean> map = new LinkedHashMap<>();
		if (!parent.has(field) || !parent.get(field).isJsonObject()) {
			return map;
		}
		JsonObject json = parent.getAsJsonObject(field);
		for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
			JsonElement value = entry.getValue();
			map.put(entry.getKey(), value.isJsonPrimitive() && ((JsonPrimitive) value).isBoolean()
					? value.getAsBoolean()
					: Boolean.parseBoolean(String.valueOf(value)));
		}
		return map;
	}

	private String formatDate(Date date) {
		if (date == null) {
			return null;
		}
		SimpleDateFormat format = new SimpleDateFormat(ISO_PATTERN, Locale.US);
		format.setTimeZone(TimeZone.getTimeZone("UTC"));
		return format.format(date);
	}

	private Date parseDate(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			SimpleDateFormat format = new SimpleDateFormat(ISO_PATTERN, Locale.US);
			format.setTimeZone(TimeZone.getTimeZone("UTC"));
			return format.parse(raw);
		} catch (ParseException e) {
			try {
				return new Date(Long.parseLong(raw));
			} catch (NumberFormatException ignored) {
				throw new IllegalArgumentException("Invalid date: " + raw, e);
			}
		}
	}

	private String asString(JsonObject json, String field) {
		if (!json.has(field) || json.get(field).isJsonNull()) {
			return null;
		}
		JsonElement element = json.get(field);
		if (element.isJsonPrimitive()) {
			return element.getAsString();
		}
		return element.toString();
	}

	private boolean asBoolean(JsonObject json, String field, boolean defaultValue) {
		if (!json.has(field) || json.get(field).isJsonNull()) {
			return defaultValue;
		}
		return json.get(field).getAsBoolean();
	}

	private int asInt(JsonObject json, String field, int defaultValue) {
		if (!json.has(field) || json.get(field).isJsonNull()) {
			return defaultValue;
		}
		return json.get(field).getAsInt();
	}

	private long asLong(JsonObject json, String field, long defaultValue) {
		if (!json.has(field) || json.get(field).isJsonNull()) {
			return defaultValue;
		}
		return json.get(field).getAsLong();
	}
}
