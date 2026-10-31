package fr.soe.a3s.bridge;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

import fr.soe.a3s.dao.DataAccessConstants;

/**
 * Resolves a local content root / {@code .a3s} folder or a remote repository URL
 * into classic metadata file bytes.
 */
public final class MetadataSource {

	private final Path localA3sDir;
	private final String remoteA3sBaseUrl;
	private final HttpClient httpClient;

	private MetadataSource(Path localA3sDir, String remoteA3sBaseUrl, HttpClient httpClient) {
		this.localA3sDir = localA3sDir;
		this.remoteA3sBaseUrl = remoteA3sBaseUrl;
		this.httpClient = httpClient;
	}

	public boolean isRemote() {
		return remoteA3sBaseUrl != null;
	}

	public Path getLocalA3sDir() {
		return localA3sDir;
	}

	public String getRemoteA3sBaseUrl() {
		return remoteA3sBaseUrl;
	}

	public static MetadataSource fromArgument(String source) throws IOException {
		Objects.requireNonNull(source, "source");
		String trimmed = source.trim();
		if (trimmed.isEmpty()) {
			throw new IOException("Source path or URL must not be empty.");
		}

		String lower = trimmed.toLowerCase(Locale.ROOT);
		if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("ftp://")) {
			return fromRemoteUrl(trimmed);
		}

		Path path = Path.of(trimmed).toAbsolutePath().normalize();
		return fromLocalPath(path);
	}

	public static MetadataSource fromLocalPath(Path path) throws IOException {
		if (!Files.exists(path)) {
			throw new IOException("Local source does not exist: " + path);
		}

		Path a3sDir;
		if (Files.isRegularFile(path)) {
			Path parent = path.getParent();
			if (parent == null || !DataAccessConstants.A3S_FOlDER_NAME.equals(parent.getFileName().toString())) {
				throw new IOException("Local metadata file must live under a .a3s directory: " + path);
			}
			a3sDir = parent;
		} else if (DataAccessConstants.A3S_FOlDER_NAME.equals(path.getFileName().toString())) {
			a3sDir = path;
		} else {
			a3sDir = path.resolve(DataAccessConstants.A3S_FOlDER_NAME);
			if (!Files.isDirectory(a3sDir)) {
				throw new IOException("No .a3s directory under content root: " + path);
			}
		}

		return new MetadataSource(a3sDir, null, null);
	}

	public static MetadataSource fromRemoteUrl(String url) throws IOException {
		String base = normalizeRemoteA3sBase(url);
		HttpClient client = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(30))
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build();
		return new MetadataSource(null, base, client);
	}

	/**
	 * Accepts either a content-root URL, a {@code .../.a3s} URL, or a full
	 * {@code .../.a3s/autoconfig} (or other kind) URL and returns the
	 * {@code .../.a3s/} base with a trailing slash.
	 */
	static String normalizeRemoteA3sBase(String url) throws IOException {
		String trimmed = url.trim();
		while (trimmed.endsWith("/")) {
			trimmed = trimmed.substring(0, trimmed.length() - 1);
		}

		String lower = trimmed.toLowerCase(Locale.ROOT);
		for (MetadataKind kind : MetadataKind.values()) {
			String suffix = "/" + DataAccessConstants.A3S_FOlDER_NAME + "/" + kind.getFileName();
			if (lower.endsWith(suffix.toLowerCase(Locale.ROOT))) {
				return trimmed.substring(0, trimmed.length() - kind.getFileName().length());
			}
		}

		if (lower.endsWith("/" + DataAccessConstants.A3S_FOlDER_NAME)) {
			return trimmed + "/";
		}

		return trimmed + "/" + DataAccessConstants.A3S_FOlDER_NAME + "/";
	}

	public byte[] readBytes(MetadataKind kind) throws IOException {
		if (localA3sDir != null) {
			Path file = localA3sDir.resolve(kind.getFileName());
			if (!Files.isRegularFile(file)) {
				throw new IOException("Missing local metadata file: " + file);
			}
			return Files.readAllBytes(file);
		}

		String requestUrl = remoteA3sBaseUrl + kind.getFileName();
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(requestUrl))
					.timeout(Duration.ofSeconds(120))
					.header("Cache-Control", "no-cache")
					.header("Pragma", "no-cache")
					.GET()
					.build();
			HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
			int status = response.statusCode();
			if (status < 200 || status >= 300) {
				throw new IOException("HTTP " + status + " fetching " + requestUrl);
			}
			try (InputStream in = response.body()) {
				return in.readAllBytes();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while fetching " + requestUrl, e);
		} catch (IllegalArgumentException e) {
			throw new IOException("Invalid remote URL: " + requestUrl, e);
		}
	}

	public Path resolveLocalFile(MetadataKind kind) throws IOException {
		if (localA3sDir == null) {
			throw new IOException("Cannot resolve a local path for a remote source.");
		}
		return localA3sDir.resolve(kind.getFileName());
	}

	public static Path resolveWriteA3sDir(Path target) throws IOException {
		Path path = target.toAbsolutePath().normalize();
		Path a3sDir;
		if (DataAccessConstants.A3S_FOlDER_NAME.equals(path.getFileName().toString())) {
			a3sDir = path;
		} else {
			a3sDir = path.resolve(DataAccessConstants.A3S_FOlDER_NAME);
		}
		Files.createDirectories(a3sDir);
		return a3sDir;
	}
}
