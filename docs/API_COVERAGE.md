# OpenList API coverage

The Android client route catalog targets OpenList **v4.2.5**, stable commit
`cc87e88f038a5a27c8782afc7b66a3c1a3cdcb77`. Its route declarations live in `server/router.go` and
`server/handles/task.go`. The inventory was also cross-checked against main commit
`1a6cabf45aecf66c6d2ff6c32aed39d50264f43c`; the covered routes are identical in those snapshots.

`EndpointCatalog` is the machine-checkable source of truth. It models **199 unique canonical
`/api` paths**. A Gin `Any` declaration remains one logical path while its nine accepted HTTP
verbs are exposed through `Endpoint.methods`.

## Coverage totals

| Area | Canonical paths |
|---|---:|
| auth, me, WebAuthn, public | 24 |
| filesystem, archive, torrent, upload | 31 |
| sharing | 7 |
| admin, excluding legacy task aliases | 53 |
| task: 7 families × 12 actions | 84 |
| **Total** | **199** |

The pinned fixture records the exact upstream declaration, including Gin `ANY` rather than a
client-preferred verb. The logical route-method distribution is `ANY 11`, `GET 40`, `POST 145`,
and `PUT 3`.

The catalog records effective authorization behavior rather than reducing access to a Boolean:

| `ApiAccess` | Upstream behavior | Paths |
|---|---|---:|
| `PUBLIC` | no identity middleware | 12 |
| `GUEST_IF_ENABLED` | disabled guests are rejected | 32 |
| `GUEST_ALWAYS` | disabled guests remain accepted end-to-end | 0 |
| `AUTHN_CONTEXT` | WebAuthn identity middleware | 4 |
| `NON_GUEST` | guest identities are explicitly rejected | 97 |
| `ADMIN` | `Auth(false)` plus `AuthAdmin` | 54 |

The router registers `fs/list`, `fs/get`, `fs/archive/meta`, and `fs/archive/list` with
`Auth(true)`. Their split handlers dispatch request-body paths under `/@s` to sharing handlers
before the regular disabled-guest check. The catalog therefore records `GUEST_IF_ENABLED` as the
default and an explicit `/@s` override to `GUEST_ALWAYS`; callers can query the effective value with
`Endpoint.accessForRequestPath`.

## Task matrix and compatibility aliases

`TaskKind` defines these seven task managers:

- `upload`
- `copy`
- `move`
- `offline_download`
- `offline_download_transfer`
- `decompress`
- `decompress_upload`

Every manager exposes the same twelve `TaskAction` values:

- GET: `undone`, `done`
- POST with `tid` query: `info`, `cancel`, `delete`, `retry`
- POST with a JSON task-id array: `cancel_some`, `delete_some`, `retry_some`
- POST without arguments: `clear_done`, `clear_succeeded`, `retry_failed`

The Cartesian product generates 84 canonical `/api/task/{kind}/{action}` paths. OpenList also
registers 84 `/api/admin/task/{kind}/{action}` compatibility aliases for older automation. Each
alias is attached to its canonical endpoint with `EndpointAliasKind.LEGACY_ADMIN_TASK`; aliases
inherit administrator access and intentionally do not increase the canonical count.

## Calling the routes

- `OpenListApi` remains the small application-facing facade for common auth and filesystem flows.
- `AdminApi` provides the 53 non-task administrator routes as `AdminRoute`, typed generic parsing,
  JSON/Unit helpers, and catalog-validated dynamic lookup. Its dynamic lookup can also resolve the
  legacy admin-task aliases.
- `TaskApi` provides typed list/info/action/batch helpers and a generic operation for every
  `TaskKind × TaskAction` pair. `legacyAdminAlias = true` selects the compatibility path.
- `GenericOpenListService.call<T>` accepts either a catalog endpoint or a canonical/alias path and
  parses the standard `{code,message,data}` envelope using the existing `OpenListHttpClient`
  behavior.
- `GenericOpenListService.raw` returns an OkHttp `Response` for redirects, protocol-specific
  payloads, stream bodies, and future routes. The caller must close the response.

`OpenListHttpClient.resolveUrl` preserves a configured server sub-path, so a deployment such as
`https://host.example/openlist` correctly resolves the catalog path to
`https://host.example/openlist/api/...`.

## Non-`/api` server transports

These entry points are not counted in the canonical 199 because they do not use the OpenList JSON
REST envelope. `NonApiTransportCatalog` documents the protocol surfaces used by the app; it is not
a claim that the raw helper implements every protocol state machine or authentication scheme.

| Transport | Registered patterns | Handling |
|---|---|---|
| health | `/ping` | raw request |
| file download/proxy | `/d/*path`, `/p/*path` | streaming GET/HEAD, signed URL |
| archive download/proxy/extract | `/ad/*path`, `/ap/*path`, `/ae/*path` | streaming GET/HEAD, archive sign |
| sharing download | `/sd/:sid`, `/sd/:sid/*path` | streaming GET/HEAD |
| sharing archive | `/sad/:sid`, `/sad/:sid/*path` | streaming GET/HEAD |
| WebDAV | `/dav`, `/dav/*path` | dedicated WebDAV verbs and Basic/Bearer authentication |
| S3 | `/s3/*path`; `/*path` on a configured dedicated S3 listener | raw S3 transport; callers must supply AWS signing |
| MCP | `/mcp` | GET/POST/DELETE and OpenList administrator authentication |

Use the catalog-aware `GenericOpenListService.raw(transport, concretePath, method, ...)` overload.
It deliberately preserves caller-supplied `Authorization` for WebDAV and S3 instead of replacing it
with the JSON API token. It does not generate AWS Signature V4 or implement a complete S3/WebDAV
client. MCP uses the configured OpenList administrator token. Direct uploads to a
third-party storage host are a separate transport concern; provider headers must never receive the
OpenList JWT automatically. A dedicated S3 listener needs a service instance configured with that
listener's base URL rather than the main OpenList HTTP URL.

## Verification

Unit tests enforce:

- an exact method-and-path match against the pinned v4.2.5 upstream route snapshot;
- exactly 199 canonical paths, unique paths, and unique IDs;
- the upstream method and access-policy distributions;
- the complete 7 × 12 task matrix and 84 unique administrator aliases;
- representative auth, filesystem, multipart, sharing, admin, and task paths;
- all 53 `AdminRoute` values;
- non-`/api` transport families;
- actual method, base-subpath, query, body, auth header, alias, envelope, and raw-response behavior
  with MockWebServer.

When upgrading OpenList, update the catalog from the two upstream route files and change the exact
counts only after comparing the new server snapshot. Do not add aliases to the canonical count.
