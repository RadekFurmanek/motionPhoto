# Vlastní portrét: implementační plán

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Režim PORTRÉT na telefonech bez portrétu výrobce: živě rozmazané pozadí v náhledu, po stisku zpracování v plném (nebo standardním) rozlišení přes ML Kit Subject Segmentation. Vydat jako 0.6.0.

**Architecture:**
- **Čisté jádro** (`portrait/PortraitMath.kt`, JVM testy): rozmazání, změkčení masky, skládání, výpočet velikostí. Pracuje nad `Raster` z `panorama/Raster.kt` (ARGB `IntArray`).
- **Android vrstva:**
  - `SubjectMasker` obaluje ML Kit: dostupnost, stažení modelu, maska popředí.
  - `PortraitProcessor` zpracuje uložený JPEG po pruzích přímo v `Bitmap`. Pixely bitmapy jsou od API 26 v nativní paměti, ne na Java heapu.
  - `PortraitPreview` dělá z `ImageAnalysis` snímků rozmazaný náhled. UI ho kreslí jako překryv nad `PreviewView`, který zůstává pod ním (pro měření ťuknutím a jako záloha).

**Tech Stack:** jako etapa 4 + `com.google.android.gms:play-services-mlkit-subject-segmentation`, `play-services-base` (ModuleInstall), `kotlinx-coroutines-play-services` (`await()`).

**Spec:** `docs/superpowers/specs/2026-10-27-vlastni-portret-design.md`

## Global Constraints

- Vlastní portrét jen tam, kde **není** `ExtensionMode.BOKEH`. Kde je, zůstává portrét výrobce beze změny.
- Režim se nezobrazí bez služeb Google Play. Zadní i přední kamera.
- Ukládá se **jen portrét**, `PXL_yyyyMMdd_HHmmss.jpg` (UTC), `DCIM/Camera`, EXIF orientace a poloha jako u Foto. Při selhání, nenalezení objektu nebo OOM se uloží nezpracovaná fotka a zobrazí se hláška.
- Nastavení „Rozlišení portrétu“:
  - **Plné** (výchozí): co dá `ImageCapture` jako ve Foto. Zpracování do ~5 s.
  - **Standardní**: ~12 Mpx, zpracování 1–3 s.
- Náhled 15–20 snímků/s, maska 3–5×/s. Bez objektu je náhled celý ostrý. Musí fungovat na minSdk 29 (žádný `RenderEffect`).
- Okraj objektu se změkčí (feather), žádný tvrdý výstřižek.
- Bez živé fotky a HDR, poměr 4:3. Zaostřování jako ve Foto. Během zpracování se točí oblouk na spoušti (`processing`) a spoušť je zakázaná.
- Bez nativních knihoven. Ostatní režimy se nesmí změnit ani zpomalit.
- `versionCode` 7, `versionName` "0.6.0".

## Review Focus

1. **Otočený telefon a přední kamera:** maska musí sedět na objekt ve výsledné fotce i v náhledu. Segmentuje se ve stejném prostoru pixelů, v jakém se skládá. EXIF orientace se nemění, zrcadlení náhledu přední kamery odpovídá `PreviewView` (Task 1 test, Task 2, Task 4).
2. **Model ještě není stažený nebo chybí internet:** náhled je ostrý, fotka se uloží nezpracovaná s hláškou `portrait_model_downloading`. Stažení se spustí při vstupu do režimu (Task 2, Task 3).
3. **50 Mpx a málo paměti:** `OutOfMemoryError` při zpracování neztratí fotku. Uloží se nezpracovaná s hláškou (Task 2).
4. **Nic nenalezeno** (krajina, bílá zeď): fotka se uloží nezpracovaná s hláškou `portrait_no_subject`, náhled je celý ostrý (Task 1 test `hasSubject`, Task 2).
5. **Odchod z aplikace nebo přepnutí režimu během zpracování:** zpracování doběhne a fotka se uloží (Task 3).

---

### Task 1: Čisté jádro portrétu

**Files:**
- Create: `app/src/main/java/cz/furmanek/motionphoto/portrait/PortraitMath.kt`
- Modify: `app/src/main/java/cz/furmanek/motionphoto/settings/CameraSettings.kt` (+ `enum class PortraitResolution { FULL, STANDARD }`, pole `portraitResolution: PortraitResolution = PortraitResolution.FULL`), `settings/SettingsRepository.kt`
- Test: `app/src/test/java/cz/furmanek/motionphoto/portrait/PortraitMathTest.kt`

**Interfaces:**
- Produces (vše v `PortraitMath.kt`):
  - `fun boxBlur(src: Raster, radius: Int): Raster`: separabilní box blur, 3 průchody (aproximace Gaussu), okraje se opakují.
  - `fun blurMask(mask: FloatArray, width: Int, height: Int, radius: Int): FloatArray`: totéž pro masku 0..1 (feather).
  - `fun hasSubject(mask: FloatArray, threshold: Float = 0.5f, minFraction: Float = 0.01f): Boolean`
  - `fun blend(sharp: Int, blurred: Int, alpha: Float): Int`: ARGB, `alpha` = jistota popředí (1 → ostrý). Výsledek je neprůhledný.
  - `fun maskAt(mask: FloatArray, maskWidth: Int, maskHeight: Int, x: Float, y: Float): Float`: bilineárně, souřadnice v pixelech masky.
  - `fun standardSampleSize(width: Int, height: Int): Int`: nejmenší mocnina 2, aby `w*h/s² ≤ 12_600_000`.
  - `fun fitInside(width: Int, height: Int, maxSide: Int): Pair<Int, Int>`: zmenšení se zachováním poměru, nikdy nezvětší.
  - `PortraitResolution` v `CameraSettings.kt`.

- [ ] **Step 1: Testy**

```kotlin
@Test fun standardSampleSize_halvesA50MpSensor() = assertEquals(2, standardSampleSize(8160, 6120))
@Test fun standardSampleSize_keeps12Mp() = assertEquals(1, standardSampleSize(4080, 3072))
@Test fun fitInside_neverUpscales() = assertEquals(800 to 600, fitInside(800, 600, 1024))
@Test fun fitInside_keepsAspect() = assertEquals(1024 to 768, fitInside(4000, 3000, 1024))
@Test fun hasSubject_falseForEmptyMask() = assertFalse(hasSubject(FloatArray(10_000)))
@Test fun hasSubject_falseBelowOnePercent() = assertFalse(hasSubject(FloatArray(10_000) { if (it < 50) 1f else 0f }))
@Test fun hasSubject_trueForSubject() = assertTrue(hasSubject(FloatArray(10_000) { if (it < 500) 0.9f else 0f }))
@Test fun blend_fullConfidenceKeepsSharp() = assertEquals(0xFF102030.toInt(), blend(0xFF102030.toInt(), 0xFFFFFFFF.toInt(), 1f))
@Test fun blend_zeroConfidenceTakesBlurred() = assertEquals(0xFFFFFFFF.toInt(), blend(0xFF102030.toInt(), 0xFFFFFFFF.toInt(), 0f))
@Test fun blend_halfway()                  // blend(0xFF000000, 0xFFFFFFFF, 0.5f) → each channel 127 or 128, alpha 0xFF
@Test fun boxBlur_uniformImageUnchanged()   // 20×20 all 0xFF404040 → all 0xFF404040
@Test fun boxBlur_spreadsASinglePoint()     // white pixel at centre of black 21×21, radius 3 → centre < 255, neighbour (13,10) > 0, corner (0,0) == 0
@Test fun blurMask_softensHardEdge()        // mask 1 for x<10 else 0 (20×1), radius 2 → value at x=9 in (0.3,0.9), at x=10 in (0.1,0.7), at x=0 == 1, at x=19 == 0
@Test fun maskAt_interpolates()             // 2×1 mask [0,1] → maskAt(x=0.5,y=0) == 0.5
@Test fun maskAt_clampsOutside()            // maskAt(x=-3, y=5) on 2×1 [0,1] == 0
```
(Komentáře u testů bez těla popisují vstup a aserce. Implementer je zapíše jako kód.)

- [ ] **Step 2:** `./gradlew :app:testDebugUnitTest --tests '*PortraitMathTest*'` → FAIL (nekompiluje).
- [ ] **Step 3:** Implementuj `PortraitMath.kt` a `PortraitResolution`. Pak doplň `SettingsRepository` read/write pro `portraitResolution` stejně jako ostatní enumy (`enum(...)`).
- [ ] **Step 4:** `./gradlew :app:testDebugUnitTest` → PASS.
- [ ] **Step 5: Commit**: `feat: portrait compositing core`

### Task 2: Maska ML Kit a zpracování fotky

**Files:**
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts`: tři závislosti z Tech Stacku. Vezmi nejnovější vydané verze, které jsou aspoň 2 týdny staré, a zapiš je do `libs.versions.toml`.
- Create: `app/src/main/java/cz/furmanek/motionphoto/portrait/SubjectMasker.kt`, `app/src/main/java/cz/furmanek/motionphoto/portrait/PortraitProcessor.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: Task 1.
- Produces:
  - `class Mask(val width: Int, val height: Int, val confidence: FloatArray)`
  - `interface SubjectMasker { val available: Boolean; suspend fun ensureModel(): Boolean; suspend fun mask(bitmap: Bitmap): Mask? }`:
    - `available` = služby Google Play jsou k dispozici (`GoogleApiAvailability … == SUCCESS`).
    - `ensureModel()` = model je nainstalovaný. Když není, spustí stažení přes `ModuleInstallClient` a vrátí false bez čekání.
    - `mask()` = `null`, když model není. Maska má rozměry vstupní bitmapy.
  - `class MlKitSubjectMasker(context: Context) : SubjectMasker`: `SubjectSegmentation.getClient(SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask().build())`. Klient se zavírá v `close()`.
  - `enum class PortraitOutcome { PORTRAIT, NO_SUBJECT, MODEL_MISSING, OUT_OF_MEMORY, FAILED }`
  - `class PortraitProcessor(masker: SubjectMasker) { suspend fun process(jpeg: File, resolution: PortraitResolution): PortraitOutcome }`: přepíše `jpeg` na místě. Při jiném výsledku než `PORTRAIT` nechá soubor beze změny. Běží na `Dispatchers.Default`.
  - Řetězce: `mode_portrait` (už existuje), `portrait_no_subject` „Portrét se nepovedl, nic jsem nenašel – uložena běžná fotka“, `portrait_model_downloading` „Portrét se připravuje (stahuje se model) – uložena běžná fotka“, `portrait_failed` „Portrét se nepovedl – uložena běžná fotka“, `settings_portrait_resolution` „Rozlišení portrétu“, `portrait_resolution_full` „Plné“, `portrait_resolution_standard` „Standardní (12 Mpx)“.

`process` po krocích:
1. **Dekódování:** dekóduj `jpeg` do mutable `ARGB_8888`. U `STANDARD` s `inSampleSize = standardSampleSize`. Pixely zůstávají v uloženém (neotočeném) prostoru. EXIF orientaci si zapamatuj a do výsledku ji zapiš beze změny. Maska vzniká ze stejných pixelů, takže sedí bez ohledu na otočení.
2. **Maska:** zmenši na `fitInside(…, 1024)`. ML Kit dostane `InputImage.fromBitmap(small, 0)`. Když `!hasSubject` → `NO_SUBJECT`. Masku změkči přes `blurMask` s poloměrem ≈ 1 % delší strany masky.
3. **Pozadí:** z malé bitmapy `boxBlur` s poloměrem ≈ 2,5 % delší strany. Pro plnou fotku se pozadí čte bilineárně z tohoto malého rozmazaného rastru, aby se nealokoval druhý plný buffer.
4. **Skládání:** po pruzích 64 řádků přes `getPixels`/`setPixels`. Pro každý pixel `blend(sharp, blurredSample, maskAt(...))`.
5. **Uložení:** JPEG kvalita 95 do dočasného souboru, pak přes `ExifInterface` zkopíruj z originálu orientaci, datum a GPS (`saveAttributes`) a nahraď `jpeg`.
6. **Chyby:** `OutOfMemoryError` → `OUT_OF_MEMORY`, jiná výjimka → `FAILED`. Bitmapy se recyklují ve `finally`.

- [ ] **Step 1:** Přidej závislosti. `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL.
- [ ] **Step 2:** Implementuj `SubjectMasker.kt` a `PortraitProcessor.kt` podle Interfaces. Kroky 1–6 výše jsou pro okraje a paměť závazné.
- [ ] **Step 3:** `./gradlew -q :app:testDebugUnitTest :app:lintDebug` → bez chyb a bez nových varování (dnes 4).
- [ ] **Step 4: Commit**: `feat: portrait processing with ML Kit subject segmentation`

(Bez JVM testu: `Bitmap` a ML Kit potřebují zařízení. Logiku kryje Task 1, end-to-end Task 4.)

### Task 3: Režim Portrét v session

**Files:**
- Modify: `camera/CameraCapabilities.kt` (+ `customPortrait: Boolean = false`), `camera/CameraSession.kt`, `camera/Modes.kt`, `camera/BindingPlan.kt`, `ui/SettingsScreen.kt`, `ui/CameraScreen.kt` (jen `processing`)
- Test: `camera/ModesTest.kt`, `camera/BindingPlanTest.kt`

**Interfaces:**
- Consumes: Task 1 (`PortraitResolution`), Task 2 (`SubjectMasker`, `PortraitProcessor`, `PortraitOutcome`).
- Produces:
  - `availableModes`: `PORTRAIT ->` `BOKEH in extensionModes || capabilities.customPortrait`.
  - `fun usesCustomPortrait(mode: CaptureMode, capabilities: CameraCapabilities): Boolean` v `Modes.kt` = `mode == PORTRAIT && BOKEH !in extensionModes && customPortrait`.
  - `bindingAttempts(mode, livePhoto, videoExtras, customPortrait: Boolean = false)`: pro vlastní portrét `listOf(BindingAttempt(setOf(PREVIEW, IMAGE, ANALYSIS)), BindingAttempt(setOf(PREVIEW, IMAGE)))`. Druhý pokus je bez živého náhledu, ale fotka se pořád zpracuje. Portrét výrobce beze změny.
  - `CameraSession`:
    - `customPortrait = masker.available` jde do `CapabilityProbe.probe(...)`.
    - Při bindu vlastního portrétu se nepoužije `extensionMode`. Spustí se `masker.ensureModel()` (fire-and-forget).
    - V `shoot()` pro vlastní portrét:
      - `capturePhoto` do dočasného souboru (bez živé fotky);
      - `portraitProcessor.process(file, settings.portraitResolution)` pod `withContext(NonCancellable)`;
      - pak `writer.savePhoto`;
      - při jiném výsledku než `PORTRAIT` se vyšle `SessionEvent.CaptureFailed` s příslušným řetězcem z Task 2 a fotka se uloží stejně (`Saved` přijde taky).
    - Pokud `capturePhoto` dnes neumí vrátit soubor před uložením, rozděl ho na `captureToFile` + uložení. Ostatní volající nesmí změnit chování.
  - `CameraScreen`: `processing` platí i pro PORTRÉT bez ohledu na původ (dnes už `settings.mode == PORTRAIT`, ověř).
  - `SettingsScreen`: řádek „Rozlišení portrétu“ s volbou Plné / Standardní (12 Mpx). Zobrazí se, jen když je vlastní portrét dostupný.

- [ ] **Step 1: Testy**
  - `ModesTest`:
    - `availableModes(caps(customPortrait = true), BACK)` obsahuje PORTRAIT;
    - bez BOKEH a bez customPortrait PORTRAIT neobsahuje;
    - `usesCustomPortrait(PORTRAIT, caps(extensionModes = setOf(BOKEH), customPortrait = true)) == false`;
    - `usesCustomPortrait(PORTRAIT, caps(customPortrait = true)) == true`.
  - `BindingPlanTest`: `bindingAttempts(PORTRAIT, livePhoto = true, videoExtras = false, customPortrait = true)` == dva pokusy výše; `customPortrait = false` == `listOf(BindingAttempt(setOf(PREVIEW, IMAGE)))`.
- [ ] **Step 2:** FAIL, **Step 3:** implementuj, **Step 4:** `./gradlew -q :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` → PASS.
- [ ] **Step 5: Emulátor API 36:**
  - Režim PORTRÉT je v liště.
  - Fotka se uloží (přes `content query`, ne `adb pull`).
  - Během zpracování se točí oblouk.
  - Hláška odpovídá výsledku.
  - Když ML Kit na emulátoru nefunguje, zapiš to do ledgeru.
  - Pozor: emulátor má skoro plný disk. Instaluj až po `adb uninstall cz.furmanek.motionphoto`.
- [ ] **Step 6: Commit**: `feat: custom portrait mode`

### Task 4: Živý rozmazaný náhled

**Files:**
- Create: `app/src/main/java/cz/furmanek/motionphoto/portrait/PortraitPreview.kt`
- Modify: `camera/CameraSession.kt` (analyzer pro vlastní portrét, `analysisSize` → 960×720 pro PORTRAIT), `ui/CameraScreen.kt` (překryv)

**Interfaces:**
- Consumes: Task 1, Task 2 (`SubjectMasker`), Task 3 (binding s ANALYSIS).
- Produces:
  - `class PortraitPreview(masker: SubjectMasker, scope: CoroutineScope)`:
    - `fun start(analysis: ImageAnalysis, mirrored: Boolean)`, `fun stop()`.
    - `val frame: StateFlow<ImageBitmap?>`: `null` = kresli jen `PreviewView`, tedy když není maska nebo je bez objektu.
  - **Každý snímek:**
    - RGBA přes `rgbaToRaster` (`panorama/PanoramaCapture.kt`);
    - otočení přes `rotationDegrees` (`Raster.rotate`), u přední kamery zrcadlení jako `PreviewView`;
    - zmenšení na výšku 480 pro rozmazání, `boxBlur`;
    - složení s poslední maskou;
    - vydání do `frame`, nejvýš 20×/s.
  - **Maska:** nejvýš jedna segmentace naráz, ne častěji než každých 250 ms, na kopii zmenšené na `fitInside(…, 256)`.
  - `ImageProxy` se zavře hned po kopii.
  - `CameraScreen`: když `portraitFrame != null`, nad `PreviewView` se kreslí `Image(bitmap, contentScale = ContentScale.Fit)` ve stejné oblasti jako náhled (FIT_CENTER, 4:3). Taps jdou dál do `PreviewView` (překryv nepřijímá dotyky).

- [ ] **Step 1:** Implementuj.
- [ ] **Step 2:** `./gradlew -q :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` → bez chyb.
- [ ] **Step 3: Emulátor API 36:**
  - Screenshot náhledu v Portrétu: pozadí rozmazané, pokud ML Kit ve virtuální scéně něco najde. Jinak náhled ostrý a záznam do ledgeru.
  - Ověř, že překryv sedí přesně na `PreviewView`: přepni Foto ↔ Portrét a porovnej hrany.
  - Plynulost: z logcatu nebo časů snímků doložit ≥ 15 snímků/s náhledu.
- [ ] **Step 4: Commit**: `feat: live portrait preview`

### Task 5: Vydání 0.6.0

- [ ] **Step 1:** `versionCode = 7`, `versionName = "0.6.0"`. Testy, lint, `assembleRelease`, podpis stejný jako 0.5.1.
- [ ] **Step 2:** Smoke test release nad 0.5.1 na emulátoru: Foto, živá fotka, Portrét.
- [ ] **Step 3:** `git push`, `gh release create release-0.6.0 … --prerelease` s českými poznámkami. Poznámky požádají o vyzkoušení portrétu na člověku, zvířeti a věci a upozorní, že první portrét potřebuje internet (stažení modelu).
