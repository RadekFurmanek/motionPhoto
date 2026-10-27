# Skrytý ultraširoký fotoaparát (makro, 0,5×) — implementační plán

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Makro a zoom 0,5× ve Foto přes zadní fotoaparát, který telefon skrývá ze seznamu, otevřený přímo přes Camera2.

**Architecture:** Čistá logika (výběr objektivu, popisek, otočení JPEG, převod klepnutí, ořez zoomu) v `camera/DirectLens.kt`. Ovladač Camera2 `camera/DirectCamera.kt` (náhled do `Surface`, ostření, expozice, zoom ořezem, JPEG do souboru). `CameraSession` drží „přímý režim“: uvolní CameraX, otevře `DirectCamera`, při odchodu ji zavře a znovu bindne. UI: `TextureView` 3:4 přes náhled, čip 0,5×, zašedlé ovládání.

**Tech Stack:** Kotlin, Camera2, Compose, CameraX 1.6.2 (beze změny pro ostatní).

**Spec:** `docs/superpowers/specs/2026-10-27-skryty-ultrawide-design.md`

## Global Constraints

- minSdk 29: zoom přes `SCALER_CROP_REGION` (ne `CONTROL_ZOOM_RATIO`, ten je od API 30).
- Jen Foto + zadní fotoaparát; jen obyčejná fotka 4:3; blesk, poměr stran, živá fotka, HDR zašedlé.
- Selhání skrytého fotoaparátu → návrat na CameraX, hláška, do restartu se skrytý nenabízí (makro = zoom 2×).
- Uložení přes `MediaStoreWriter.savePhoto(file, capturedAtMillis, location)` → `PXL_….jpg`.
- Verze 0.10.0 (versionCode 15).

## Review Focus

- Otočení fotky na šířku (oba směry) a na výšku: `JPEG_ORIENTATION` z `targetRotation`.
- Odchod z aplikace / přepnutí režimu během otevírání skrytého fotoaparátu: žádný visící otevřený fotoaparát, CameraX se znovu bindne.
- Klepnutí pro ostření mimo oblast náhledu 3:4 (pruhy nahoře/dole): ignorovat.
- Opakované klepnutí na 0,5×/1× během otevírání: jedno otevření, poslední volba vyhrává.
- Selhání `openCamera` / `onError` / `onDisconnected` uprostřed focení: hláška, žádný pád, návrat na hlavní.

---

### Task 1: Čistá logika přímého fotoaparátu

**Files:**
- Modify: `camera/Macro.kt` — `LensInfo` + `sensorWidthMm: Float = 0f`, `sensorOrientation: Int = 90`; `MacroSetup.Hidden(cameraId)`; `chooseMacro` smí vybrat skrytý (vrátí `Hidden`), viditelný objektiv dál `Lens`.
- Create: `camera/DirectLens.kt`
- Test: `MacroTest` (upravit `hiddenLensIsNeverMacro` → `hiddenLensBecomesHiddenMacro`), `DirectLensTest`

**Interfaces — Produces:**
- `fun ultraWideLens(lenses: List<LensInfo>, defaultCameraId: String?): LensInfo?` — skrytý zadní objektiv, jehož zorné pole (`sensorWidthMm / focalMm`) je aspoň 1,3× širší než u výchozího.
- `fun zoomLabelRatio(lens: LensInfo, main: LensInfo): Float` — poměr zorných polí main/lens, zaokrouhlený na 0,1.
- `fun jpegOrientation(sensorOrientation: Int, surfaceRotation: Int): Int` — zadní fotoaparát: `(sensorOrientation + deviceDegrees) % 360`, kde `deviceDegrees = (360 − surfaceRotation·90) % 360`.
- `fun tapToSensor(u: Float, v: Float, sensorOrientation: Int): Pair<Float, Float>` — (u, v) 0..1 v náhledu na výšku → normalizované souřadnice senzoru.
- `fun cropRegion(activeWidth: Int, activeHeight: Int, zoom: Float): android.graphics.Rect`-ekvivalent jako `IntArray(left, top, right, bottom)` (JVM test bez Androidu).
- `fun largest43(sizes: List<Pair<Int, Int>>, maxLong: Int = Int.MAX_VALUE): Pair<Int, Int>?`

- [ ] Testy: `ultraWideChosenWhenClearlyWider`, `noUltraWideWhenOnlyVisibleLenses`, `labelIsFovRatio` (2,1 mm/6,4 mm senzor vs 5,6 mm/6,4 mm → 0,4), `jpegOrientationPortraitAndLandscape` (90+ROTATION_0=90, ROTATION_90→0, ROTATION_270→180), `tapMapsThroughSensorRotation` (90°: (0,0)→(0,1), (1,0)→(0,0)), `cropCentredByZoom` (4000×3000, 2× → 1000,750,3000,2250), `largest43Picks43`, `hiddenLensBecomesHiddenMacro`. FAIL → implementace → PASS.
- [ ] Commit `feat: hidden lens selection and direct camera math`.

### Task 2: `DirectCamera` (Camera2)

**Files:** Create `camera/DirectCamera.kt`.

**Interfaces — Produces:** `class DirectCamera(context: Context, cameraId: String)` s
- `suspend fun open(preview: Surface, previewSize: Size)` — otevře, session s náhledem + `ImageReader` JPEG (největší 4:3), opakovaný náhled `CONTINUOUS_PICTURE`; selhání = výjimka `DirectCameraException`.
- `val previewSize: Size` (největší 4:3 do 1920 na delší straně, spočítá se v konstruktoru z charakteristik), `val sensorOrientation`, `val exposureRange: IntRange`, `val maxZoom: Float`.
- `fun focusAt(u: Float, v: Float)` (AF+AE region 10 % strany, AF trigger), `fun setExposure(index: Int)`, `fun setZoom(ratio: Float)`.
- `suspend fun capture(file: File, jpegOrientation: Int)` — still capture, zapíše JPEG.
- `val failures: StateFlow<Boolean>` (onError/onDisconnected), `fun close()` idempotentní.
- [ ] Build (`assembleDebug`). Commit `feat: direct Camera2 controller`.

### Task 3: Přímý režim v `CameraSession` a UI

**Files:** `CameraSession.kt`, `CameraCapabilities.kt` (`ultraWide: LensInfo?`, `ultraWideLabel: Float`), `CameraViewModel.kt`, `CameraScreen.kt`, `ZoomChips.kt`, `QuickSettingsBar.kt`, `strings.xml`.

- `SessionState.direct: DirectKind?` (`MACRO`, `ULTRA_WIDE`); `fun selectUltraWide(on: Boolean)`; makro s `MacroSetup.Hidden` přepíná přímý režim; `requestBind` v přímém režimu nebinduje.
- Vstup: `bindJob?.cancel()`, stop portrait/pre-roll, `unbindAll`, počkat na CLOSED, `DirectCamera.open` jakmile UI dodá `Surface` (`attachDirectSurface`/`detachDirectSurface`). Výstup: `close()`, `requestBind()`.
- `takePhoto` v přímém režimu: odpočet, zvuk, `capture` do temp souboru, `writer.savePhoto`, `lastCapture`.
- Selhání (open/capture/`failures`): `hiddenBroken = true`, výstup, `CaptureFailed(R.string.ultra_wide_failed)`.
- UI: `DirectPreview` = `TextureView` s `aspectRatio(3f/4f)` uprostřed přes `PreviewView`; klepnutí → (u, v) uvnitř 3:4, mimo ignorovat; čip 0,5× před presety; zašedlé blesk, poměr, živá fotka, HDR.
- [ ] Testy (JVM, kde jde): `zoomPresetsWithUltraWide` (štítek před 1×). Build, testy, lint. Commit `feat: hidden ultra-wide for macro and 0.5x`.

### Task 4: Ověření a vydání 0.10.0

- [ ] Emulátor s dočasně skrytým zadním „10“ (neukládat): náhled, klepnutí, focení (otočení na výšku i na šířku přes `ffprobe` rotation/EXIF), návrat 1×, odchod z aplikace v přímém režimu.
- [ ] `versionCode 15`, `versionName "0.10.0"`, release build, podpis, smoke test.
- [ ] Self-review, po souhlasu uživatele vydání.
