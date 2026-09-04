# Architecture

## Layers

```text
Compose Material 3 UI
  ├─ file/search/settings/account/gallery routes
  ├─ MediaController ──> PlaybackService / MediaSession / enhanced Media3
  └─ WorkManager ──────> resumable upload + local download coordinators

Repositories
  ├─ sibling media sequence builder
  ├─ file and account repository
  ├─ upload checkpoint store
  └─ unified cache coordinator

Transports
  ├─ typed OpenList API
  ├─ generic/admin/task API
  └─ dedicated uncredentialed media/download client
```

The project intentionally stays in one Android module while features are small. Packages are the ownership boundary; a multi-module split is only justified when build isolation or independently reusable code appears.

## API contract

The source of truth is the v4.2.5 server router and handler models. The public OpenAPI export documents only a subset and describes standard Bearer auth even though the server expects the token value directly.

Core user flows use typed request and response models. Driver additions, `fs/other`, settings, message bridges, WebAuthn payloads and future endpoints remain `JsonElement` at the transport boundary. The endpoint catalog makes route coverage machine-checkable without creating hundreds of repetitive Retrofit methods; task routes are the product of two roots, seven task kinds and twelve actions.

Authenticated JSON requests recover HTTP or envelope code 401 by logging in again through `/api/auth/login/hash` and replaying the original request once. Concurrent failures share the renewed token. Renewal never changes the selected account, and only commits while the original account and login generation still match. Login/logout, explicit caller Authorization headers and non-repeatable bodies are excluded from automatic replay. Missing saved credentials, rejected credentials or a new two-factor challenge return the user to login; network failures keep the session available for retry. Accounts saved before this feature need one successful manual login to enable renewal.

## Media grouping

Opening an audio, video or image builds an immutable snapshot from the selected item's real parent directory. Only direct siblings with the same effective media kind are included; extension detection corrects known misclassification for `.m3u8`, `.mkv`, `.wma`, and `.wmv`. The current item appears exactly once. Matching `video.srt`, `video.language.ass`, VTT, SSA, and TTML sidecars are attached through opaque process-local references. Every audio and video queue, including WMA and WMV, follows the same service-backed Media3 path so queue state, background playback, notifications and renderer fallback remain consistent across item transitions.

The playback service uses the pinned FongMi Media3 ASF extractor and FFmpeg audio/video extension renderers. Its `DecodeTrackSelector` selects FFmpeg for both tracks of a WMV item and for the audio track of a WMA item; ordinary formats remain hardware-first. The preference is reapplied on every queue transition, so switching between legacy Windows Media and common formats does not require a second player or a UI-thread native teardown path.

Media cache identities use server/account, canonical path, the strongest available content revision and representation. Rotating `raw_url`, thumbnails, signatures, directory passwords and tokens are deliberately excluded.

MediaSession items expose a process-local random identifier, a safe basename and a stable content hash—not a remote path, signed URL or credential. HLS manifests and segments bypass disk caching because a single progressive-file key cannot safely identify all of their independently addressed resources.

## Cache policy

Disk content is trimmed when any configured limit is violated:

1. incomplete/orphaned writes;
2. entries whose sliding idle TTL expired;
3. least recently used entries until both byte and count limits fit.

An active lease prevents the currently played or decoded resource from being removed. Temporary writes use `.part` files and become visible through an atomic rename. A single audio/video asset counts as one logical item even if Media3 stores multiple byte ranges.

## Upload recovery

Before the first upload request, the client copies the selected document into an app-private immutable staging file and calculates SHA-256. The checkpoint records that staged identity, destination, server upload id, effective chunk size and phase. The server's `received` ranges are authoritative after a restart. Chunks are idempotent; transient failures use bounded backoff, while malformed/missing sources and authentication changes fail permanently. A server-lost session is recreated. Empty files and servers without multipart support use `/api/fs/put`.

## Local downloads

The document picker creates the destination and grants a persistable write capability; the app never requests broad storage access. A worker resolves a fresh `raw_url` immediately before transfer, strips OpenList credentials from object-storage requests, writes with truncate semantics, validates the expected byte count, and clears partial output on cancellation or permanent failure. Each job is bound one-way to the account identity and login generation that created it so account switching cannot retarget an in-flight download. Automatic token renewal preserves that binding; explicit login and logout replace or clear it.

## Security boundaries

- Passwords are converted to OpenList's static SHA-256 form before `/auth/login/hash`.
- Successful logins retain only the password hash encrypted with AndroidKeyStore AES/GCM and authenticated against the account ID. Raw passwords and OTP codes are never persisted; logout, account removal and credential identity changes clear the retained hash.
- Protected-directory passwords live only in an in-memory store keyed by server, account and nearest ancestor path; they are cleared on account changes and logout.
- Tokens are never logged or added to third-party download hosts.
- Cache keys and persisted queues never contain credentials or temporary URLs.
- Cleartext HTTP is rejected unless the account explicitly enables it. The login flow defaults new OpenList connections to HTTP/5244 as requested, while the UI warns that public deployments should use HTTPS.
- Background uploads capture one authenticated identity and revalidate it before each physical request, including redirect hops.
- Exported media sessions accept only this UID or Android/Media3-trusted controllers; external trusted controllers receive transport controls but cannot replace the queue.
- Android 17 local-network permission is requested only when a connection needs it and denial remains recoverable through system settings.
