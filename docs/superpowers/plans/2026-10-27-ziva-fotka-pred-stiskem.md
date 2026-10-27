# Živá fotka i před stiskem — implementační plán

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans (uživatel chce vše v jednom vlákně,
> bez subagentů). Kroky používají checkboxy (`- [ ]`).

**Goal:** Klip živé fotky obsahuje 1,5 s před stiskem a 1,5 s po něm, fotka je z okamžiku stisku.

**Architecture:** Ve Foto se zapnutou živou fotkou běží průběžný H.264 encoder (+ AAC zvuk) do kruhového
bufferu kódovaných vzorků v RAM (`livephoto/`). Po stisku se pořídí fotka, počká se na 1,5 s dotočení, z bufferu se
vybere úsek od klíčového snímku ~1,5 s před stiskem a zapíše `MediaMuxer`em do MP4. Pak jde stávající cestou
`MediaStoreWriter.saveMotionPhoto`. Když průběžný encoder nejde rozjet, zůstává dnešní cesta přes
`VideoCapture<Recorder>`.

**Tech Stack:** Kotlin, CameraX 1.6.2, MediaCodec/MediaMuxer/AudioRecord, coroutines, JUnit.

**Spec:** `docs/superpowers/specs/2026-10-27-ziva-fotka-pred-stiskem-design.md`

## Global Constraints

- minSdk 29, targetSdk 37, CameraX 1.6.2, žádné nativní knihovny navíc.
- Pre-roll 1,5 s, post-roll 1,5 s (`PRE_ROLL_MS = 1500`, `POST_ROLL_MS = 1500`). Tolerance začátku klipu ±0,5 s.
- Klip nejvýš 1080p, ~30 fps, zvuk jen s oprávněním `RECORD_AUDIO`.
- Pre-roll běží jen ve Foto + živá fotka + aplikace na popředí. Jinak nic navíc neběží.
- Selhání klipu ⇒ uloží se obyčejná fotka; selhání průběžného encoderu ⇒ dnešní živá fotka (natáčení po stisku).
- Formát výsledku beze změny (`PXL_….MP.jpg`, `MotionPhotoMuxer`). Verze **0.7.0** (versionCode 9).
- Před předáním APK smoke test **release** buildu (R8) na emulátoru.

## Review Focus

1. Stisk dřív než 1,5 s po vstupu do Foto: klip začne prvním dostupným klíčovým snímkem, nic nespadne (test T2).
2. Přepnutí režimu / odchod z aplikace během dotáčení: fotka se uloží aspoň jako obyčejná (T4 krok ověření).
3. Přední kamera se zrcadlením: klip zrcadlený stejně jako fotka (T1 rozhodne jak, T4 ověří).
4. Telefon na šířku: orientace klipu odpovídá fotce (orientation hint z rotace v okamžiku stisku, T3).
5. Audio a video na různých hodinách: značka fotky i synchronizace zvuku počítané na jedněch hodinách (test T2).

---

### Task 1: Spike — jak dostat snímky do vlastního encoderu

Throwaway, necommitovat kód spiku (jen zápis výsledku do tohoto plánu).

- [ ] **Step 1:** Cesta A: vlastní `VideoOutput` (`onSurfaceRequested(request, timebase, …)` → `request.provideSurface(codec.createInputSurface())`) v `VideoCapture.withOutput(...)`, svázaný s Preview + ImageCapture. Ověřit na emulátoru API 36: bind projde, codec dostává snímky, jaké rozlišení CameraX zvolí, jaký `Timebase`, a zda `VideoCapture.Builder.setMirrorMode` funguje i s vlastním výstupem.
- [ ] **Step 2:** Pokud A selže: cesta B — `ImageAnalysis` YUV_420_888 (≤1080p, `KEEP_ONLY_LATEST`) → `codec.getInputImage()` kopie rovin; změřit fps. Zrcadlení přední kamery = převrácení řádků při kopii.
- [ ] **Step 3:** Zapsat sem `Ruling: cesta A|B — důvod — timebase — rozlišení — zrcadlení` a zda jde `CAPTURE_MODE_ZERO_SHUTTER_LAG` spolu s touto kombinací (`ImageCapture.isZslSupported`/bind).

### Task 2: Kruhový buffer a výběr klipu (čistá logika)

**Files:**
- Create: `app/src/main/java/cz/furmanek/motionphoto/livephoto/ClipBuffer.kt`
- Test: `app/src/test/java/cz/furmanek/motionphoto/livephoto/ClipBufferTest.kt`

**Interfaces — Produces:**
- `class EncodedSample(val track: Track, val timeUs: Long, val keyFrame: Boolean, val data: ByteArray)`; `enum class Track { VIDEO, AUDIO }`. `timeUs` je na jedněch hodinách (elapsedRealtime v µs).
- `class ClipBuffer(keepUs: Long = KEEP_US)` — `fun add(sample: EncodedSample)` (zahazuje video starší než nejnovější − `keepUs`, ale vždy ponechá klíčový snímek, od kterého lze začít; audio stejné okno); `fun clip(pressUs: Long, preUs: Long, postUs: Long): Clip?`; `fun clear()`; `val newestVideoUs: Long?`. `KEEP_US = 4_000_000` (v okamžiku výřezu je nejnovější snímek press + 1,5 s a potřebujeme sahat až press − 2,0 s).
- `class Clip(val video: List<EncodedSample>, val audio: List<EncodedSample>, val firstVideoUs: Long)`.
- `fun stillPositionUs(stillUs: Long, clip: Clip): Long` = `(stillUs − firstVideoUs).coerceIn(0, lastVideoUs − firstVideoUs)`.

- [ ] **Step 1: Testy** (video 30 fps, klíčový snímek každých 0,5 s, audio každých 20 ms):
  - `clipStartsAtKeyFrameNearestBeforePreRoll`: buffer 0–4 s, press 3,0 s ⇒ `firstVideoUs == 1_500_000`, poslední video ≤ 4_500_000 a ≥ 4_466_000 když buffer sahá do 4,5 s.
  - `clipBeforeKeyFrameRoundsDown`: press 3,2 s ⇒ start 1,5 s (klíčový snímek ≤ 1,7 s), ne 2,0 s.
  - `earlyPressStartsAtFirstKeyFrame`: buffer 0–2 s, press 0,6 s ⇒ `firstVideoUs == 0`.
  - `noKeyFrameReturnsNull`: jen ne-klíčové snímky ⇒ `null`.
  - `audioTrimmedToVideoRange`: audio jen v rozsahu `[firstVideoUs, lastVideoUs]`.
  - `oldSamplesDropped`: po přidání 0–10 s je nejstarší video ≥ 5,5 s a ≤ 6,0 s a je to klíčový snímek.
  - `stillPositionInsideClip`: still 3,0 s, start 1,5 s ⇒ 1_500_000; still před startem ⇒ 0.
- [ ] **Step 2:** `./gradlew testDebugUnitTest --tests '*ClipBufferTest'` ⇒ FAIL (nekompiluje se).
- [ ] **Step 3:** Implementace; buffer je thread-safe (`synchronized`), encoder thread přidává, capture čte.
- [ ] **Step 4:** Testy PASS.
- [ ] **Step 5:** Commit `feat: clip buffer for live photo pre-roll`.

### Task 3: Průběžný encoder

**Files:**
- Create: `app/src/main/java/cz/furmanek/motionphoto/livephoto/PreRollRecorder.kt`
- Create: `app/src/main/java/cz/furmanek/motionphoto/livephoto/ClipWriter.kt`
- Test: `app/src/test/java/cz/furmanek/motionphoto/livephoto/ClipWriterTest.kt` (jen čistá část: přepočet časů vzorků na PTS od 0)

**Interfaces:**
- Consumes: `ClipBuffer`, `EncodedSample`, `Clip`, `stillPositionUs` (T2); ruling T1.
- Produces:
  - `class PreRollRecorder(context: Context, withAudio: Boolean)` — podle T1 buď `val videoOutput: VideoOutput` (A), nebo `val analyzer: ImageAnalysis.Analyzer` (B); `fun start()`, `fun stop()` (uvolní codec, AudioRecord, vyprázdní buffer); `val failed: StateFlow<Boolean>`; `suspend fun cut(pressUs: Long, rotationDegrees: Int, out: File): Clip?` — počká, až `newestVideoUs ≥ pressUs + POST_ROLL_US` (timeout 3 s), vybere klip a zapíše ho; `null` = klip selhal.
  - `object ClipWriter { fun write(clip: Clip, videoFormat: MediaFormat, audioFormat: MediaFormat?, rotationDegrees: Int, out: File); fun relativeTimesUs(samples: List<EncodedSample>, originUs: Long): LongArray }`.
- [ ] **Step 1: Test** `relativeTimesStartAtZeroAndKeepSpacing`: origin 1_500_000, vzorky 1_500_000/1_533_333/1_566_666 ⇒ `[0, 33_333, 66_666]`; audio vzorek před originem se odfiltruje už v `clip` (T2), zde jen aritmetika.
- [ ] **Step 2:** FAIL, Step 3: implementace. Video: H.264, `KEY_I_FRAME_INTERVAL` tak, aby klíčový snímek byl každých 0,5 s (API 29 bere float sekundy přes `setFloat`), bitrate jako `TimelapseEncoder` (`BITS_PER_PIXEL`, strop). Výstupní formát (`INFO_OUTPUT_FORMAT_CHANGED`) si recorder drží pro `ClipWriter`. Časy vzorků převést na elapsedRealtime µs podle timebase z T1. Audio: `AudioRecord` 44,1 kHz mono → AAC-LC 128 kb/s, čas = elapsedRealtime v okamžiku čtení − délka bufferu.
- [ ] **Step 4:** PASS + `assembleDebug`.
- [ ] **Step 5:** Commit `feat: pre-roll recorder for live photos`.

### Task 4: Napojení do session, spouště a capture

**Files:**
- Modify: `camera/BindingPlan.kt` — PHOTO+livePhoto: `[photo + VIDEO (preRoll = true), photo + VIDEO, photo]`; `BindingAttempt` dostane `preRoll: Boolean = false`.
- Modify: `camera/CameraSession.kt` — při bindu s `preRoll` vytvořit `PreRollRecorder`, start po bindu, stop při unbindu/změně režimu/`onStop`; při `failed` rebind bez pre-rollu. Fotka v PHOTO+live s pre-rollem: `photoCapture.captureMotionPhotoPreRoll(...)`.
- Modify: `camera/PhotoCapture.kt` — `suspend fun captureMotionPhotoPreRoll(imageCapture, recorder: PreRollRecorder, capturedAtMillis, pressUs, rotationDegrees, mirrored, location): SavedMedia`: fotka ihned (čas `onCaptureStarted`), pak `recorder.cut(...)`, značka `stillPositionUs`; klip `null` ⇒ `savePhoto`. Konstanty `PRE_ROLL_MS = 1500L`, `POST_ROLL_MS = 1500L`.
- Modify: `ui/components/ShutterButton.kt` + `CameraScreen.kt` — červený kruh trvá `POST_ROLL_MS` při pre-rollu, `CLIP_DURATION_MS` při staré cestě (session vystaví `livePhotoPreRoll: Boolean` ve stavu).
- Test: `BindingPlanTest` — `photoLivePrefersPreRollThenRecorderThenStill`; `photo without live` beze změny.

- [ ] **Step 1:** Test BindingPlan ⇒ FAIL; implementace ⇒ PASS.
- [ ] **Step 2:** Session + capture + spoušť; ZSL podle rulingu T1.
- [ ] **Step 3:** Celé testy + `lintDebug`.
- [ ] **Step 4: Emulátor (release build):** živá fotka se uloží; `content query` ukáže `.MP.jpg`; vytáhnout přes `content read` a ověřit délku klipu ~3 s (`MotionPhotoMuxer`/ffprobe) a značku ~1,5 s; během 1,5 s před stiskem pohnout scénou emulátoru (pohyb je v klipu). Přepnout režim během dotáčení ⇒ uloží se fotka. Vypnout živou fotku ⇒ `dumpsys media.codec`/log: encoder neběží.
- [ ] **Step 5:** Commit `feat: live photo with pre-roll`.

### Task 5: Vydání 0.7.0

- [ ] **Step 1:** `versionCode = 9`, `versionName = "0.7.0"`; testy, lint, `assembleRelease`, podpis stejný jako 0.6.1.
- [ ] **Step 2:** Celková kontrola větve (sám, bez agenta), opravy.
- [ ] **Step 3:** Po souhlasu uživatele: merge do main, push, `gh release create release-0.7.0 --prerelease`, české poznámky (klip 1,5 s před a po stisku; prosba vyzkoušet zachycení okamžiku, Google Photos, teplotu/baterii).
