package fr.soe.a3s.bridge;

import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;

import fr.soe.a3s.dao.A3SFilesAccessor;
import fr.soe.a3s.domain.repository.AutoConfig;
import fr.soe.a3s.domain.repository.Changelogs;
import fr.soe.a3s.domain.repository.Events;
import fr.soe.a3s.domain.repository.ServerInfo;
import fr.soe.a3s.domain.repository.SyncTreeDirectory;

/**
 * Thin wrapper around {@link A3SFilesAccessor} that types classic {@code .a3s} blobs.
 */
public final class MetadataBlobIO {

	public Object readFile(Path file, MetadataKind kind) throws IOException {
		Object object = A3SFilesAccessor.read(file.toFile());
		return cast(kind, object, file.toString());
	}

	public Object readBytes(byte[] bytes, MetadataKind kind, String label) throws IOException {
		Path temporary = Files.createTempFile("a3s-bridge-" + kind.getFileName() + "-", ".bin");
		try {
			Files.write(temporary, bytes);
			Object object = A3SFilesAccessor.read(temporary.toFile());
			return cast(kind, object, label);
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	public void write(Path file, Serializable object) throws IOException {
		Path parent = file.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		A3SFilesAccessor.write(object, file.toFile());
	}

	public Object readFromSource(MetadataSource source, MetadataKind kind) throws IOException {
		if (!source.isRemote()) {
			return readFile(source.resolveLocalFile(kind), kind);
		}
		byte[] bytes = source.readBytes(kind);
		return readBytes(bytes, kind, source.getRemoteA3sBaseUrl() + kind.getFileName());
	}

	private Object cast(MetadataKind kind, Object object, String label) throws IOException {
		if (object == null) {
			throw new IOException("Deserialized null object from " + label);
		}
		Class<?> expected = expectedType(kind);
		if (!expected.isInstance(object)) {
			throw new IOException("Unexpected type for " + kind.getFileName() + " at " + label + ": "
					+ object.getClass().getName() + " (expected " + expected.getName() + ")");
		}
		return object;
	}

	private Class<?> expectedType(MetadataKind kind) {
		return switch (kind) {
			case AUTOCONFIG -> AutoConfig.class;
			case SYNC -> SyncTreeDirectory.class;
			case SERVERINFO -> ServerInfo.class;
			case CHANGELOGS -> Changelogs.class;
			case EVENTS -> Events.class;
		};
	}
}
