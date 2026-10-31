package fr.soe.a3s.bridge;

import java.util.Locale;

import fr.soe.a3s.dao.DataAccessConstants;

/**
 * Classic remote {@code .a3s/} metadata file kinds (extensionless).
 */
public enum MetadataKind {

	AUTOCONFIG(DataAccessConstants.AUTOCONFIG_FILE_NAME),
	SYNC(DataAccessConstants.SYNC_FILE_NAME),
	SERVERINFO(DataAccessConstants.SERVERINFO_FILE_NAME),
	CHANGELOGS(DataAccessConstants.CHANGELOGS_FILE_NAME),
	EVENTS(DataAccessConstants.EVENTS_FILE_NAME);

	private final String fileName;

	MetadataKind(String fileName) {
		this.fileName = fileName;
	}

	public String getFileName() {
		return fileName;
	}

	public static MetadataKind parse(String raw) {
		if (raw == null || raw.isBlank()) {
			throw new IllegalArgumentException("Metadata kind is required.");
		}
		String normalized = raw.trim().toLowerCase(Locale.ROOT);
		for (MetadataKind kind : values()) {
			if (kind.name().toLowerCase(Locale.ROOT).equals(normalized)
					|| kind.fileName.equals(normalized)) {
				return kind;
			}
		}
		throw new IllegalArgumentException(
				"Unknown metadata kind: " + raw + " (expected autoconfig|sync|serverinfo|changelogs|events|all)");
	}

	public static boolean isAll(String raw) {
		return raw != null && "all".equalsIgnoreCase(raw.trim());
	}
}
