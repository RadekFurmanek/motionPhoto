# Etapa 4 — panorama: implementační plán

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Vodorovné panorama otáčením telefonu. Snímky spouští gyroskop a skládání je vlastní, bez OpenCV. Výsledek je JPEG `PXL_…PANO.jpg`. Vydat jako 0.5.0.

**Architecture:**
- **Čisté jádro** (`panorama/`, JVM testy): `Raster` (ARGB IntArray), otočení, cylindrická projekce, odhad posunu sousedních snímků, složení s prolnutím a ořez.
- **Android vrstva:** `PanoramaSensor` (rotation vector → azimut a náklon), `PanoramaCapture` (Preview + ImageAnalysis RGBA, snímek pokaždé, když pravidlo `PanoramaProgress` řekne ano). Skládání běží na `Dispatchers.Default` s průběhem a ukládá se přes `MediaStoreWriter`.

**Tech Stack:** jako etapa 3, žádná nová knihovna.

**Spec:** `docs/superpowers/specs/2026-10-26-full-camera-design.md`

## Global Constraints

- Jen vodorovné panorama zadní kamerou (spec: svislé, 360° fotosféra a přední kamera jsou mimo rozsah).
- Snímky se pořizují automaticky po každém otočení o ~1/3 zorného úhlu (~⅔ překryv). Uživatel ukončí ťuknutím, jinak se snímání ukončí samo při ~360° (spec).
- Bez OpenCV a nativních knihoven (spec). Skládání: cylindrická projekce podle ohniskové vzdálenosti z `CameraCharacteristics`, doladění posunu hledáním shody v překryvu na zmenšených obrázcích, prolnutí (spec).
- Výstup `PXL_yyyyMMdd_HHmmss.PANO.jpg` (UTC), JPEG kvalita 92, `DCIM/Camera`.
- Panorama běží jen na popředí. Při odchodu z aplikace se zruší (spec).
- Bez senzoru rotation vector se režim nezobrazí.
- `versionCode` 5, `versionName` "0.5.0".

## Review Focus

1. **Ukončení po jediném snímku** (ťuknutí hned po startu): neukládá se „panorama“ z jednoho snímku; hláška (Task 4).
2. **Otáčení špatným směrem nebo tam a zpět:** směr určí první pohyb, návrat zpět nic nepořídí (Task 2 test).
3. **Přechod azimutu přes ±180°:** pořizování pokračuje plynule (Task 2 test).
4. **Scéna bez textury** (bílá zeď): odhad posunu nesmí ujet. Když shoda není výrazná, použije se posun ze senzoru (Task 1 test).
5. **Paměť při 360°:** snímky se ukládají zmenšené (výška max. 960 px). Výsledek nesmí překročit ~16 000 px šířky, jinak se zmenší (Task 1, Task 3).

---

### Task 1: Skládací jádro (čistá logika)

**Files:**
- Create: `panorama/Raster.kt`, `panorama/Stitcher.kt`
- Test: `panorama/RasterTest.kt`, `panorama/StitcherTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  class Raster(val width: Int, val height: Int, val pixels: IntArray)   // ARGB, alfa 0 = prázdné
  fun Raster.rotate(degrees: Int): Raster                               // 0/90/180/270 po směru hodin
  fun Raster.scaleToHeight(height: Int): Raster                         // bilineárně
  fun cylindricalWarp(src: Raster, focalPx: Double): Raster
  data class Shift(val dx: Int, val dy: Int, val confident: Boolean)
  fun estimateShift(left: Raster, right: Raster, expectedDx: Int, searchX: Int, searchY: Int): Shift
  fun stitch(frames: List<Raster>, shifts: List<Shift>): Raster          // shifts[i] = posun frames[i+1] vůči frames[i]
  fun focalLengthPx(focalMm: Float, sensorMm: Float, imagePx: Int): Double
  ```
  Algoritmus (z podpisů neplyne):
  - **cylindricalWarp:**
    - výstupní šířka `W' = round(2·f·atan(w/(2f)))`, výška stejná;
    - pro výstupní pixel `(x', y')`: `θ = (x' − W'/2)/f`, `xs = f·tan θ + w/2`, `ys = (y' − h/2)/cos θ + h/2`;
    - hodnota je bilineární vzorek, mimo zdroj alfa 0.
  - **estimateShift:**
    - oba obrázky převeď na jas a zmenši 4×;
    - pro `dx ∈ expectedDx/4 ± searchX/4` a `dy ∈ ±searchY/4` spočti průměrný absolutní rozdíl jasu v překryvu (jen pixely s alfou > 0 v obou), překryv musí mít aspoň 20 % šířky;
    - nejlepší kandidáta dolaď na plném rozlišení v ±4 px;
    - `confident = (druhé nejlepší skóre mimo ±2 px − nejlepší) / nejlepší > 0.1`. Když `confident == false`, vrať `Shift(expectedDx, 0, false)`.
  - **stitch:**
    - kumulativní posuny určí plátno;
    - v překryvu lineární prolnutí podle vzdálenosti od středu překryvu;
    - nakonec ořez na řádky, kde jsou všechny sloupce neprázdné (odstraní černé okraje z vertikálního driftu a projekce);
    - když by šířka přesáhla 16 000 px, zmenší se poměrově.
- [ ] **Step 1: Testy:**
  - `RasterTest`:
    - `rotate(90)` 2×3 obrázku dá 3×2 se správně přesunutými rohy;
    - `rotate(360 − 90)` je inverze `rotate(90)`.
  - `StitcherTest`:
    - pomocná `syntheticScene(width = 2400, height = 320, seed = 7)` vytvoří hladkou texturu (součet sinusů různých frekvencí + šum);
    - `cylindricalWarp` s `focalPx = 1e7` vrátí obrázek shodný se vstupem (±1 v kanálu); s `focalPx = w` má šířku `round(2·w·atan(0.5))`;
    - `estimateShift(crop(scene, 0, 400), crop(scene, 150, 400) posunutý svisle o 3 px, expectedDx = 140, searchX = 40, searchY = 8)` → `dx == 150 ±1`, `dy == 3 ±1`, `confident`;
    - na jednobarevných obrázcích `confident == false` a `dx == expectedDx`;
    - `stitch` šesti výřezů šířky 400 po 150 px se správnými posuny → šířka `400 + 5·150 ±2` a průměrný rozdíl proti odpovídající části scény < 6 na kanál;
    - `focalLengthPx(4.38f, 5.6f, 960)` ≈ 750.9.
- [ ] **Step 2: FAIL**, **Step 3: implementuj**, **Step 4: PASS** — `./gradlew :app:testDebugUnitTest --tests '*panorama*'`
- [ ] **Step 5: Commit** — `feat: panorama stitching core`

### Task 2: Pravidlo snímání a senzor

**Files:**
- Create: `panorama/PanoramaProgress.kt` (čisté), `panorama/PanoramaSensor.kt` (Android)
- Test: `panorama/PanoramaProgressTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  class PanoramaProgress(private val stepDeg: Float, private val maxDeg: Float = 360f) {
      val capturedCount: Int
      val sweptDeg: Float
      val finished: Boolean            // sweptDeg >= maxDeg - stepDeg
      /** Nová hodnota azimutu (−180..180); true = teď pořiď snímek. První volání vždy true. */
      fun onYaw(yawDeg: Float): Boolean
  }
  fun isLevel(pitchDeg: Float, toleranceDeg: Float = 6f): Boolean
  class PanoramaSensor(context: Context) {
      val available: Boolean                                // TYPE_ROTATION_VECTOR existuje
      fun start(listener: (yawDeg: Float, pitchDeg: Float) -> Unit); fun stop()
  }
  ```
  - **Výpočet orientace:** `getRotationMatrixFromVector` → `remapCoordinateSystem(m, AXIS_X, AXIS_Z, out)` (telefon na výšku, kamera dopředu) → `getOrientation(out)`, odkud azimut = `[0]` a náklon = `[1]`, obojí ve stupních.
  - **Směr otáčení** určí první odchylka ≥ `stepDeg/2`. Pohyb opačným směrem se ignoruje. Rozdíly úhlů se počítají modulo 360 (přechod přes ±180).
- [ ] **Step 1: Testy:**
  - `PanoramaProgress(20f)`:
    - `onYaw(0)` true; `onYaw(10)` false; `onYaw(20)` true; `onYaw(15)` false (zpět);
    - `onYaw(170)`, `onYaw(-170)` (přechod) pořizuje dál, `sweptDeg` roste;
    - otáčení doleva (0, −20, −40) pořizuje;
    - po 340° `finished`.
  - `isLevel(5f)` true, `isLevel(-7f)` false.
- [ ] **Step 2: FAIL**, **Step 3: implementuj**, **Step 4: PASS** — `./gradlew :app:testDebugUnitTest --tests '*PanoramaProgress*'`
- [ ] **Step 5: Commit** — `feat: panorama capture rule and orientation sensor`

### Task 3: Režim Panorama v session

**Files:**
- Create: `panorama/PanoramaCapture.kt`
- Modify: `camera/Modes.kt` (PANORAMA na konci, když `hasRotationVector` a je zadní kamera), `camera/CameraCapabilities.kt` (+ `hasRotationVector`, `focalMm`, `sensorWidthMm`, `sensorHeightMm`), `camera/CapabilityProbe.kt`, `camera/BindingPlan.kt`, `camera/CameraSession.kt`, `media/MediaNaming.kt` (+ `PANORAMA(".PANO.jpg", "image/jpeg")`), `media/MediaStoreWriter.kt` (+ `savePanorama(jpeg: ByteArray, capturedAtMillis: Long, location: Location?): Uri`)
- Test: `camera/ModesTest.kt`, `camera/BindingPlanTest.kt`, `media/MediaNamingTest.kt`

**Interfaces:**
- Consumes: Task 1, Task 2
- Produces:
  - `bindingAttempts(PANORAMA, …) == listOf(BindingAttempt(setOf(PREVIEW, ANALYSIS)))`. ImageAnalysis: RGBA, `KEEP_ONLY_LATEST`, rozlišení 4:3 co nejblíž 1280×960.
  - `SessionState.panorama: PanoramaUiState?`:
    - `capturing: Boolean`, `count: Int`, `sweptDeg: Float`, `level: Boolean`, `yawToNextDeg: Float`, `stitchingProgress: Float?`.
  - `CameraSession.onShutter()` v PANORAMA:
    - **první stisk** spustí snímání. Ze senzoru přijde azimut a když `PanoramaProgress.onYaw` vrátí true, další snímek z analyzéru se otočí podle `rotationDegrees`, zmenší na výšku 960 a uloží do seznamu spolu s azimutem.
    - **druhý stisk** nebo `finished` ukončí snímání.
  - **Skládání po ukončení:**
    - `focalPx` = `focalLengthPx(focalMm, sensorMm, frameWidth)`; `sensorMm` je rozměr senzoru, který po otočení odpovídá šířce snímku (při 90/270 výška senzoru);
    - na každý snímek `cylindricalWarp`;
    - `expectedDx` z rozdílu azimutů: `Δyaw[rad] · focalPx`;
    - `estimateShift(searchX = expectedDx/4, searchY = 24)`, pak `stitch`;
    - výsledek JPEG 92 přes `savePanorama`, emit `Saved(SavedMedia(uri, PANORAMA))`;
    - průběh skládání jde do `stitchingProgress`.
  - Méně než 2 snímky → `CaptureFailed(getString(R.string.panorama_too_short))`, nic se neukládá.
  - `onUiVisible(false)` během snímání nebo skládání → zrušit a smazat (spec: panorama jen na popředí).
- [ ] **Step 1: Testy:**
  - `MediaNamingTest`: `fileName(PANORAMA, 1_761_498_818_000) == "PXL_20251026_171338.PANO.jpg"`.
  - `ModesTest`: PANORAMA je poslední, jen s `hasRotationVector` a zadní kamerou.
  - `BindingPlanTest`: binding pro PANORAMA.
- [ ] **Step 2: FAIL**, **Step 3: implementuj**, **Step 4: PASS** — `./gradlew :app:testDebugUnitTest`
- [ ] **Step 5: Commit** — `feat: panorama mode`

### Task 4: UI panoramatu

**Files:**
- Create: `ui/components/PanoramaGuide.kt`
- Modify: `ui/CameraScreen.kt`, `ui/components/ModeBar.kt`, `ui/components/ShutterButton.kt`, `res/values/strings.xml`

Chování:
- **Vodítko** uprostřed náhledu: vodorovná čára, kruh cíle posunutý podle `yawToNextDeg` (do kruhu se míří) a pás s počtem snímků a uraženým úhlem.
- **Náklon:** když `level == false`, zobrazí se „Držte telefon rovně“ a čára zčervená.
- **Spoušť:** v Panoramě má ikonu startu; během snímání je to stop.
- **Skládání:** `stitchingProgress` ukazuje „Skládám panorama…“ s průběhem; ovládání je zakázané.
- **Lišta:** PANORAMA je na konci a popisek je PANORAMA.

- [ ] **Step 1: Implementuj.**
- [ ] **Step 2: Build + lint** → bez chyb.
- [ ] **Step 3: Emulátor API 36 (virtuální scéna):**
  - Nejdřív zjisti, zda `adb emu sensor status` nabízí ovládání orientace a zda virtuální scéna sleduje natočení zařízení.
  - **Pokud ano:** start, postupně měň azimut po 10° na 100°, stop → `PXL_…PANO.jpg` existuje, šířka > 2× šířka snímku, výška > 0, na screenshotu výsledku navazující scéna (zkontrolovat obrazem).
  - **Pokud ne:** ověř UI (vodítko, hláška náklonu), start + okamžitý stop → hláška „příliš krátké“ a nic se neuloží; stitching pokrývají JVM testy. Do ledgeru zapiš, že end-to-end skládání ze senzoru nebylo na emulátoru ověřeno.
- [ ] **Step 4: Commit** — `feat: panorama guide UI`

### Task 5: Vydání 0.5.0

- [ ] **Step 1:** `versionCode = 5`, `versionName = "0.5.0"`; testy, lint, `assembleRelease`, podpis.
- [ ] **Step 2:** Smoke test release přes `adb install -r` nad 0.4.0 (živá fotka, video, časosběr, panorama UI).
- [ ] **Step 3:** `git push`, `gh release create release-0.5.0 … --prerelease`. České poznámky musí poprosit o vyzkoušení panoramatu venku: na emulátoru se skládání ze skutečného otáčení ověřit nedá.
