# Nezh — Project Instructions

## 1. Project overview

Nezh is an Android local music player focused on owning and controlling
the user's music library.

The goal is to build a reliable, offline-first music player with a
predictable library, excellent queue management, and no dependence on
streaming services.

The project is currently an early-stage Android application scaffold.
Do not assume that planned features or architectural decisions have
already been implemented.

### Product principles

- Local music playback is the primary purpose.
- Offline-first: playback must not depend on an internet connection.
- The user controls which folders are scanned.
- Avoid indexing unrelated audio such as messaging-app voice notes
  unless the user explicitly chooses those locations.
- Queue management is a core feature, not an afterthought.
- Support standard Android media controls and Bluetooth controls.
- Keep the application maintainable, stable, and reasonably lightweight.
- Do not introduce accounts, cloud services, streaming, analytics,
  or advertisements without explicit approval.

## 2. Current technical baseline

These are the technologies currently present in the project:

- Language: Kotlin
- UI: Jetpack Compose
- UI components: Material 3
- Build system: Gradle
- Build scripts: Kotlin DSL
- Dependency catalog: `gradle/libs.versions.toml`
- Android application module: `:app`
- Namespace: `com.hachicorp.nezh`
- Application ID: `com.hachicorp.nezh`
- Minimum SDK: API 23
- Compile SDK: API 37
- Target SDK: API 37

The project currently contains the default Compose application scaffold.

Do not assume that libraries such as Media3, ExoPlayer, Room, Hilt,
Koin, or Navigation Compose are installed. Inspect the Gradle
configuration and version catalog before using any dependency.

## 3. Roles and decision-making

The project owner makes product and architecture decisions.

Your role is to implement approved tasks, investigate technical
problems, write tests, and report findings.

You may recommend architectural changes, libraries, or improvements,
but you must explain the reason, trade-offs, and impact before
implementing them.

### Approval required

Do not independently:

- Change the application architecture.
- Add or replace major dependencies.
- Change minSdk, targetSdk, compileSdk, namespace, or application ID.
- Introduce new Gradle plugins.
- Add new modules or architectural layers.
- Change database schemas or persistence strategies.
- Introduce network services, analytics, or external APIs.
- Perform broad refactors unrelated to the assigned task.
- Remove existing functionality or files without justification.

When a task requires one of these changes, stop and ask for approval.

Small implementation details within an approved task may be decided
independently, provided they do not change the architecture or product
behavior.

## 4. Engineering principles

- Prefer simple, readable, maintainable Kotlin.
- Follow official Android and Kotlin conventions.
- Prefer immutable data structures where practical.
- Keep UI code separate from business logic.
- Avoid unnecessary abstractions and premature optimization.
- Do not duplicate logic.
- Handle errors explicitly; do not silently swallow exceptions.
- Avoid blocking the main thread.
- Use coroutines for asynchronous work when appropriate.
- Respect Android lifecycle and background execution restrictions.
- Request only the permissions required by a feature.
- Preserve user data and avoid destructive operations.
- Consider API 23 compatibility when implementing Android features.

Do not create an elaborate architecture before the actual requirements
justify it.

## 5. Android application guidelines

- Use Jetpack Compose for new UI.
- Keep composables focused and reusable when appropriate.
- Do not put database, file-scanning, or playback logic directly
  inside composables.
- Keep playback behavior independent from screen lifecycle.
- Treat background playback and media-session integration as
  dedicated concerns.
- Prefer Android Storage Access Framework or other approved
  storage APIs for user-controlled file access.
- Never scan the entire device storage without explicit product
  approval.
- Do not assume that filesystem paths are always directly accessible
  on modern Android versions.

## 6. Music player requirements and priorities

The following are product goals, not necessarily implemented features.

Core priorities:

1. Reliable local audio playback.
2. User-controlled music folder selection.
3. Accurate library organization and metadata.
4. Persistent playback queue.
5. Add to queue and play next actions.
6. Queue reordering and item removal.
7. Background playback and Android media controls.
8. Bluetooth headset and car media controls.

Potential future capabilities include playlists, favorites, listening
history, shuffle, repeat, sleep timer, and iTunes library import.

Do not implement future capabilities unless they are part of the
assigned task.

## 7. iTunes compatibility

iTunes library compatibility is a project goal, but it is not a
current implementation requirement unless explicitly assigned.

Potential integration includes importing:

- Track metadata.
- Playlists.
- Ratings and play counts.
- Persistent track identifiers.
- Other useful library information.

Keep iTunes integration conceptually separate from the playback
engine. Nezh must remain fully usable without iTunes.

Do not assume that importing an iTunes XML file transfers the
associated audio files. Metadata import and audio-file transfer are
separate concerns.

## 8. Working procedure

Before making changes:

1. Read the relevant source files.
2. Inspect existing project conventions.
3. Check the Gradle version catalog before adding dependencies.
4. Understand the requested behavior.
5. Identify files that need to change.
6. Mention important assumptions or risks.

While implementing:

- Keep changes scoped to the assigned task.
- Avoid unrelated formatting or refactoring.
- Do not overwrite user work.
- Do not modify generated files manually.
- Do not edit Gradle wrapper files unless required and approved.

After implementing:

1. Summarize what changed.
2. Explain relevant implementation decisions.
3. List files created, modified, or removed.
4. Run the most relevant tests.
5. Run the debug build when appropriate.
6. Report test/build results honestly.
7. Mention anything that could not be verified.

If a build or test fails, investigate the cause. Do not hide failures
or claim success without evidence.

## 9. Git discipline

- Do not commit changes unless explicitly requested.
- Do not reset, rebase, force-push, or rewrite Git history without
  explicit approval.
- Do not discard uncommitted user changes.
- Do not stage unrelated files.
- Keep commits focused and descriptive when asked to commit.

## 10. Communication

Be concise and technical.

Before making a significant decision, explain:

- What needs to change.
- Why it needs to change.
- Alternatives considered.
- Risks and trade-offs.

If requirements are ambiguous, ask instead of inventing behavior.

The project owner has final authority over product scope and
architectural decisions.