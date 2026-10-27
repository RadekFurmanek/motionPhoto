# Oprava panoramy — implementační plán

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans (uživatel chce vše v jednom vlákně,
> bez subagentů). Kroky používají checkboxy (`- [ ]`).

**Goal:** Panorama ve sférické projekci s GPano metadaty (Google Fotky), bez duchů, ostřejší, s vodováhou.

**Architecture:** Každý snímek nese rotační matici z game rotation vectoru. Při spojování se snímek promítne do
„dlaždice“ na společné sférické (equirectangular) mřížce podle své orientace (otočení, sklon, náklon). Sousední
dlaždice se pak liší jen posunem, který dnešní `estimateShift` doladí obrazem. Dlaždice se postupně skládají na
plátno: šev se hledá dynamickým programováním po řádcích v překryvu a prolne se úzkým pásem. Výsledek se ořízne
na plně pokryté řádky a do JPEG se po EXIF vloží XMP GPano. Vodováha čte stejný senzor už před startem.

**Tech Stack:** Kotlin, CameraX 1.6.2, SensorManager, Adobe XMP core (už v projektu), Compose, JUnit.

**Spec:** `docs/superpowers/specs/2026-10-27-panorama-oprava-design.md`

## Global Constraints

- minSdk 29, CameraX 1.6.2, žádné nativní knihovny (OpenCV ne).
- GPano: `ProjectionType=equirectangular`, `UsePanoramaViewer=True`, `FullPanoWidthPixels = round(2π·F)`,
  `FullPanoHeightPixels = FullPanoWidthPixels / 2`, horizont ve středu plné výšky, výřez vodorovně vycentrovaný.
- Krok snímků = 2/3 zorného pole (`PANORAMA_STEPS_PER_FOV = 1.5f`), šev s přechodem 32 px.
- Snímky ~1440 px na kratší straně (analýza 1920×1440), drží se jako JPEG q95, ne jako ARGB.
- Vodováha: „rovně“ při náklonu do ±2°, barva žlutá `0xFFFFD54F`; ukazuje se i před startem.
- Jméno souboru beze změny (`PXL_….PANO.jpg`). Verze **0.8.0** (versionCode 10).
- Při `OutOfMemoryError` jeden pokus znovu v měřítku 0,6, teprve pak hláška `panorama_failed`.
- Release build (R8) smoke test na emulátoru před předáním.

## Review Focus

1. Telefon držený na šířku (aplikace je zamčená na výšku): vodováha musí ukazovat skutečný horizont (T1 test).
2. Panorama přes celých 360°: výsledek nesmí být širší než `FullPanoWidthPixels` (T2 test).
3. Fotka s polohou: XMP GPano musí přežít zápis EXIF polohy (T3: XMP se vkládá až po EXIF; emulátor ověří).
4. Prázdná stěna (bez textury): sensor-only zarovnání nesmí ujet (T2 test s `confident = false`).
5. Velká paměť: 360° na telefonu nesmí spadnout (T4: OOM retry ve zmenšeném měřítku).

---

### Task 1: Orientace — kamera, náklon, sklon (čistá matematika)

**Files:**
- Create: `app/src/main/java/cz/furmanek/motionphoto/panorama/Orientation.kt`
- Test: `app/src/test/java/cz/furmanek/motionphoto/panorama/OrientationTest.kt`

**Interfaces — Produces:**
- `class Vec3(val x: Double, val y: Double, val z: Double)` s `dot`, `times(Double)`, `plus`.
- `class CameraBasis(val forward: Vec3, val right: Vec3, val down: Vec3)` — ve světových souřadnicích (Z nahoru).
- `fun cameraBasis(rotation: FloatArray, surfaceRotation: Int): CameraBasis` — `rotation` je 3×3 device→world
  z `SensorManager.getRotationMatrixFromVector`; zadní kamera míří po −Z zařízení; „vzpřímený“ snímek má osy podle
  `surfaceRotation` (0: right=+X, down=−Y; 1 (90): right=+Y, down=+X; 2: right=−X, down=+Y; 3: right=−Y, down=−X).
- `fun headingOf(direction: Vec3): Double` = `atan2(x, y)` (radiány, doprava roste), `fun elevationOf(direction: Vec3): Double` = `asin(z)`.
- `class Attitude(val rollDeg: Float, val pitchDeg: Float)`; `fun attitudeOf(rotation: FloatArray): Attitude` —
  roll = `atan2(-up.x, up.y)` kde `up` = světové nahoru v souřadnicích zařízení (`Rᵀ·(0,0,1)`), pitch = elevace
  `forward` zadní kamery.
- `fun levelErrorDeg(rollDeg: Float): Float` — odchylka od nejbližšího násobku 90°.

- [ ] **Step 1: Testy** — `uprightPhoneLooksAtHorizon` (matice pro telefon na výšku, kamera na sever: forward≈(0,1,0), right≈(1,0,0), down≈(0,0,−1), heading 0, elevace 0); `turnedRightRaisesHeading` (otočení o 30° doprava ⇒ heading +30°); `tiltUpRaisesElevation` (sklon kamery nahoru o 10° ⇒ elevace +10°, pitch +10°); `rollClockwiseIsPositive` (natočení telefonu po směru hodin o 5° ⇒ rollDeg = 5, levelError 5); `landscapeLevelHasNoError` (telefon na šířku ⇒ |rollDeg| ≈ 90, levelError ≈ 0); `surfaceRotation90AxesStayPerpendicular`.
- [ ] **Step 2:** FAIL (nekompiluje). **Step 3:** implementace. **Step 4:** PASS.
- [ ] **Step 5:** Commit `feat: panorama orientation math`.

### Task 2: Sférické dlaždice, šev, složení

**Files:**
- Create: `app/src/main/java/cz/furmanek/motionphoto/panorama/Spherical.kt`
- Modify: `panorama/Stitcher.kt` — smazat `cylindricalWarp` a `stitch`; `estimateShift`, `LumaMap`, `mismatch`, `focalLengthPx` zůstávají.
- Modify: `panorama/PanoramaCapture.kt` — `stitchPanorama` přepsat na nový tok.
- Test: `app/src/test/java/cz/furmanek/motionphoto/panorama/SphericalTest.kt`; `StitcherTest` upravit (testy smazaných funkcí pryč).

**Interfaces:**
- Consumes: T1 `CameraBasis`, `headingOf`, `elevationOf`.
- Produces:
  - `class SphereGrid(val focalPx: Double, val topElevation: Double, val height: Int)` — řádek y ↔ elevace `topElevation − (y+0.5)/focalPx`.
  - `fun projectTile(frame: Raster, basis: CameraBasis, centreHeading: Double, grid: SphereGrid, width: Int): Raster` — sloupec x ↔ heading `centreHeading + (x + 0.5 − width/2)/focalPx`; mimo snímek alfa 0.
  - `class Panorama(val image: Raster, val fullWidth: Int, val fullHeight: Int, val left: Int, val top: Int)`.
  - `fun composeWithSeam(canvas: Raster, tile: Raster, tileX: Int, tileY: Int, feather: Int = SEAM_FEATHER_PX)` — na místě; šev DP v překryvu, prolnutí ±feather/2.
  - `suspend fun stitchPanorama(frames: List<PanoramaFrame>, focalPx: Double, scale: Double = 1.0, onProgress: (Float) -> Unit): Panorama`.
- [ ] **Step 1: Testy:**
  - `tileCentreShowsFrameCentre`: snímek s jediným bílým bodem ve středu, basis z T1 rovně ⇒ v dlaždici bod na (width/2, řádek elevace 0).
  - `neighbourTilesDifferByHeadingShift`: dva výřezy syntetické scény promítnuté s headingem 0° a 20° ⇒ `estimateShift` vrátí dx ≈ 20°·F (±2 px), dy ≈ 0.
  - `seamAvoidsMovingObject`: plátno a dlaždice ze stejné scény, v dlaždici navíc tmavý obdélník v levé části překryvu ⇒ výsledek ten obdélník nemá (šev jde napravo od něj) a jinde se od scény liší < 3 úrovně.
  - `noGhostOnStaticScene`: 3 dlaždice syntetické scény s přesnými posuny ⇒ výsledek ≈ scéna (průměrná odchylka < 2).
  - `fullTurnIsNotWiderThanFullPano`: rámce pokrývající 380° ⇒ `image.width ≤ fullWidth`.
  - `gpanoNumbersConsistent`: `fullWidth == round(2π·F·scale)`, `fullHeight == fullWidth/2`, `top == round(fullHeight/2 − topElevationOfCrop·F)`, `left == (fullWidth − width)/2`.
  - `blankWallFallsBackToSensor`: jednobarevné dlaždice ⇒ posun = senzorový.
- [ ] **Step 2:** FAIL. **Step 3:** implementace; postup `stitchPanorama`: (1) z matic spočítat headingy (unwrap vůči prvnímu), pořadí podle směru, `SphereGrid` z rozsahu elevací ± půl svislého zorného pole; (2) plátno široké podle součtu kroků + šířka dlaždice + rezerva; (3) pro každý snímek: dekódovat JPEG → `projectTile` → `estimateShift` vůči předchozí dlaždici (searchX = 4 % šířky snímku, searchY = 24) → `composeWithSeam`; (4) oříznout na plně pokryté řádky, šířku na `fullWidth`; scale < 1 zmenší snímky před projekcí.
- [ ] **Step 4:** PASS + celé testy.
- [ ] **Step 5:** Commit `feat: spherical panorama stitching with seams`.

### Task 3: XMP GPano

**Files:**
- Create: `app/src/main/java/cz/furmanek/motionphoto/media/PanoramaXmp.kt`
- Modify: `media/MediaStoreWriter.kt` — `savePanorama(jpeg, panorama: Panorama metadata, …)`: zapsat soubor → EXIF poloha → vložit XMP → uložit.
- Test: `app/src/test/java/cz/furmanek/motionphoto/media/PanoramaXmpTest.kt`

**Interfaces — Produces:** `object PanoramaXmp { fun insert(jpeg: ByteArray, width: Int, height: Int, fullWidth: Int, fullHeight: Int, left: Int, top: Int): ByteArray }` — APP1 `http://ns.adobe.com/xap/1.0/\0` hned za SOI (a za APP0/EXIF, pokud jsou), namespace `http://ns.google.com/photos/1.0/panorama/` prefix `GPano`.
- [ ] **Step 1: Test** `insertedXmpParsesWithGPanoValues`: minimální JPEG (SOI, APP0, SOS…EOI) ⇒ výstup začíná SOI, obsahuje APP1 XMP, `XMPMetaFactory.parseFromBuffer` vrátí `ProjectionType=equirectangular`, `UsePanoramaViewer=True` a všechna čísla; zbytek JPEG beze změny za segmentem.
- [ ] **Step 2–4:** FAIL → implementace → PASS.
- [ ] **Step 5:** Commit `feat: GPano metadata for panoramas`.

### Task 4: Pořízení — matice, rozlišení, krok, OOM

**Files:**
- Modify: `panorama/PanoramaSensor.kt` — callback `(yawDeg, pitchDeg, rotation: FloatArray)`; nové `fun startAttitude(onAttitude: (Attitude) -> Unit)` sdílí listener.
- Modify: `panorama/PanoramaCapture.kt` — `PanoramaFrame(val jpeg: ByteArray, val rotation: FloatArray, val surfaceRotation: Int)`; snímek vzpřímit, bez zmenšení pod 1440 px kratší strany, uložit JPEG q95; `start(…, surfaceRotation)`.
- Modify: `camera/CameraSession.kt` — analýza v PANORAMA `Size(1920, 1440)`; `PANORAMA_STEPS_PER_FOV = 1.5f`; `finishPanorama`: `stitchPanorama(frames, focalPx)`, při OOM znovu se `scale = 0.6`; `savePanorama` s GPano.
- Test: `PanoramaCaptureTest` upravit na nový `PanoramaFrame`; `panoramaLayout` testy podle potřeby.
- [ ] **Step 1:** testy upravit (FAIL), implementace, PASS, `assembleDebug`.
- [ ] **Step 2: Emulátor:** panorama (otáčení virtuální scény), uloží se, `content read` → XMP obsahuje GPano a čísla sedí s rozměrem; log `Panorama optics`.
- [ ] **Step 3:** Commit `feat: sharper panorama frames with orientation`.

### Task 5: Vodováha a sklon v UI

**Files:**
- Modify: `camera/CameraSession.kt` — `val panoramaAttitude: StateFlow<Attitude?>`: senzor běží v režimu PANORAMA s viditelnou aplikací (i před startem), jinak null.
- Modify: `ui/components/PanoramaGuide.kt` — nový `@Composable fun PanoramaLevel(attitude: Attitude?, startPitchDeg: Float?, …)`: čára horizontu natočená o `−rollDeg`, žlutá při `abs(levelErrorDeg(roll)) ≤ 2f`, jinak bílá; cílová tečka svisle posunutá o `(pitch − startPitch) · TARGET_DP_PER_DEGREE` (omezeno). Před startem jen čára.
- Modify: `ui/CameraScreen.kt` — v režimu Panorama vykreslit `PanoramaLevel` (izolovaný composable, sbírá flow sám jako `PortraitOverlay`).
- Test: `PanoramaGuideTest` — `targetVerticalOffsetFollowsPitch` (sklon +5° nad start ⇒ tečka o 5·k výš, ořez na max), `levelColourWithinTwoDegrees`.
- [ ] **Step 1–4:** test FAIL → implementace → PASS → emulátor screenshot v Panoramě (čára vidět před startem).
- [ ] **Step 5:** Commit `feat: panorama spirit level`.

### Task 6: Vydání 0.8.0

- [ ] `versionCode = 10`, `versionName = "0.8.0"`; testy, lint, `assembleRelease`, podpis jako 0.7.0, smoke test release.
- [ ] Celková kontrola větve (sám), opravy.
- [ ] Po souhlasu uživatele: merge, push, `gh release create release-0.8.0 --prerelease`, české poznámky.
