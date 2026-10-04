# Android content contract (A0)

The draft source of truth is `shared/api/openapi-v1.json`. Kotlin response
properties retain its exact camelCase names. CatalogResponse, CatalogSong,
SongResponse, ChartSummary, ChartResponse and AssetSummary map directly to the
corresponding schemas. Nullable preview positions and meter remain nullable.
Server metadata and transport decoding will be added with content-server in A3.

ContentKey scopes every content ID to its source. Its length-prefixed cache key
avoids collisions when either component contains separators. Local and server
sources never merge by song title. PreparedSong requires an application-managed
local audio path; the runtime must not stream HTTP or SAF content during play.

The current API has no asset hash. A3 must retain a catalog revision with each
cached preparation and invalidate affected preparations when the snapshot
changes. This is provisional until the shared API introduces content hashes.

ContentError carries a stable code, user-readable message and optional scoped
key. Import and preparation failures stay on the library screen; credentials
and absolute server paths must not appear in messages or diagnostics.

No database exists in A0. A1 must introduce an explicit schema version and
transactional migrations before persistent indexes are used. Failed migrations
must preserve imported media and local scores; silent destructive recreation is
not an acceptable migration strategy.
