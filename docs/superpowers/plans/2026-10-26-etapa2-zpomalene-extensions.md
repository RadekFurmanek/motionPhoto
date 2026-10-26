# Etapa 2 — zpomalené video a režimy výrobce: implementační plán

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Přidat režimy Zpomalené (120/240 fps), Portrét a Noc a přepínač HDR ve Foto. Všechno jen tam, kde to telefon umí. Vydat jako 0.3.0.

**Architecture:** `CaptureMode` se rozšíří o nové režimy. Které režimy lišta ukáže, rozhoduje čistá funkce `availableModes(capabilities)`. `CameraSession.bind` dostane větve pro high-speed session (`HighSpeedVideoSessionConfig` přes `bindToLifecycle(owner, selector, sessionConfig)`) a pro Extensions (selektor z `ExtensionsManager.getExtensionEnabledCameraSelector`). Zpomalené video používá stávající `VideoRecorder` a `RecordingService`, takže funguje i na pozadí. Portrét, Noc a HDR fotí přes `PhotoCapture.capturePhoto`.

**Tech Stack:** jako etapa 1, navíc `androidx.camera:camera-extensions:1.6.2`.

**Spec:** `docs/superpowers/specs/2026-10-26-full-camera-design.md`

## Global Constraints

- minSdk 29, CameraX 1.6.2, žádné nativní knihovny. Nová závislost jen `camera-extensions` 1.6.2.
- Pořadí režimů na liště: Časosběr · Zpomalené · Video · **Foto** · Portrét · Noc · Panorama. Lišta ukáže jen dostupné. Časosběr a Panorama přidají etapy 3 a 4.
- Živá fotka a HDR se vylučují: zapnutí jednoho vypne druhé (spec).
- Portrét, Noc a HDR fotí bez živé fotky. Jméno souboru `PXL_yyyyMMdd_HHmmss.jpg` (UTC).
- Zpomalené video: `PXL_yyyyMMdd_HHmmss.mp4`, natáčí se i na pozadí stejně jako Video.
- Co telefon neumí, se nezobrazí. Na emulátoru se ověří jen to, že se nové režimy skryjí.
- `versionCode` 3, `versionName` "0.3.0". Vydání obsahuje i plynulý zoom (commit c388e55).

## Review Focus

1. **Telefon bez high-speed a bez Extensions** (oba emulátory): lišta ukazuje jen Video a Foto, nastavení nenabízí 120/240 a HDR, nic nespadne (Task 1 test `availableModes`, Task 4 emulátor).
2. **Uložený režim, který nový telefon nebo jiná kamera neumí** (např. Noc uložená z jiné kamery, pak přepnutí na přední): session se vrátí do Foto, nezůstane na chybě (Task 1 test `fallbackMode`).
3. **Zapnutý HDR a pak zapnutí živé fotky** (a naopak): nikdy nejsou zapnuté obě (Task 1 test).
4. **Přepnutí režimu během zpomaleného nahrávání:** zakázané stejně jako u Videa (Task 3: `isLocked`).
5. **Extensions mají dlouhé zpracování (Noc až několik sekund):** spoušť ukazuje průběh, druhý stisk se ignoruje a fotka se uloží (Task 3, `busy`).

---

### Task 1: Režimy a pravidla (čistá logika)

**Files:**
- Modify: `settings/CameraSettings.kt`, `settings/SettingsRepository.kt`
- Create: `camera/Modes.kt`
- Test: `settings/CameraSettingsTest.kt` (rozšířit), `camera/ModesTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  enum class CaptureMode { TIMELAPSE, SLOW_MOTION, VIDEO, PHOTO, PORTRAIT, NIGHT, PANORAMA }  // pořadí = pořadí lišty
  // CameraSettings: + slowMotionFps: Int = 120, + hdr: Boolean = false
  fun CameraSettings.effectiveLivePhoto(): Boolean = livePhoto && flash == FlashSetting.OFF && !hdr
  fun CameraSettings.withHdr(on: Boolean): CameraSettings      // on → livePhoto = false
  fun CameraSettings.withLivePhoto(on: Boolean): CameraSettings // on → hdr = false
  // camera/Modes.kt
  fun availableModes(capabilities: CameraCapabilities): List<CaptureMode>  // jen implementované režimy: VIDEO, PHOTO vždy (pokud kamera umí video/foto), SLOW_MOTION když slowMotionFps není prázdné, PORTRAIT/NIGHT podle extensionModes
  fun fallbackMode(requested: CaptureMode, available: List<CaptureMode>): CaptureMode  // requested když je dostupný, jinak PHOTO
  ```
  `CameraCapabilities` dostane `slowMotionFps: Set<Int>` (podmnožina {120, 240}) a `extensionModes: Set<Int>` (hodnoty `ExtensionMode.BOKEH/NIGHT/HDR`). Pro testy stačí holé Int konstanty.

- [ ] **Step 1: Testy:**
  - `ModesTest`:
    - `availableModes(caps bez slow-motion a extensions) == listOf(VIDEO, PHOTO)`;
    - se `slowMotionFps = setOf(120)` a `extensionModes = setOf(BOKEH, NIGHT)` → `listOf(SLOW_MOTION, VIDEO, PHOTO, PORTRAIT, NIGHT)`;
    - samotné `HDR` v `extensionModes` nepřidá režim (HDR je přepínač ve Foto);
    - `fallbackMode(NIGHT, listOf(VIDEO, PHOTO)) == PHOTO`;
    - `fallbackMode(VIDEO, listOf(VIDEO, PHOTO)) == VIDEO`.
  - `CameraSettingsTest`:
    - `CameraSettings(livePhoto = true).withHdr(true)` má `livePhoto == false` a `hdr == true`;
    - `CameraSettings(hdr = true).withLivePhoto(true)` má `hdr == false`;
    - `effectiveLivePhoto()` je false při `hdr = true`.
  - Repozitář: `slowMotionFps` a `hdr` přežijí uložení (rozšířit `updates survive a new repository`).
- [ ] **Step 2: FAIL**, **Step 3: implementuj**, **Step 4: PASS** — `./gradlew :app:testDebugUnitTest`
- [ ] **Step 5: Commit** — `feat: capture modes and rules for slow motion and extensions`

### Task 2: Schopnosti — high-speed a Extensions

**Files:**
- Modify: `camera/CapabilityProbe.kt`, `camera/CameraCapabilities.kt`, `gradle/libs.versions.toml`, `app/build.gradle.kts`

**Interfaces:**
- Consumes: `CameraCapabilities` (Task 1)
- Produces: `CapabilityProbe.probe(provider, cameraInfo, selector, extensions: ExtensionsManager?)` vyplní `slowMotionFps` a `extensionModes`.
  - **slowMotionFps:** `Recorder.getHighSpeedVideoCapabilities(cameraInfo)`. Pokud má kvality, pro nejvyšší z nich sestav `HighSpeedVideoSessionConfig(VideoCapture(Recorder s tou kvalitou), Preview)` a vezmi `cameraInfo.getSupportedFrameRateRanges(config)`. Do množiny dej 120/240, když existuje rozsah s horní mezí ≥ té hodnoty.
  - **extensionModes:** `extensions.isExtensionAvailable(selector, mode)` pro BOKEH, NIGHT, HDR.
  - Každé volání musí být ve `try/catch` (IllegalArgumentException, IllegalStateException), aby výsledkem byla prázdná množina, ne pád.
  - `ExtensionsManager.getInstance(context, provider)` (suspend) se v `CameraSession` vytvoří jednou a výsledek se drží. Selhání znamená `null`, tedy žádné Extensions.

- [ ] **Step 1: Implementuj.**
- [ ] **Step 2: Build + testy** — `./gradlew :app:assembleDebug :app:testDebugUnitTest` → BUILD SUCCESSFUL
- [ ] **Step 3: Commit** — `feat: probe high-speed video and camera extensions`

### Task 3: Binding a focení/natáčení v nových režimech

**Files:**
- Modify: `camera/CameraSession.kt`, `camera/BindingPlan.kt`

**Interfaces:**
- Consumes: `availableModes`, `fallbackMode`, `effectiveLivePhoto` (Task 1); `CameraCapabilities.slowMotionFps/extensionModes` (Task 2)
- Produces:
  - `BindingKey` dostane `hdr` a `slowMotionFps`.
  - `bind()` po probe nejdřív spočítá `fallbackMode(settings.mode, availableModes(caps))`. Když se liší, uloží ho do nastavení a skončí. Nové nastavení spustí nový bind.
  - **SLOW_MOTION:**
    - `VideoCapture(Recorder s nejvyšší high-speed kvalitou)` + `Preview`;
    - `HighSpeedVideoSessionConfig(video, preview, Range(fps, fps), slowMotionEnabled = true)`, kde `fps = settings.slowMotionFps`, pokud je ve `slowMotionFps`, jinak nejvyšší dostupné;
    - `provider.bindToLifecycle(this, selector, sessionConfig)`.
  - **PORTRAIT/NIGHT:**
    - selektor `extensions.getExtensionEnabledCameraSelector(baseSelector, BOKEH|NIGHT)`;
    - use cases `Preview` + `ImageCapture` v `UseCaseGroup` s ViewPortem 4:3.
  - **PHOTO s `hdr`:** stejně jako Portrét, jen s `ExtensionMode.HDR` a bez VideoCapture.
  - **Spoušť (`onShutter`):**
    - SLOW_MOTION → start/stop nahrávání (stávající `startRecording`/`stopRecording` a `RecordingService`);
    - PORTRAIT/NIGHT → `takePhoto()` s obyčejnou fotkou.
  - `startRecording` přijme `settings.mode == SLOW_MOTION`. Zpomalené nahrávání je bez zvuku: high-speed audio nepodporuje a `withAudio = false` zároveň znamená FGS jen typu camera.
  - `isLocked` se nemění; během nahrávání se režim nepřepíná.

- [ ] **Step 1: Test** v `BindingPlanTest`: `bindingAttempts(SLOW_MOTION, …) == listOf(BindingAttempt(setOf(PREVIEW, VIDEO)))`; `bindingAttempts(NIGHT, …) == listOf(BindingAttempt(setOf(PREVIEW, IMAGE)))`; `bindingAttempts(PORTRAIT, …)` totéž.
- [ ] **Step 2: FAIL**, **Step 3: implementuj** (rozšíření `bindingAttempts` + binding v session), **Step 4: PASS** — `./gradlew :app:testDebugUnitTest`
- [ ] **Step 5: Emulátor API 36 a API 29** — appka startuje, lišta jen Video · Foto, Foto/Video fungují jako v 0.2.0 (živá fotka, 5 s video), `adb logcat | grep AndroidRuntime` prázdné.
- [ ] **Step 6: Commit** — `feat: slow motion, portrait, night and HDR capture`

### Task 4: UI — lišta režimů, volby, průběh zpracování

**Files:**
- Modify: `ui/components/ModeBar.kt`, `ui/components/QuickSettingsBar.kt`, `ui/CameraScreen.kt`, `ui/SettingsScreen.kt`, `ui/CameraViewModel.kt`, `res/values/strings.xml`

**Interfaces:**
- Consumes: `availableModes`, `withHdr`, `withLivePhoto` (Task 1)

Chování:
- **ModeBar:** vykreslí `availableModes(caps)` v daném pořadí s popisky (ZPOMALENÉ, VIDEO, FOTO, PORTRÉT, NOC). Řádek je vodorovně posouvatelný a vybraný režim je vystředěný. Swipe přepíná na sousední dostupný režim.
- **QuickSettingsBar:**
  - Foto: přepínač HDR (ikona `HdrOn`/`HdrOff`), jen když `HDR in extensionModes`. Přepínač živé fotky a HDR volají `withLivePhoto`/`withHdr`.
  - Zpomalené: volba 120/240 jen z `slowMotionFps`, se svítilnou jako ve Videu.
  - Portrét/Noc: blesk (pokud je) a samospoušť.
- **Spoušť:**
  - zpomalené: červené kolečko/čtverec jako Video;
  - Portrét/Noc během `busy`: neurčitý kruhový průběh (Extensions zpracovávají až sekundy).
- **Nastavení:** sekce Zpomalené s 120/240, jen pokud je.

- [ ] **Step 1: Implementuj.**
- [ ] **Step 2: Build + lint** — `./gradlew :app:assembleDebug :app:lintDebug` → bez chyb
- [ ] **Step 3: Emulátor** — screenshot: lišta jen VIDEO · FOTO, žádný přepínač HDR; swipe přepíná Foto↔Video.
- [ ] **Step 4: Commit** — `feat: mode bar and settings for slow motion and extensions`

### Task 5: Vydání 0.3.0

- [ ] **Step 1:** `versionCode = 3`, `versionName = "0.3.0"`; `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease` → BUILD SUCCESSFUL; `apksigner verify --print-certs` → `CN=Radek Furmanek, O=cz.furmanek`.
- [ ] **Step 2:** Smoke test release na API 36 přes `adb install -r` nad 0.2.0 z GitHubu: živá fotka (XMP OK), 5 s video, plynulý zoom (na API 29, kde jde zoom do 10×).
- [ ] **Step 3:** `git push`, `gh release create release-0.3.0 MotionPhoto-0.3.0.apk --prerelease` s českými poznámkami. Poznámky musí uvést, že zpomalené video a Portrét/Noc/HDR se objeví jen na telefonech, které je podporují.
