# Android AI Screen Buddy: Architecture and Agent Build Spec

Placeholder app name: Buddy.

## Part 1: Architecture

### 1.1 What v1 is

A floating bubble that sits on top of any app. The user taps or holds it, the app grabs one screenshot of whatever is on screen, opens a Gemini Live voice session, and the user just talks. Gemini answers out loud and can point at things on the screen (ring, highlight box, arrow, label) by calling tools. The drawing is rendered by Jetpack Compose in a transparent overlay.

**Explicitly out of scope for v1:** AccessibilityService, clicking or typing in other apps, drawings that follow scrolling, background agents, wake word, iOS. Do not build these.

### 1.2 System diagram

```
+--------------------------- Android app (one process) ---------------------------+
|                                                                                 |
|  Compose UI                    OverlayService (foreground service,              |
|  - Onboarding                  types: mediaProjection | microphone)             |
|  - Settings                    |                                                |
|                                +- BubbleWindow    touchable ComposeView         |
|                                +- DrawingWindow   full-screen, NOT touchable    |
|                                +- ScreenCaptureManager -> JPEG frame            |
|                                +- MicRecorder  (16 kHz PCM16 mono)  --+         |
|                                +- PcmPlayer    (24 kHz PCM16 mono) <-+         |
|                                +- LiveSessionManager <-----------------+        |
|                                       |                                         |
|                                       +- ToolRouter -> DrawingState (StateFlow) |
+------------------------------------------+--------------------------------------+
                                           | Firebase AI Logic SDK (Live API, WebSocket)
                                     Gemini Live API
```

Later (not v1): a small backend (Cloud Run or Cloud Functions) that runs long agent jobs and reports status back through Firestore or FCM.

### 1.3 Tech stack

- Kotlin 2.x, Jetpack Compose (latest stable BOM), Material 3
- MVVM with StateFlow, Hilt for DI, Coroutines and Flow
- Room for conversation history, DataStore for settings
- Firebase AI Logic SDK (`firebase-ai`) for the Gemini Live API, plus Firebase App Check. The API key never ships in the APK.
- minSdk 29, targetSdk and compileSdk at the latest stable
- Version catalog (`libs.versions.toml`), single Gradle module with clean packages (split later if needed)
- Timber for logging

The Live API model name and the exact Firebase class names change often. The agent must check the current Firebase AI Logic docs before coding against them and must not guess from memory.

### 1.4 Package structure

```
com.yourname.buddy
  di/               Hilt modules
  ui/
    onboarding/     permission steps (overlay, mic, notifications, screen capture)
    settings/       voice, language, pause, clear history
  overlay/
    OverlayService.kt       foreground service, owns windows and lifecycle
    OverlayLifecycleOwner.kt  LifecycleOwner + SavedStateRegistryOwner + ViewModelStoreOwner
    BubbleWindow.kt         tap / press-and-hold bubble
    DrawingWindow.kt        full-screen drawing layer
    draw/                   Ring, HighlightBox, Arrow, Label composables
    DrawingState.kt         sealed class DrawOp + StateFlow<List<DrawOp>>
  capture/
    ProjectionHolder.kt     holds MediaProjection result and token
    ScreenCaptureManager.kt VirtualDisplay + ImageReader -> Bitmap
    FrameEncoder.kt         downscale + JPEG
  audio/
    MicRecorder.kt          AudioRecord, VOICE_COMMUNICATION source, AEC on
    PcmPlayer.kt            AudioTrack streaming playback, interruptible
  live/
    LiveSessionManager.kt   connect, send audio and frame, receive audio and tool calls
    ToolRouter.kt           maps function calls to actions, returns results
    SessionState.kt         Idle | Connecting | Listening | Thinking | Speaking | Error
    SystemPrompt.kt
  data/
    SettingsRepository.kt   DataStore
    db/                     Room entities and DAO (messages only, never images)
```

> Project note: existing applicationId / namespace is `com.screen.buddy`. Keep that package root instead of `com.yourname.buddy`.

### 1.5 Core flows

#### One-time setup (onboarding)

1. Overlay permission (`Settings.ACTION_MANAGE_OVERLAY_PERMISSION`)
2. `RECORD_AUDIO` and `POST_NOTIFICATIONS`
3. Start `OverlayService` as a foreground service with a persistent notification (Pause and Quit actions)
4. Screen capture consent (`MediaProjectionManager.createScreenCaptureIntent()`), result handed to `ProjectionHolder`

#### Summon flow (the main loop)

1. The user taps or holds the bubble. State goes to Connecting.
2. `ScreenCaptureManager` grabs exactly one frame, `FrameEncoder` downscales it (longest side around 1280 px) and encodes JPEG at quality 70. The frame lives in memory only.
3. `LiveSessionManager` opens the Live session (or reuses a warm one), sends the frame as an image, and starts streaming mic audio. State goes to Listening.
4. Gemini replies with audio chunks, which `PcmPlayer` plays (Speaking), and with tool calls, which `ToolRouter` executes against `DrawingState`. Each tool call must get a response sent back to the session.
5. If the user speaks while Gemini is talking, stop playback immediately (barge-in) and clear the queued audio.
6. After the turn completes, drawings stay for about 8 seconds, then fade. A new summon or a tap on the bubble clears them immediately. Session returns to Idle and the mic stops.

In push-to-talk mode, holding the bubble records and releasing it ends the user's turn. Tap-to-toggle is the alternative; make it a setting, default to hold.

#### Coordinate system

- The model returns coordinates normalized to 0 to 1000, origin top-left, relative to the screenshot it was shown.
- Screen pixels = `norm / 1000 * dimension` where the dimension comes from `WindowManager.currentWindowMetrics.bounds` (the full physical screen, including status and navigation bars).
- The `DrawingWindow` must cover the full physical screen: `FLAG_LAYOUT_NO_LIMITS`, `layoutInDisplayCutoutMode = ALWAYS`, width/height = `MATCH_PARENT`, so the overlay's pixel space equals the screenshot's pixel space.
- Handle rotation: if the orientation changed between capture and drawing, drop the drawing instead of drawing it in the wrong place.

#### Tools exposed to Gemini (function declarations)

- `point_at(x, y, label?)` draws a ring and optional label
- `highlight_box(ymin, xmin, ymax, xmax, label?)` draws a rounded rectangle
- `draw_arrow(from_x, from_y, to_x, to_y, label?)`
- `clear_drawings()`

All coordinates are integers 0 to 1000. Each returns `{ "ok": true }` or an error string the model can react to.

#### System prompt for the model (starting version)

A friendly teacher sitting next to the user. Answer out loud in one to three short sentences unless asked for more. A screenshot of the user's screen is attached at the start of each turn; use it. When you refer to something visible, call `point_at` or `highlight_box` while you speak. Coordinates are 0 to 1000 from the top-left of the screenshot. Reply in the language the user speaks. You cannot tap, type, or control the phone; never claim you did.

### 1.6 Android constraints the agent must respect

- Foreground service types: declare `mediaProjection|microphone` in the manifest, with `FOREGROUND_SERVICE_MEDIA_PROJECTION` and `FOREGROUND_SERVICE_MICROPHONE`. On Android 14 and up the service must be started with the matching types and, for MediaProjection, started before `getMediaProjection()` is called.
- MediaProjection token: on Android 14 and up it cannot be reused after it stops, and the user can choose single-app sharing. Register a `MediaProjection.Callback`; when `onStop` fires, clear state and show a "tap to re-enable screen access" notification. Do not crash.
- Starting the mic from the background: a visible overlay window counts as a visible state, so starting the foreground service from the bubble works. Do not start microphone capture from anywhere else.
- `FLAG_SECURE` apps (banking, some video apps): the frame comes back black. Detect it (mean luminance near zero over a sampled grid), skip sending it, and have the assistant say it cannot see that app.
- Compose inside a Service window: the ComposeView needs `ViewTreeLifecycleOwner`, `ViewTreeViewModelStoreOwner` and `ViewTreeSavedStateRegistryOwner` set manually. `OverlayLifecycleOwner` implements all three and moves through CREATED -> RESUMED when the window is added and DESTROYED when removed.
- Two windows, not one: the drawing window uses `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE` so touches pass through to the app underneath. The bubble is a separate small touchable window.
- Echo: use `AudioSource.VOICE_COMMUNICATION` and enable `AcousticEchoCanceler` and `NoiseSuppressor` when available, otherwise Gemini hears itself and interrupts.
- Audio formats: input 16-bit PCM, 16 kHz, mono, little-endian. Output 24 kHz PCM16 mono. Verify against the current Live API docs.
- Battery and OEM killers: keep the service light when idle (no mic, no capture, session closed). Detect aggressive OEMs and show a one-time hint to disable battery optimization.

### 1.7 Privacy and security

- Screen is captured only when the user summons the assistant. No periodic capture.
- Frames are never written to disk and never go to Room. Store text transcripts only, and give users a clear-history button.
- Persistent notification and an in-app Pause switch that closes the session and releases projection.
- No API keys in the repo. Firebase App Check on. `google-services.json` is gitignored; include a documented placeholder.
- Add an on-screen indicator (the bubble changes state color) whenever the mic or a session is active.

### 1.8 Later: connectors and agents (after v1 works)

- Simple connectors (Calendar or Gmail read-only) can run as on-device tool calls using Credential Manager and the Google Authorization API with narrow scopes.
- Long-running agent jobs run on a backend, never on the phone. The phone sends a task, the backend runs Gemini with tool calling, and status comes back through Firestore listeners or FCM. Add a `start_agent_task(description)` tool only then.

## Part 2: Prompt for the coding agent

You are a senior Android engineer. Build the app described in Part 1 of this document, in Kotlin with Jetpack Compose, MVVM, Hilt, Coroutines/Flow, following the package structure above. Work milestone by milestone, in order. Do not start a milestone until the previous one meets its acceptance criteria.

### Rules

1. Follow Part 1. If something in it is unclear or conflicts with a current Android or Firebase API, tell me and propose the smallest fix; do not silently redesign.
2. Before writing code against Firebase AI Logic (Live API), Android MediaProjection, or foreground service types, read the current official documentation. Do not rely on memory for class names, model IDs, or permission names.
3. Do not build anything listed as out of scope in 1.1. No AccessibilityService, no extra features, no speculative abstractions.
4. Keep functions small, name things clearly, and comment only where the reason is not obvious (coordinate math, lifecycle hacks, Android version workarounds).
5. Every milestone ends with: the project builds, `./gradlew lint` has no new errors, any unit tests for that milestone pass, and a short summary of what changed and how I should test it on a device.
6. Commit once per milestone with a clear message.
7. Never hardcode secrets. Use the placeholder `google-services.json` approach from 1.7.
8. Test target: a real device on Android 14 or newer. Emulators are unreliable for overlay, MediaProjection and audio.

### Milestones

#### M0: Project skeleton

- New project, version catalog, Hilt, Compose, Timber, Firebase BoM with `firebase-ai` and App Check, the package structure from 1.4 with empty classes.
- Manifest with all permissions and the foreground service declaration.
- Done when: the app launches to a placeholder screen and builds clean.

#### M1: Onboarding and permissions

- Compose onboarding screens for overlay, mic, notifications, and screen capture consent. Each step shows its status and can be re-run from Settings.
- `ProjectionHolder` stores the consent result and handles `MediaProjection.Callback.onStop`.
- Done when: all four permissions can be granted from a fresh install, and revoking one is detected and shown.

#### M2: Overlay bubble

- `OverlayService` as a foreground service with the persistent notification and Pause and Quit actions.
- `OverlayLifecycleOwner` and the draggable `BubbleWindow` (Compose) that snaps to a screen edge and shows `SessionState` as color or animation.
- Press-and-hold and tap events are exposed as a Flow of gestures.
- Done when: the bubble floats over other apps, can be dragged, and survives rotation and app switching.

#### M3: Screen capture

- `ScreenCaptureManager` grabs one frame on demand through VirtualDisplay and ImageReader, releasing resources afterwards. `FrameEncoder` downscales and encodes JPEG.
- Black-frame detection for FLAG_SECURE content.
- A debug-only screen that shows the last captured frame so I can verify it.
- Done when: tapping the bubble in any normal app produces a correct, non-distorted frame within about 300 ms, and a banking app produces the "cannot see this app" path.

#### M4: Drawing overlay

- `DrawingWindow` (full physical screen, non-touchable), `DrawOp` sealed class, and Compose renderers for Ring, HighlightBox, Arrow, Label with a simple draw-in animation and a fade-out.
- A debug menu that fires test DrawOps using the 0 to 1000 coordinate system.
- Done when: a test ring at (500, 500) lands in the exact center of the screen in both orientations, including on devices with a camera cutout, and a test box around a known on-screen element lines up with it.

#### M5: Live voice session

- `MicRecorder`, `PcmPlayer` (streaming, with immediate stop for barge-in), and `LiveSessionManager` using Firebase AI Logic Live API.
- Wire the summon flow from 1.5: capture, send the frame, stream audio, play replies, show states.
- Tools `point_at`, `highlight_box`, `draw_arrow`, `clear_drawings` declared and routed through `ToolRouter` to `DrawingState`, with a response returned for every call.
- System prompt in `SystemPrompt.kt` as given in 1.5.
- Graceful errors: no network, session dropped, quota hit, projection stopped. Each shows a short notification or toast and returns to Idle.
- Done when: I can hold the bubble over any app, ask "what is this?" or "where do I tap to do X?", hear a spoken answer, and see the ring or box land on the right element at least 8 times out of 10. Interrupting mid-answer stops the audio within about 300 ms.

#### M6: Settings, history and polish

- Settings: push-to-talk vs tap-to-toggle, voice choice, language hint, drawing duration, Pause, clear history.
- Room stores text transcripts only. A simple history screen.
- Battery hint for aggressive OEMs, idle state verified (no mic, no capture, no open session).
- Done when: the service sits idle for 30 minutes with no mic indicator and negligible battery use, and every setting persists across restarts.

### Report format after each milestone

1. What you built (3 to 5 lines)
2. Files added or changed
3. How to test on a device, step by step
4. Known issues or decisions you made that I should review
5. What you need from me before the next milestone

### Start now

Begin with M0. Before writing code, list the exact dependency versions and the Firebase AI Logic classes you plan to use, based on the current official documentation, and wait for my OK.
