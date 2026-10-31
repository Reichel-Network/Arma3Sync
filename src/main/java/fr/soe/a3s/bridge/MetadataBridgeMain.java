package fr.soe.a3s.bridge;

import java.io.IOException;
import java.io.PrintStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Headless metadata bridge for FASTER's {@code IA3SMetadataStore} (Phase A).
 *
 * <p>No Swing. Machine-friendly JSON on stdin/stdout or files. Non-zero exits on failure.
 *
 * <pre>
 *   java -jar a3s-metadata-bridge.jar read  --source &lt;url|path&gt; [--kind all|autoconfig|...] [--out -|file]
 *   java -jar a3s-metadata-bridge.jar write --target &lt;path&gt; --kind &lt;kind&gt; [--in -|file]
 *   java -jar a3s-metadata-bridge.jar copy  --from &lt;url|path&gt; --to &lt;path&gt; [--kind all|...]
 * </pre>
 */
public final class MetadataBridgeMain {

	private final PrintStream out;
	private final PrintStream err;
	private final MetadataBlobIO blobIO = new MetadataBlobIO();
	private final MetadataJsonCodec codec = new MetadataJsonCodec();

	MetadataBridgeMain(PrintStream out, PrintStream err) {
		this.out = out;
		this.err = err;
	}

	public static void main(String[] args) {
		System.exit(new MetadataBridgeMain(System.out, System.err).run(args));
	}

	int run(String[] args) {
		if (args == null || args.length == 0 || isHelp(args[0])) {
			printUsage(out);
			return args == null || args.length == 0 ? BridgeExitCodes.USAGE : BridgeExitCodes.SUCCESS;
		}

		String command = args[0].trim().toLowerCase(Locale.ROOT);
		Map<String, String> options = new LinkedHashMap<>();
		try {
			parseOptions(args, 1, options);
		} catch (IllegalArgumentException e) {
			err.println(e.getMessage());
			printUsage(err);
			return BridgeExitCodes.USAGE;
		}

		try {
			return switch (command) {
				case "read" -> read(options);
				case "write" -> write(options);
				case "copy" -> copy(options);
				case "help", "-h", "--help" -> {
					printUsage(out);
					yield BridgeExitCodes.SUCCESS;
				}
				default -> {
					err.println("Unknown command: " + command);
					printUsage(err);
					yield BridgeExitCodes.USAGE;
				}
			};
		} catch (IllegalArgumentException e) {
			err.println(e.getMessage());
			return BridgeExitCodes.USAGE;
		} catch (IOException e) {
			err.println(e.getMessage());
			return BridgeExitCodes.IO_ERROR;
		} catch (BridgeDataException e) {
			err.println(e.getMessage());
			return BridgeExitCodes.DATA_ERROR;
		} catch (RuntimeException e) {
			err.println(e.getMessage() == null ? e.toString() : e.getMessage());
			return BridgeExitCodes.DATA_ERROR;
		}
	}

	private int read(Map<String, String> options) throws IOException {
		String sourceArg = require(options, "source");
		String kindArg = options.getOrDefault("kind", "all");
		String outArg = options.getOrDefault("out", "-");

		MetadataSource source = MetadataSource.fromArgument(sourceArg);
		String json;
		if (MetadataKind.isAll(kindArg)) {
			Map<MetadataKind, Object> files = new EnumMap<>(MetadataKind.class);
			for (MetadataKind kind : MetadataKind.values()) {
				try {
					files.put(kind, blobIO.readFromSource(source, kind));
				} catch (IOException e) {
					// Optional files (often events) may be absent; keep null for kind=all.
					files.put(kind, null);
					err.println("warn: could not read " + kind.getFileName() + ": " + e.getMessage());
				}
			}
			boolean any = files.values().stream().anyMatch(v -> v != null);
			if (!any) {
				throw new IOException("No metadata files could be read from " + sourceArg);
			}
			json = codec.toJsonAll(files);
		} else {
			MetadataKind kind = MetadataKind.parse(kindArg);
			Object domain = blobIO.readFromSource(source, kind);
			json = codec.toJson(kind, domain);
		}

		writeText(outArg, json);
		return BridgeExitCodes.SUCCESS;
	}

	private int write(Map<String, String> options) throws IOException {
		String targetArg = require(options, "target");
		String kindArg = require(options, "kind");
		String inArg = options.getOrDefault("in", "-");
		String json = readText(inArg);
		Path a3sDir = MetadataSource.resolveWriteA3sDir(Path.of(targetArg));

		if (MetadataKind.isAll(kindArg)) {
			Map<MetadataKind, Serializable> files = codec.fromJsonAll(json);
			int written = 0;
			for (Map.Entry<MetadataKind, Serializable> entry : files.entrySet()) {
				Path file = a3sDir.resolve(entry.getKey().getFileName());
				blobIO.write(file, entry.getValue());
				written++;
			}
			out.println("Wrote " + written + " metadata file(s) to " + a3sDir.toAbsolutePath().normalize());
			return BridgeExitCodes.SUCCESS;
		}

		MetadataKind kind = MetadataKind.parse(kindArg);
		Serializable domain = codec.fromJson(kind, json);
		Path file = a3sDir.resolve(kind.getFileName());
		blobIO.write(file, domain);
		out.println("Wrote " + file.toAbsolutePath().normalize());
		return BridgeExitCodes.SUCCESS;
	}

	private int copy(Map<String, String> options) throws IOException {
		String fromArg = require(options, "from");
		String toArg = require(options, "to");
		String kindArg = options.getOrDefault("kind", "all");

		MetadataSource source = MetadataSource.fromArgument(fromArg);
		Path a3sDir = MetadataSource.resolveWriteA3sDir(Path.of(toArg));

		if (MetadataKind.isAll(kindArg)) {
			int written = 0;
			for (MetadataKind kind : MetadataKind.values()) {
				try {
					Object domain = blobIO.readFromSource(source, kind);
					blobIO.write(a3sDir.resolve(kind.getFileName()), (Serializable) domain);
					written++;
				} catch (IOException e) {
					err.println("warn: skipped " + kind.getFileName() + ": " + e.getMessage());
				}
			}
			if (written == 0) {
				throw new IOException("No metadata files could be copied from " + fromArg);
			}
			out.println("Copied " + written + " metadata file(s) to " + a3sDir.toAbsolutePath().normalize());
			return BridgeExitCodes.SUCCESS;
		}

		MetadataKind kind = MetadataKind.parse(kindArg);
		Object domain = blobIO.readFromSource(source, kind);
		Path file = a3sDir.resolve(kind.getFileName());
		blobIO.write(file, (Serializable) domain);
		out.println("Copied " + kind.getFileName() + " to " + file.toAbsolutePath().normalize());
		return BridgeExitCodes.SUCCESS;
	}

	private static void parseOptions(String[] args, int start, Map<String, String> options) {
		for (int i = start; i < args.length; i++) {
			String token = args[i];
			if (!token.startsWith("--")) {
				throw new IllegalArgumentException("Unexpected argument: " + token);
			}
			String key = token.substring(2);
			if (key.isEmpty()) {
				throw new IllegalArgumentException("Empty option name.");
			}
			if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
				throw new IllegalArgumentException("Missing value for --" + key);
			}
			options.put(key, args[++i]);
		}
	}

	private static String require(Map<String, String> options, String key) {
		String value = options.get(key);
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("Missing required option --" + key);
		}
		return value;
	}

	private static boolean isHelp(String token) {
		String normalized = token.trim().toLowerCase(Locale.ROOT);
		return "help".equals(normalized) || "-h".equals(normalized) || "--help".equals(normalized);
	}

	private void writeText(String outArg, String text) throws IOException {
		if ("-".equals(outArg)) {
			out.print(text);
			if (!text.endsWith("\n")) {
				out.println();
			}
			return;
		}
		Path path = Path.of(outArg);
		Path parent = path.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		Files.writeString(path, text.endsWith("\n") ? text : text + "\n", StandardCharsets.UTF_8);
	}

	private String readText(String inArg) throws IOException {
		if ("-".equals(inArg)) {
			return new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
		}
		return Files.readString(Path.of(inArg), StandardCharsets.UTF_8);
	}

	static void printUsage(PrintStream stream) {
		stream.println("a3s-metadata-bridge — headless classic .a3s metadata I/O for FASTER");
		stream.println();
		stream.println("Usage:");
		stream.println("  read  --source <url|path> [--kind all|autoconfig|sync|serverinfo|changelogs|events] [--out -|file]");
		stream.println("  write --target <content-root|.a3s-dir> --kind <kind|all> [--in -|file]");
		stream.println("  copy  --from <url|path> --to <content-root|.a3s-dir> [--kind all|...]");
		stream.println();
		stream.println("Exit codes: 0 success, 1 usage, 2 I/O/network, 3 data/JSON error");
		stream.println("See docs/BRIDGE.md for the JSON contract.");
	}
}
