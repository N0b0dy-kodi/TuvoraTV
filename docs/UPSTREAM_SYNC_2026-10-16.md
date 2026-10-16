# Upstream sync — 2026-10-16

This merge starts from Tuvora `org/main` and preserves the released fork history. Existing feature branches and uncommitted primary-checkout work are untouched.

## Integration decisions

- Keep switchable Tuvora backend identity, the single-flight Supabase client, and the single auth refresher. Port the upstream backend rate-limit coordinator and realtime reconnect socket through that provider.
- Preserve setup-code deep links alongside upstream party links, Tuvora branding, build workflows, credentials privacy, feature gates, and the architecture baseline.
- Watch Party remains disabled: neither the Tuvora backend nor the public `NuvioMedia/self-host` snapshot fetched on 2026-10-16 contains `watch_party_*` RPCs or migrations. No backend schema or dashboard/account changes are needed while that capability remains disabled. Enabling it requires a backend implementation and TV playback integration.
- Keep rejecting custom-poster patterns without placeholders. Upstream's token-cache optimization accidentally changed that existing contract; TV already rejects them and the KMP twins now agree.

## Source

`origin/dev` at `6bda667f8c6756d98b06e1404081fad91f09a134`; 200 upstream commits absent from the pre-merge Tuvora main.

## TV player pin

Follow `NuvioMobile/docs/UPSTREAM_MERGE_PLAYBOOK.md`, Phase 5: existing `ui/screens/player` files stay fork-pinned, including HTTP/2 lanes, memory budgets, live/catch-up behavior, audio recovery, and the observed mpv shadow. The independent upstream subtitle language/format/cue utilities and playback TCP no-delay socket configuration are imported. New sidecar, automatic subtitle sync, mpv-event, playback-party, and post-play recommendation controllers are not imported; tests requiring those unported implementations are omitted. This intentionally limits the TV playback portion of the sync.

Upstream addon search-session caching excludes Tuvora's own-source searches. Direct IPTV/Stalker URLs still resolve freshly for each play, and matching searches stay scoped to their collectors. Trailer decoder checks are adapted to the fork's Media3 context-taking API. Tracking settings retain missing-credential provider gates within the upstream reusable content shell.

The fork keeps its matched Media3 1.11 allocator and data-source binaries. The upstream data-source AAR lacks `DataSourceBitmapLoader.Builder`, which the fork's MediaSession requires; importing it broke the existing clean-session test. The upstream HTTP status transport change is therefore excluded with the player pin, and the fork's existing status-message assertion is retained.

The new Trakt delta-refresh path propagates cancellation instead of converting it to an I/O failure, and its payload/forced-watched-list decisions are extracted into a pure policy with network-free tests. Mobile/iOS and Desktop already rethrow cancellation in their separate Trakt refresh implementations; those files remain unchanged.

## Validation

- `:app:compileFullDebugKotlin` passed.
- Focused `:app:testFullDebugUnitTest`: 867 tests, 4 skipped, 0 failures, 0 errors. Covers Media3 binaries, networking, party, player rules, sync/credential policies, debrid/posters/tracking, repositories including Trakt refresh cancellation, trailers, settings categories, subtitle utilities, and the clean MediaSession lifecycle.
- Initial broad runs stalled in unchanged `StalkerMinistraSeriesTest` and `AndroidMpvBackendTest` cases; stopped those runs. The successful focused run includes the corrected Trakt cancellation test and the MediaSession test that caught the incompatible upstream AAR.
- Resource XML duplicate checks, conflict-marker checks, and whitespace checks passed.
