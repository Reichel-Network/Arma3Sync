# a3s-metadata-bridge

Headless classic `.a3s` metadata reader/writer for FASTER’s `IA3SMetadataStore` (integration path **A**).

This is an **internal compatibility adapter**. Operators never configure or launch it as a product. It does **not** open Swing, does **not** run sync/check/build/upload, and does **not** use `%APPDATA%\Arma3Sync` as a source of truth.

## Build

```bash
gradle metadataBridgeJar
# → build/libs/a3s-metadata-bridge-<version>-all.jar
```

Requires JDK 25 (same toolchain as Arma3Sync).

## Invoke (FASTER)

```text
java -jar a3s-metadata-bridge-<version>-all.jar <command> [options]
```

Suggested process spawn from `BridgeA3SMetadataStore`:

| Need | Command |
| --- | --- |
| Import / refresh repo metadata | `read --source <autoconfigUrlOrContentRoot> --kind all --out -` |
| Read one file | `read --source … --kind autoconfig\|sync\|serverinfo\|changelogs\|events --out -` |
| Write one file after build | `write --target <contentRoot> --kind <kind> --in <jsonFile>` |
| Mirror remote blobs locally (binary fidelity via deserialize→serialize) | `copy --from <url> --to <contentRoot> --kind all` |

`--source` / `--from` accept:

- Remote autoconfig URL: `https://host/path/.a3s/autoconfig` (also content-root or `…/.a3s/` URLs)
- Local content root containing `.a3s/`
- Local `.a3s/` directory
- Local path to a single metadata file under `.a3s/`

`--out` / `--in` default to `-` (stdout / stdin).

### Exit codes

| Code | Meaning |
| --- | --- |
| `0` | Success |
| `1` | Usage / unknown command |
| `2` | I/O, HTTP, missing file, deserialize failure |
| `3` | JSON / domain mapping error |

Failures are always non-zero (unlike several legacy Arma3Sync CLI paths).

## JSON contract (`bridgeVersion: 1`)

Single-kind response:

```json
{
  "bridgeVersion": 1,
  "kind": "serverinfo",
  "serverinfo": { "revision": 12, "buildDate": "2026-10-29T18:44:48.000Z", "…": "…" }
}
```

`kind=all` response:

```json
{
  "bridgeVersion": 1,
  "kind": "all",
  "files": {
    "autoconfig": { },
    "sync": { },
    "serverinfo": { },
    "changelogs": { },
    "events": null
  }
}
```

Absent optional files under `all` are `null` (a warning is printed on stderr). A specific `--kind` that is missing fails with exit `2`.

### Notable shapes

- **`autoconfig.protocol.type`**: `HTTP`, `HTTPS`, `FTP`, `FTPS`, `SFTP`, `HTTP_WEBDAV`, `HTTPS_WEBDAV`, …
- **`sync`**: tree of `{ "type": "directory"|"leaf", "name", "children"? , "sha1"? , "size"? , … }`. Parent links are omitted in JSON and rebuilt on write. Root name is usually `racine`.
- Dates are UTC ISO-8601 (`yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`).

`write` re-serializes through `A3SFilesAccessor` (GZIP `ObjectOutputStream`) so community clients keep classic wire compatibility. Prefer `copy` when you already have a trusted classic blob and only need to place it under a content root.

## Out of scope

- Full repository sync / check / build / upload orchestration
- Swing / FlatLaf / interactive `-console`
- Sealed `*.a3s.repository` local defs (FASTER settings remain source of truth)
- Replacing C# protocol download of addon trees

## License

GNU GPLv3 — same as Arma3Sync. Preserve notices when bundling beside FASTER.
