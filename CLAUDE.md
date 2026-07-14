# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Taper: an on-device memory and reliability SDK for Android apps that embed AI agents (LLM API calls, tool-use loops, long context histories). Kotlin, minSdk 24, three independent components with no cloud dependency — published to JitPack as `com.github.rsngpt:taper`. Full problem statement, architecture, and measured benchmark results are in [README.md](README.md); don't duplicate that content here, read it when context is needed.

## Commands

All commands run from the repo root via the wrapper (no global Gradle install required).

```bash
./gradlew testDebugUnitTest                              # all unit tests (JVM/Robolectric, no device needed)
./gradlew :taper:testDebugUnitTest --tests 'dev.taper.parser.*'   # one package
./gradlew :taper:testDebugUnitTest --tests 'dev.taper.queue.SyncQueueTest.enqueue persists and preserves order'  # one test
./gradlew :taper:lintDebug :benchmark:lintDebug           # lint (also run by CI)
./gradlew :taper:assembleDebug :benchmark:assembleDebug   # compile both modules
./gradlew :taper:publishToMavenLocal                      # local install as dev.taper:taper:0.1.0
./gradlew :taper:dokkaGenerate                             # regenerate API docs into taper/build/dokka/html (copy into docs/api/ manually — see below)
./run_benchmark.sh                                         # instrumented memory benchmark on a connected device/emulator → results table (see below)
```

CI (`.github/workflows/ci.yml`) runs `testDebugUnitTest` + lint on every push/PR on `ubuntu-latest`, JDK 17. It deliberately does **not** run the instrumented benchmark — that needs a device and its numbers are only meaningful on a profile you control.

## Architecture

Three components, each usable standalone, that compose into an ingest → classify → sync loop (see the README's mermaid diagram for the full picture):

1. **Streaming parser** — [`taper/src/main/kotlin/dev/taper/parser/`](taper/src/main/kotlin/dev/taper/parser/). `TaperParser.parse(inputStream, fieldsOfInterest)` walks Moshi's streaming `JsonReader` and extracts only declared field paths (`FieldPath.kt` compiles path expressions like `messages[].tool_calls[].name`, `metadata.*`). Unmatched subtrees are skipped token-by-token — never materialized as a DOM — which is the whole point: cost scales with what you extract, not document size. `ParserEngine` is a deliberately unimplemented v2 extension point (native/ashmem/FlatBuffers engines would plug in here without changing `TaperParser`'s public API — do not implement a second engine unless explicitly asked).

2. **Exception classifier** — [`taper/src/main/kotlin/dev/taper/classify/ExceptionClassifier.kt`](taper/src/main/kotlin/dev/taper/classify/ExceptionClassifier.kt). A chain of `ClassificationRule`s (error-body shape → HTTP status → exception type; first non-null answer wins) categorizes a failure as `SEMANTIC` (never retry — malformed request, invalid tool call, content-policy rejection) or `TRANSIENT` (retry — timeout, 5xx, DNS). Non-obvious cases are intentional, not bugs: 408/425/429 are transient despite being 4xx; 501 is semantic despite being 5xx; unknown failures default to `TRANSIENT` because that failure mode is bounded (capped retries) while the reverse silently drops user data. This is rules-based by design (see README § Tradeoffs) — an ML classifier is explicitly out of scope for v1; don't add one unless asked.

3. **Offline sync queue** — [`taper/src/main/kotlin/dev/taper/queue/`](taper/src/main/kotlin/dev/taper/queue/). Room-backed (`QueueEntities.kt`), durable across process death. `SyncQueue.drain(syncer)` groups all pending rows by `conversationId` and hands the syncer **one oldest-first batch per conversation** — the coalescing that avoids a request flood on reconnect — deleting rows only after the syncer succeeds (at-least-once delivery). Failures re-enter the `ExceptionClassifier`: `SEMANTIC` → dead-letter immediately, `TRANSIENT` → stays pending up to `maxAttempts` then dead-letters. `ConnectivityDrainTrigger.kt` wires `drain` to `ConnectivityManager.registerDefaultNetworkCallback` (API 24+, which is why minSdk is 24 — see README § minSdk justification).

Module layout: `taper/` is the published library (all three components + all unit tests, including Robolectric queue tests and a constrained-heap test that runs DOM parsing in a real child JVM capped at 32MB to prove the OOM claim rather than asserting it). `benchmark/` is a separate Android app module hosting the instrumented memory benchmark (`benchmark/src/androidTest/`) — it depends on `taper/` but is not published.

## Working in this codebase

- **No fabricated numbers, anywhere** — code comments, commit messages, README, docs site. Every performance claim in this repo was produced by `./run_benchmark.sh` or the `ConstrainedHeapTest` unit test. If you change parsing/queue internals in a way that could affect the benchmark tables in [README.md](README.md) or [docs/index.html](docs/index.html), rerun the harness and update all of them with the *actual* new numbers — do not hand-edit tables. There are two published device profiles (low-RAM emulator + Redmi Note 7 Pro); the raw CSVs behind every published number are committed under `benchmark/results/` — add the new run's CSV there too.
- **Benchmarking on real devices, lessons already paid for**: (1) MIUI/Xiaomi phones block the Test Orchestrator's permission-granting (`-g`) install even when plain `adb install` works — the workaround is one `am instrument` invocation per test from the host (same fresh-process isolation; see the methodology note in README § Real hardware). (2) When filtering with `-e tests_regex` through `adb shell`, the device shell eats backslashes, silently turning `measure\[100KB-...\]` into a character class that matches zero tests and reports `OK (0 tests)` — use `.` wildcards instead of escaped brackets, and always treat `OK (0 tests)` as a failure, never a pass. (3) Results are collected from logcat tag `TaperBenchmark` because AGP uninstalls the test APK (and its files dir) after connected tests. (4) Expect wall-times to inflate up to ~2.5× on back-to-back device runs (thermal throttling); heap medians at ≥5MB payloads are stable.
- **`docs/`** is the GitHub Pages site (landing page `docs/index.html` + Dokka output `docs/api/`, committed rather than generated by CI). If you touch public API KDoc, regenerate `docs/api/` with `./gradlew :taper:dokkaGenerate` and copy `taper/build/dokka/html/*` into `docs/api/` before committing.
- **Commit messages omit AI co-author trailers** — this was an explicit, deliberate repo decision (history was rewritten once to strip `Co-Authored-By` lines); don't reintroduce them.
- Respect the three "explicitly out of scope for v1" boundaries called out in the README (FlatBuffers/ashmem/JNI, ML classification, cloud/compression gateway) — they have extension-point interfaces (`ParserEngine`, the `ClassificationRule` chain) precisely so v2 work doesn't require breaking the public API. Don't implement them preemptively.
