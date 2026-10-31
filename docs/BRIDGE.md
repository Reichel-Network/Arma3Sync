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
| Write several files after build | `write --target <contentRoot> --kind all --in <jsonFile>` |
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

Failures are always non-zero (unlike several legacy Arma3Sync CLI paths). Missing JSON payloads, invalid dates, unknown protocol types, and malformed JSON are exit `3` — not usage.

## JSON contract (`bridgeVersion: 1`)

Single-kind response / write body:

```json
{
  "bridgeVersion": 1,
  "kind": "serverinfo",
  "serverinfo": { "revision": 12, "buildDate": "2026-10-29T18:44:48.000Z", "…": "…" }
}
```

`kind=all` response / multi-write body:

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

Absent optional files under `all` are `null` on read (a warning is printed on stderr). A specific `--kind` that is missing fails with exit `2`. On `write --kind all`, null / omitted entries are skipped; at least one non-null file is required.

Single-kind `write` also accepts a payload nested under `files.<kind>` (same shape as the `all` envelope) so FASTER can reuse one JSON builder for both modes.

### Field shapes (Phase 2 publish)

Dates are UTC ISO-8601 (`yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`). Epoch millis as a JSON number/string is also accepted on write.

#### `autoconfig`

```json
{
  "repositoryName": "reichel-arma",
  "protocol": {
    "type": "HTTPS",
    "url": "arma.game.reichel.network",
    "port": "443",
    "login": "anonymous",
    "password": "",
    "connectionTimeOut": "30000",
    "readTimeOut": "30000",
    "validateSSLCertificate": true
  },
  "favoriteServers": [
    {
      "name": "Server",
      "ipAddress": "1.2.3.4",
      "port": 2302,
      "password": "",
      "selected": false,
      "modsetName": "",
      "repositoryName": "reichel-arma"
    }
  ]
}
```

`protocol.type`: `HTTP`, `HTTPS`, `FTP`, `FTPS`, `SFTP`, `HTTP_WEBDAV`, `HTTPS_WEBDAV`, … (`ProtocolType` enum name).

#### `serverinfo`

```json
{
  "revision": 12,
  "buildDate": "2026-10-29T18:44:48.000Z",
  "numberOfFiles": 451,
  "totalFilesSize": 123456789,
  "numberOfConnections": 1,
  "noPartialFileTransfer": false,
  "repositoryContentUpdated": true,
  "compressedPboFilesOnly": false,
  "hiddenFolderPaths": ["@mod/hidden"]
}
```

FASTER Phase 2 bumps `revision`, sets `buildDate`, and refreshes file counts/sizes after the C# content walk.

#### `changelogs`

```json
{
  "list": [
    {
      "revision": 12,
      "buildDate": "2026-10-29T18:44:48.000Z",
      "contentUpdated": true,
      "newAddons": ["@new"],
      "updatedAddons": ["@changed"],
      "deletedAddons": ["@gone"],
      "addons": ["@new", "@changed", "@kept"]
    }
  ]
}
```

#### `events`

```json
{
  "list": [
    {
      "name": "Weekend ops",
      "description": "Optional",
      "addonNames": { "@mod": true },
      "dlcNames": {},
      "userconfigFolderNames": {}
    }
  ]
}
```

Boolean maps mark selected addon / DLC / userconfig folders for the event.

#### `sync`

Tree of directories and leaves. Parent links are omitted in JSON and rebuilt on write. Root name is usually `racine`.

```json
{
  "type": "directory",
  "name": "racine",
  "markAsAddon": false,
  "updated": false,
  "deleted": false,
  "hidden": false,
  "children": [
    {
      "type": "directory",
      "name": "@mod",
      "markAsAddon": true,
      "updated": false,
      "deleted": false,
      "hidden": false,
      "children": [
        {
          "type": "leaf",
          "name": "addons/mod.pbo",
          "sha1": "deadbeef…",
          "size": 1024,
          "compressedSize": 0,
          "compressed": false,
          "updated": false,
          "deleted": false
        }
      ]
    }
  ]
}
```

`write` re-serializes through `A3SFilesAccessor` (GZIP `ObjectOutputStream`) so community clients keep classic wire compatibility. Prefer `copy` when you already have a trusted classic blob and only need to place it under a content root.

## Out of scope

- Full repository sync / check / build / upload orchestration
- Swing / FlatLaf / interactive `-console`
- Sealed `*.a3s.repository` local defs (FASTER settings remain source of truth)
- Replacing C# protocol download of addon trees

## License

GNU GPLv3 — same as Arma3Sync. Preserve notices when bundling beside FASTER.
