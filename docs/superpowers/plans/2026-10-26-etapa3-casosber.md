# Etapa 3 — časosběr: implementační plán

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Režim Časosběr pořídí snímek každých N sekund a složí je do MP4 s 30 fps. Běží i se zhasnutou obrazovkou. Vydat jako 0.4.0.

**Architecture:** CameraX `Recorder` neumí snímat pomaleji, než přehrává, proto má časosběr vlastní kodér. Session naváže `Preview` + `ImageAnalysis` (výstup RGBA, `STRATEGY_KEEP_ONLY_LATEST`). Analyzer pustí dál jen jeden snímek za interval. `TimelapseEncoder` (MediaCodec H.264 s ByteBuffer vstupem YUV420 + MediaMuxer) ho převede na I420 a zapíše s časovou značkou `index × 1/30 s`. Výsledek jde do cache a po dokončení do MediaStore přes `MediaStoreWriter.saveVideoFile`. Běh drží stávající `RecordingService` (typ jen camera) a `SessionState.recording`.

**Tech Stack:** jako etapa 2 (MediaCodec a MediaMuxer jsou součást Androidu).

**Spec:** `docs/superpowers/specs/2026-10-26-full-camera-design.md`

## Global Constraints

- Intervaly 0,5 / 1 / 2 / 5 / 10 s (spec), výstup H.264 MP4 30 fps, jméno `PXL_yyyyMMdd_HHmmss.mp4` (UTC), uložení do `DCIM/Camera`.
- Pokračuje se zhasnutou obrazovkou a na pozadí (spec, rozhodnutí uživatele). FGS typ jen `camera` (bez zvuku), na API 29 bez typu (`foregroundServiceTypes(false)`).
- Při přerušení se uloží, co se natočilo (anti-goal „ztracený snímek“). Prázdný časosběr (0 snímků) se neukládá a uživatel to uvidí.
- UI ukazuje uplynulý čas a výslednou délku videa.
- `versionCode` 4, `versionName` "0.4.0".

## Review Focus

1. **Zastavení dřív, než vznikne první snímek:** nic se neuloží a uživatel dostane hlášku, žádný prázdný ani rozbitý MP4 v galerii (Task 2 test + Task 3).
2. **Ukončení z notifikace nebo ztráta kamery během běhu (jiná aplikace ji převezme):** kodér se dokončí s tím, co má, a soubor je přehratelný. Session při chybě kamery (`CameraState` error) během časosběru zavolá `stopRecording()` (Task 3). Na emulátoru se ověří ukončení z notifikace na pozadí (Task 3, krok 5b).
3. **Liché rozměry nebo stride ≠ šířka v RGBA bufferu z ImageAnalysis:** převod musí respektovat `rowStride` a sudé rozměry (Task 1 test se stride).
4. **Otočený telefon:** video má správnou orientaci (`setOrientationHint` z `rotationDegrees` prvního snímku) (Task 2).
5. **Dlouhý běh (hodiny):** paměť se nesmí hromadit; každý snímek se po zakódování uvolní (`ImageProxy.close()` hned po převodu) (Task 2).

---

### Task 1: Převod RGBA → I420 a časování (čistá logika)

**Files:**
- Create: `timelapse/TimelapseMath.kt`
- Test: `timelapse/TimelapseMathTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  /** RGBA (4 B/px, řádek [rowStride] B) → I420 (Y, pak U, pak V), sudé [width]/[height]; BT.601 limited range. */
  fun rgbaToI420(rgba: ByteArray, width: Int, height: Int, rowStride: Int): ByteArray
  /** Má se vzít snímek v čase [nowMs], když poslední vzatý byl v [lastMs] (null = žádný)? */
  fun shouldTakeFrame(nowMs: Long, lastMs: Long?, intervalMs: Long): Boolean
  fun frameTimestampUs(index: Int, fps: Int = 30): Long
  fun timelapseOutputDurationMs(frames: Int, fps: Int = 30): Long
  val TIMELAPSE_INTERVALS_MS: List<Long>  // 500, 1000, 2000, 5000, 10000
  ```
- [ ] **Step 1: Testy:**
  - **Převod barev:**
    - čistě bílý obrázek 4×2 → všechny Y = 235, U = V = 128;
    - černý → Y = 16;
    - čistě červený (255,0,0) → Y = 82, U = 90, V = 240 (±1);
    - délka výstupu = `w*h*3/2`;
    - se `rowStride = w*4 + 8` (výplň na konci řádku) vyjde stejný výsledek jako bez výplně.
  - **Časování:**
    - `shouldTakeFrame(0, null, 1000)` true;
    - `shouldTakeFrame(999, 0, 1000)` false;
    - `shouldTakeFrame(1000, 0, 1000)` true.
  - **Délka a značky:**
    - `frameTimestampUs(30) == 1_000_000`;
    - `timelapseOutputDurationMs(90) == 3000`.
- [ ] **Step 2: FAIL**, **Step 3: implementuj**, **Step 4: PASS** — `./gradlew :app:testDebugUnitTest --tests '*TimelapseMath*'`
- [ ] **Step 5: Commit** — `feat: timelapse frame conversion and timing`

### Task 2: TimelapseEncoder

**Files:**
- Create: `timelapse/TimelapseEncoder.kt`
- Test: žádný JVM test (MediaCodec na JVM neexistuje); ověření na emulátoru v Task 3.

**Interfaces:**
- Consumes: `rgbaToI420`, `frameTimestampUs` (Task 1)
- Produces:
  ```kotlin
  class TimelapseEncoder(output: File, width: Int, height: Int, rotationDegrees: Int, fps: Int = 30) : Closeable {
      val frameCount: Int
      fun addFrame(i420: ByteArray)   // blokující, volat z jednoho pracovního vlákna
      /** Dokončí soubor; vrátí false (a soubor smaže), když nebyl zapsán žádný snímek. */
      fun finish(): Boolean
      override fun close()             // = finish() bez návratové hodnoty, idempotentní
  }
  ```
  MediaCodec konfigurace:
  - kodek `video/avc`, `COLOR_FormatYUV420Flexible`, bitrate `width*height*4` (~8 Mb/s při 1080p), `I_FRAME_INTERVAL` 1, `KEY_FRAME_RATE` = fps;
  - vstup přes `getInputImage(index)` a kopii planes podle `rowStride`/`pixelStride` (podporuje planar i semi-planar);
  - `muxer.setOrientationHint(rotationDegrees)` před startem.

  Rozměry se zarovnají dolů na násobek 16 (výřez středu), kvůli kompatibilitě kodérů.

- [ ] **Step 1: Implementuj.**
- [ ] **Step 2: Build** — `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL
- [ ] **Step 3: Commit** — `feat: timelapse encoder`

### Task 3: Režim Časosběr v session a UI

**Files:**
- Modify: `camera/Modes.kt` (TIMELAPSE vždy dostupný, když kamera umí náhled), `camera/BindingPlan.kt`, `camera/CameraSession.kt`, `settings/CameraSettings.kt` (+ `timelapseIntervalMs: Long = 1000`), `settings/SettingsRepository.kt`, `ui/components/QuickSettingsBar.kt`, `ui/components/RecordingIndicator.kt`, `ui/CameraScreen.kt`, `res/values/strings.xml`
- Create: `camera/TimelapseRecorder.kt`
- Test: `camera/ModesTest.kt`, `camera/BindingPlanTest.kt`, `settings/SettingsRepositoryTest.kt` (rozšířit)

**Interfaces:**
- Consumes: Task 1, Task 2, `RecordingService`, `MediaStoreWriter.saveVideoFile`
- Produces:
  - `bindingAttempts(TIMELAPSE, …) == listOf(BindingAttempt(setOf(PREVIEW, ANALYSIS)))` (nový `UseCaseKind.ANALYSIS`)
  - `RecordingState` dostane `kind: RecordingKind { VIDEO, TIMELAPSE }` a `frames: Int`.
  - `TimelapseRecorder.start(analysis: ImageAnalysis, intervalMs: Long, onProgress: (frames: Int, elapsedNanos: Long) -> Unit)` a `stop(): File?` (null = 0 snímků).
    - Analyzer běží na jednovláknovém executoru.
    - Snímek, který `shouldTakeFrame` nepustí, se hned zavře.
    - Vzatý snímek se převede, předá do kodéru a pak zavře.
- Session:
  - `startRecording()` v režimu TIMELAPSE spustí `RecordingService.start(app, withAudio = false)` a `TimelapseRecorder`.
  - `stopRecording()` zavolá `stop()` na IO. Výsledný soubor uloží `writer.saveVideoFile` a emituje `Saved(SavedMedia(uri, VIDEO))`. Bez souboru emituje `CaptureFailed(getString(R.string.timelapse_empty))`.
  - Stop z notifikace funguje stejně.
- UI:
  - Lišta má TIMELAPSE na začátku a popisek ČASOSBĚR.
  - Rychlá volba intervalu cykluje přes `TIMELAPSE_INTERVALS_MS` a zobrazuje „0,5 s“, „1 s“ atd.
  - `RecordingIndicator` u časosběru ukazuje „mm:ss → výsledek m:ss“ přes `timelapseOutputDurationMs(frames)`.

- [ ] **Step 1: Testy**:
  - `ModesTest`: TIMELAPSE je první v `availableModes`.
  - `BindingPlanTest`: binding pro TIMELAPSE.
  - Repozitář: `timelapseIntervalMs` přežije uložení.
- [ ] **Step 2: FAIL**, **Step 3: implementuj**, **Step 4: PASS** — `./gradlew :app:testDebugUnitTest`
- [ ] **Step 5: Emulátor API 36:**
  - (a) interval 0,5 s, běh 20 s → MP4 v `content://media/external/video/media`, `ffprobe` ≈ 40 snímků, `r_frame_rate` 30/1, délka ≈ 1,3 s;
  - (b) start, `KEYCODE_HOME`, 20 s, návrat → UI ukazuje běžící časosběr a počet snímků roste i na pozadí; stop tlačítkem Zastavit v notifikaci (`adb shell cmd notification` / tap ve shade) → video uložené;
  - (c) start a hned stop (do 0,5 s) → hláška, žádné nové video;
  - (d) API 29: (a) projde bez pádu.
- [ ] **Step 6: Commit** — `feat: timelapse mode`

### Task 4: Vydání 0.4.0

- [ ] **Step 1:** `versionCode = 4`, `versionName = "0.4.0"`; testy, lint, `assembleRelease` → BUILD SUCCESSFUL; podpis ověřen.
- [ ] **Step 2:** Smoke test release přes `adb install -r` nad 0.3.0: časosběr 20 s, živá fotka, video.
- [ ] **Step 3:** `git push`, `gh release create release-0.4.0 … --prerelease` s českými poznámkami.
