# Etapa 1 — jádro fotoaparátu: implementační plán

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Z aplikace na živé fotky udělat základ plnohodnotného fotoaparátu: režimy Foto (živá i obyčejná fotka) a Video
(i na pozadí), nastavení, objektivy, náhled posledního snímku a volání z jiných aplikací. Vydat jako 0.2.0.

**Architecture:** Kameru vlastní aplikačně-scopovaná `CameraSession` s vlastním `LifecycleOwner`. Ten je STARTED, když je
vidět UI **nebo** běží nahrávání, takže nahrávání přežije zánik Activity. Activity jen připojuje náhled. Během nahrávání
drží proces foreground service `RecordingService`. Čistá logika (pojmenování, výběr voleb, pravidla nastavení, časování)
je v souborech bez Android závislostí a má JVM unit testy. CameraX část se ověřuje na emulátoru.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), CameraX 1.6.2, DataStore Preferences 1.3.0, Adobe XMPCore 6.1.11.

**Spec:** `docs/superpowers/specs/2026-10-26-full-camera-design.md`

## Global Constraints

- minSdk 29, compileSdk/targetSdk 37, AGP 9.4.1, Kotlin 2.4.20, CameraX 1.6.2. Nové závislosti jen `androidx.datastore:datastore-preferences:1.3.0` (a `-core` pro testy). Žádné OpenCV ani nativní knihovny.
- Ukládání do `DCIM/Camera` přes MediaStore. Jména v UTC: `PXL_yyyyMMdd_HHmmss.MP.jpg` (živá fotka), `PXL_yyyyMMdd_HHmmss.jpg` (fotka), `PXL_yyyyMMdd_HHmmss.mp4` (video).
- Formát živé fotky se nemění (`MotionPhotoMuxer` beze změny chování; jeho testy musí dál procházet).
- Co kamera neumí, se v UI nezobrazí. Žádná volba, která na daném telefonu selže.
- Ukládání, které selže, musí uživatel vidět (snackbar, u nahrávání na pozadí notifikace).
- UI texty česky ve `strings.xml`.
- Release podepsaný klíčem z `keystore.properties`, `versionCode` 2, `versionName` "0.2.0".

## Review Focus

1. **Přepnutí kamery, objektivu nebo režimu během nahrávání** nesmí nahrávání rozbít. Tyto volby jsou během nahrávání zakázané (Task 4, Task 7).
2. **Nahrávání bez oprávnění RECORD_AUDIO** proběhne bez zvuku. Service se nesmí spustit s typem `microphone`, jinak Android 14+ vyhodí SecurityException (Task 6: `foregroundServiceTypes`).
3. **Nedostatek místa nebo přerušení nahrávání** (jiná aplikace převezme kameru, ERROR_INSUFFICIENT_STORAGE, ERROR_SOURCE_INACTIVE): co se natočilo, se uloží a uživatel dostane notifikaci nebo snackbar (Task 6: `classifyFinalize`).
4. **Návrat do aplikace během nahrávání na pozadí** (i po zániku Activity): UI se připojí k běžícímu nahrávání a ukáže čas a stop (Task 6 a Task 7, emulátorový krok).
5. **Kamera nedostupná** (jiná aplikace ji drží, telefon bez zadní kamery): hláška, žádný pád, spoušť zakázaná (Task 4).

---

## Struktura souborů

```
app/src/main/java/cz/furmanek/motionphoto/
  MotionPhotoApp.kt                 Application; drží CameraSession, SettingsRepository, MediaStoreWriter
  MainActivity.kt                   (upravit) volume key, připojení k session
  CaptureIntentActivity.kt          ACTION_IMAGE_CAPTURE / ACTION_VIDEO_CAPTURE
  settings/CameraSettings.kt        data class + enumy, čistá pravidla (effectiveLivePhoto…)
  settings/SettingsRepository.kt    DataStore <-> CameraSettings
  media/MediaNaming.kt              jména souborů (čisté)
  media/MediaStoreWriter.kt         nahrazuje MotionPhotoStore: fotky, živé fotky, video výstup
  motionphoto/MotionPhotoMuxer.kt   beze změny
  camera/CameraCapabilities.kt      data + čisté odvození voleb (zoom předvolby, video volby)
  camera/CapabilityProbe.kt         CameraInfo -> CameraCapabilities (Android)
  camera/CameraSession.kt           lifecycle, binding podle režimu, ovládání kamery, stav
  camera/PhotoCapture.kt            obyčejná fotka + živá fotka (přesunuto z CameraViewModel)
  camera/VideoRecorder.kt           nahrávání videa, pauza, finalize
  camera/RecordingService.kt        foreground service + notifikace
  location/LocationProvider.kt      poslední/aktuální poloha přes LocationManager
  ui/CameraViewModel.kt             (přesunout z camera/) UI stav nad CameraSession
  ui/CameraScreen.kt                (upravit) skládá komponenty níže
  ui/components/ModeBar.kt, QuickSettingsBar.kt, ShutterButton.kt, ZoomChips.kt,
               ExposureSlider.kt, GridOverlay.kt, LastCaptureThumbnail.kt, RecordingIndicator.kt
  ui/SettingsScreen.kt
  ui/CameraPermissionGate.kt        (beze změny)
app/src/test/java/cz/furmanek/motionphoto/
  settings/CameraSettingsTest.kt, settings/SettingsRepositoryTest.kt, media/MediaNamingTest.kt,
  camera/CameraCapabilitiesTest.kt, camera/MotionPhotoTimingTest.kt, camera/VideoRecorderRulesTest.kt
```

---

### Task 1: Nastavení — model a DataStore

**Files:**
- Create: `settings/CameraSettings.kt`, `settings/SettingsRepository.kt`
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts` (datastore-preferences 1.3.0 do `implementation`, datastore-preferences-core 1.3.0 do `testImplementation`, kotlinx-coroutines-test 1.11.0 do `testImplementation`)
- Test: `settings/CameraSettingsTest.kt`, `settings/SettingsRepositoryTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  enum class CaptureMode { PHOTO, VIDEO }
  enum class FlashSetting { OFF, AUTO, ON }
  enum class TimerSetting(val seconds: Int) { OFF(0), THREE(3), TEN(10) }
  enum class PhotoAspect { RATIO_4_3, RATIO_16_9, RATIO_1_1 }
  enum class VideoQualitySetting { SD, HD, FHD, UHD }
  data class CameraSettings(
      val mode: CaptureMode = CaptureMode.PHOTO,
      val lensFacing: Int = CameraSelector.LENS_FACING_BACK,  // Int konstanta, ne CameraX typ v testech
      val livePhoto: Boolean = true,
      val flash: FlashSetting = FlashSetting.OFF,
      val timer: TimerSetting = TimerSetting.OFF,
      val aspect: PhotoAspect = PhotoAspect.RATIO_4_3,
      val videoQuality: VideoQualitySetting = VideoQualitySetting.FHD,
      val videoFps60: Boolean = false,
      val videoStabilization: Boolean = true,
      val torch: Boolean = false,
      val grid: Boolean = false,
      val saveLocation: Boolean = false,
      val shutterSound: Boolean = true,
      val volumeKeyShutter: Boolean = true,
      val mirrorFrontCamera: Boolean = false,
  )
  /** Živá fotka se pořizuje jen bez blesku (spec: blesk vypne živou fotku pro daný snímek). */
  fun CameraSettings.effectiveLivePhoto(): Boolean = livePhoto && flash == FlashSetting.OFF
  fun CameraSettings.shutterSoundRequired(mustPlay: Boolean): Boolean = shutterSound || mustPlay
  class SettingsRepository(private val dataStore: DataStore<Preferences>) {
      val settings: Flow<CameraSettings>
      suspend fun update(transform: (CameraSettings) -> CameraSettings)
  }
  ```
  `lensFacing` jako `Int` s konstantami `LENS_FACING_BACK = 1`, `LENS_FACING_FRONT = 0` (stejné hodnoty jako CameraX), aby soubor neimportoval CameraX.

- [ ] **Step 1: Testy pravidel** v `CameraSettingsTest`:
  `effectiveLivePhoto` je true jen pro `livePhoto=true, flash=OFF`; pro `AUTO` i `ON` false; pro `livePhoto=false` vždy false.
  `shutterSoundRequired(mustPlay=true)` je true i při `shutterSound=false`.
- [ ] **Step 2: Test repozitáře** v `SettingsRepositoryTest` (DataStore přes `PreferenceDataStoreFactory.create { tmpFile.preferencesDataStoreFile }`, `runTest`):
  bez uložených dat `settings.first() == CameraSettings()`; po `update { it.copy(flash = AUTO, timer = TEN, aspect = RATIO_1_1, mode = VIDEO) }` vrací nový repozitář nad stejným souborem tytéž hodnoty; neznámá hodnota enumu v souboru (ručně zapsané `"flash" to "BOGUS"`) vrací výchozí `OFF`.
- [ ] **Step 3: Spusť testy, očekávej FAIL** — `./gradlew :app:testDebugUnitTest --tests '*Settings*'`
- [ ] **Step 4: Implementuj** — enumy ukládej jménem (`stringPreferencesKey`), čtení přes `runCatching { valueOf() }` s výchozí hodnotou.
- [ ] **Step 5: Testy PASS** — stejný příkaz.
- [ ] **Step 6: Commit** — `feat: camera settings persisted in DataStore`

### Task 2: Pojmenování a ukládání médií

**Files:**
- Create: `media/MediaNaming.kt`, `media/MediaStoreWriter.kt`
- Delete: `motionphoto/MotionPhotoStore.kt` (jeho logika přejde do `MediaStoreWriter`)
- Test: `media/MediaNamingTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  enum class MediaKind(val suffix: String, val mimeType: String) {
      MOTION_PHOTO(".MP.jpg", "image/jpeg"), PHOTO(".jpg", "image/jpeg"), VIDEO(".mp4", "video/mp4")
  }
  object MediaNaming { fun fileName(kind: MediaKind, capturedAtMillis: Long): String }  // "PXL_" + UTC yyyyMMdd_HHmmss + suffix
  class MediaStoreWriter(private val resolver: ContentResolver) {
      fun saveMotionPhoto(jpeg: File, video: File, presentationTimestampUs: Long, capturedAtMillis: Long, location: Location?): Uri
      fun savePhoto(jpeg: File, capturedAtMillis: Long, location: Location?): Uri
      /** Video zapisuje Recorder rovnou do MediaStore (MediaStoreOutputOptions); vrací volby pro VideoRecorder. */
      fun videoOutputOptions(capturedAtMillis: Long, location: Location?): MediaStoreOutputOptions
  }
  ```
  Polohu fotek zapisuj do EXIF přes `androidx.exifinterface` (přidat `androidx.exifinterface:exifinterface:1.4.1`) **před** muxem, protože muxer pracuje s hotovým JPEG. U videa použij `MediaStoreOutputOptions.Builder.setLocation`. Pending/cleanup chování převezmi z `MotionPhotoStore` (IS_PENDING, smazání při chybě).

- [ ] **Step 1: Testy** `MediaNamingTest`: `fileName(MOTION_PHOTO, 1_761_498_818_000)` == `"PXL_20251026_171338.MP.jpg"`; `PHOTO` končí `.jpg` bez `.MP`; `VIDEO` končí `.mp4`; čas se formátuje v UTC nezávisle na `TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))`.
- [ ] **Step 2: FAIL** — `./gradlew :app:testDebugUnitTest --tests '*MediaNaming*'`
- [ ] **Step 3: Implementuj** `MediaNaming` a `MediaStoreWriter`; aktualizuj volající (`CameraViewModel`) na `MediaStoreWriter.saveMotionPhoto`.
- [ ] **Step 4: PASS** — `./gradlew :app:testDebugUnitTest` (včetně `MotionPhotoMuxerTest`)
- [ ] **Step 5: Commit** — `refactor: MediaStoreWriter for photos, motion photos and video`

### Task 3: Schopnosti kamery

**Files:**
- Create: `camera/CameraCapabilities.kt` (čisté), `camera/CapabilityProbe.kt` (Android)
- Test: `camera/CameraCapabilitiesTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  data class LensOption(val cameraId: String, val lensFacing: Int, val label: String)  // label např. "UW", "Tele" podle ohniska
  data class CameraCapabilities(
      val hasFlash: Boolean,
      val minZoom: Float, val maxZoom: Float,
      val exposureRange: IntRange, val exposureStep: Float,
      val videoQualities: List<VideoQualitySetting>,          // seřazené od nejnižší
      val fps60Qualities: Set<VideoQualitySetting>,
      val videoStabilization: Boolean,
      val backLenses: List<LensOption>,                        // samostatné zadní kamery, prázdné když je jen jedna
      val hasFrontCamera: Boolean, val hasBackCamera: Boolean,
  )
  /** Předvolby zoomu: 0.5 (když minZoom <= 0.6), 1, 2, 5, 10 — jen ty v [minZoom, maxZoom]; 1 vždy. */
  fun zoomPresets(minZoom: Float, maxZoom: Float): List<Float>
  /** Kvalita, kterou lze použít: požadovaná, jinak nejbližší nižší, jinak nejnižší dostupná; null když video nejde. */
  fun resolveVideoQuality(requested: VideoQualitySetting, available: List<VideoQualitySetting>): VideoQualitySetting?
  object CapabilityProbe { fun probe(provider: ProcessCameraProvider, cameraInfo: CameraInfo): CameraCapabilities }
  ```
  `CapabilityProbe` používá `Recorder.getVideoCapabilities(cameraInfo).getSupportedQualities(DynamicRange.SDR)`, `isStabilizationSupported`, `cameraInfo.hasFlashUnit()`, `zoomState`, `exposureState`, `cameraInfo.supportedFrameRateRanges` (60 fps = obsahuje `Range(60, 60)` nebo horní mez ≥ 60 u fixního rozsahu) a `provider.availableCameraInfos` pro zadní objektivy (`Camera2CameraInfo` + `LENS_INFO_AVAILABLE_FOCAL_LENGTHS`).

- [ ] **Step 1: Testy** `CameraCapabilitiesTest`:
  `zoomPresets(0.5f, 10f) == listOf(0.5f, 1f, 2f, 5f, 10f)`; `zoomPresets(1f, 8f) == listOf(1f, 2f, 5f)`; `zoomPresets(1f, 1f) == listOf(1f)`;
  `resolveVideoQuality(UHD, listOf(SD, HD, FHD)) == FHD`; `resolveVideoQuality(SD, listOf(HD, FHD)) == HD`; `resolveVideoQuality(FHD, emptyList()) == null`.
- [ ] **Step 2: FAIL**, **Step 3: implementuj**, **Step 4: PASS** — `./gradlew :app:testDebugUnitTest --tests '*CameraCapabilities*'`
- [ ] **Step 5: Commit** — `feat: camera capability model`

### Task 4: CameraSession — vlastnictví kamery a binding podle režimu

**Files:**
- Create: `MotionPhotoApp.kt` (zaregistrovat v manifestu `android:name`), `camera/CameraSession.kt`
- Move: `camera/CameraViewModel.kt` → `ui/CameraViewModel.kt` (bude jen překládat UI akce na `CameraSession`)

**Interfaces:**
- Consumes: `CameraSettings`, `SettingsRepository` (Task 1), `CameraCapabilities`, `CapabilityProbe`, `zoomPresets`, `resolveVideoQuality` (Task 3)
- Produces:
  ```kotlin
  data class SessionState(
      val status: Status,                       // CLOSED, OPENING, READY, ERROR
      val errorMessage: String? = null,
      val capabilities: CameraCapabilities? = null,
      val activeCameraId: String? = null,
      val zoomRatio: Float = 1f,
      val exposureIndex: Int = 0,
      val recording: RecordingState? = null,    // z Task 6
      val busy: Boolean = false,                // probíhá focení/živá fotka
  )
  class CameraSession(app: Application, settings: SettingsRepository, writer: MediaStoreWriter) : LifecycleOwner {
      val state: StateFlow<SessionState>
      fun attachPreview(surfaceProvider: Preview.SurfaceProvider, viewPortRotation: Int, portrait: Boolean, meteringFactory: MeteringPointFactory)
      fun detachPreview()
      fun onUiVisible(visible: Boolean)          // lifecycle STARTED když visible || recording != null
      fun selectLens(cameraId: String)
      fun setZoom(ratio: Float); fun zoomBy(factor: Float)
      fun focusAt(x: Float, y: Float)
      fun setExposureIndex(index: Int)
      fun setTargetRotation(rotation: Int)
  }
  ```
  Binding podle `settings.mode`: **PHOTO** s `effectiveLivePhoto()` = Preview + ImageCapture + VideoCapture (jako 0.1.0); PHOTO bez živé fotky = Preview + ImageCapture; **VIDEO** = Preview + VideoCapture (kvalita z `resolveVideoQuality`, 60 fps přes `VideoCapture.Builder.setTargetFrameRate(Range(60, 60))` jen když je kvalita v `fps60Qualities`, stabilizace jen když ji capabilities mají). Vždy `UseCaseGroup` s `ViewPort` podle `aspect` (4:3 → 3/4 na výšku, 16:9 → 9/16, 1:1 → 1/1). Při změně nastavení, které mění binding, převaž; **nikdy během nahrávání** (požadavky na změnu režimu/kamery/objektivu během nahrávání se ignorují a UI je má zakázané). Flash mode pro ImageCapture z `FlashSetting`, torch z `torch` jen ve VIDEO. Chyba bindingu nebo `CameraState.ERROR_CAMERA_IN_USE`/`ERROR_CAMERA_DISABLED` z `cameraInfo.cameraState` → `status = ERROR` s českou hláškou (Review Focus 5).

- [ ] **Step 1: Implementuj `CameraSession`** a přepoj `CameraViewModel` + `CameraScreen` na ni (stávající funkce: náhled, ostření, zoom, přepnutí kamery, živá fotka musí fungovat beze změny).
- [ ] **Step 2: Build + testy** — `./gradlew :app:assembleDebug :app:testDebugUnitTest` → BUILD SUCCESSFUL
- [ ] **Step 3: Emulátor** — spusť `Medium_Phone_API_36.1` (`-no-window -gpu swiftshader_indirect`), nainstaluj s `-g`, vyfoť živou fotku, ověř nový soubor v `content://media/external/images/media` a jeho XMP stejně jako u 0.1.0 (`GCamera:MotionPhoto="1"`, `Item:Length` = délka koncového MP4, před MP4 `ffd9`).
- [ ] **Step 4: Commit** — `refactor: app-scoped CameraSession owns the camera`

### Task 5: Focení — obyčejná a živá fotka, samospoušť, zvuk, zrcadlení, poloha

**Files:**
- Create: `camera/PhotoCapture.kt`, `location/LocationProvider.kt`
- Modify: `camera/CameraSession.kt` (přidat `suspend fun takePhoto(): Uri`), manifest (`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`)
- Test: `camera/MotionPhotoTimingTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  /** Pozice fotky v klipu (µs) kotvená od konce klipu — přesunuto z CameraViewModel 0.1.0. */
  fun motionPhotoPresentationTimestampUs(stillNs: Long, stopNs: Long, clipDurationNs: Long): Long
  class PhotoCapture(/* ImageCapture, VideoCapture?, writer, location */) {
      suspend fun capturePhoto(settings: CameraSettings, isFrontCamera: Boolean): Uri
      suspend fun captureMotionPhoto(settings: CameraSettings, isFrontCamera: Boolean): Uri
  }
  class LocationProvider(context: Context) { suspend fun currentOrNull(): Location? }  // bez oprávnění null; timeout 2 s, jinak poslední známá
  ```
  `CameraSession.takePhoto()`: odpočet samospouště (`timer.seconds`, stav `countdown: Int?` v `SessionState`), zvuk `MediaActionSound.SHUTTER_CLICK` když `shutterSoundRequired(MediaActionSound.mustPlayShutterSound())` (API 33+, jinak `false`), pak `captureMotionPhoto` když `effectiveLivePhoto()`, jinak `capturePhoto`. Zrcadlení přední kamery: `ImageCapture.Metadata.isReversedHorizontal` a `VideoCapture` s `MirrorMode.MIRROR_MODE_ON_FRONT_ONLY` — obojí jen když `mirrorFrontCamera` (spec: fotka i video stejně). Poloha jen když `saveLocation` a oprávnění je udělené.

- [ ] **Step 1: Testy** `MotionPhotoTimingTest`: `motionPhotoPresentationTimestampUs(stillNs = 1_500_000_000, stopNs = 3_000_000_000, clipDurationNs = 2_000_000_000) == 500_000`; fotka před začátkem klipu → `0`; fotka po konci → `clipDurationNs / 1000`.
- [ ] **Step 2: FAIL**, **Step 3: implementuj**, **Step 4: PASS** — `./gradlew :app:testDebugUnitTest --tests '*MotionPhotoTiming*'`
- [ ] **Step 5: Emulátor** — fotka s vypnutou živou fotkou → `PXL_…jpg` bez XMP `GCamera`; s bleskem AUTO a zapnutou živou fotkou → obyčejná fotka; samospoušť 3 s → soubor vznikne ≥ 3 s po stisku.
- [ ] **Step 6: Commit** — `feat: photo capture with timer, sound, mirroring and location`

### Task 6: Video a nahrávání na pozadí

**Files:**
- Create: `camera/VideoRecorder.kt`, `camera/RecordingService.kt`
- Modify: `camera/CameraSession.kt` (`startRecording()`, `pauseRecording()`, `resumeRecording()`, `stopRecording()`), manifest (service s `android:foregroundServiceType="camera|microphone"`, oprávnění `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CAMERA`, `FOREGROUND_SERVICE_MICROPHONE`, `POST_NOTIFICATIONS`)
- Test: `camera/VideoRecorderRulesTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  data class RecordingState(val startedAtElapsedMs: Long, val durationNanos: Long, val paused: Boolean, val withAudio: Boolean)
  enum class FinalizeOutcome { SAVED, SAVED_INTERRUPTED, FAILED }
  /** ERROR_NONE → SAVED; INSUFFICIENT_STORAGE, SOURCE_INACTIVE, FILE_SIZE_LIMIT, DURATION_LIMIT → SAVED_INTERRUPTED; ostatní → FAILED. */
  fun classifyFinalize(error: Int): FinalizeOutcome
  /** Typy FGS: CAMERA vždy, MICROPHONE jen s audiem. */
  fun foregroundServiceTypes(withAudio: Boolean): Int
  class RecordingService : LifecycleService  // ACTION_START / ACTION_STOP; notifikace s délkou a tlačítkem Zastavit
  ```
  `startRecording()`: spusť `RecordingService` (`ContextCompat.startForegroundService`, uvnitř `ServiceCompat.startForeground(..., foregroundServiceTypes(withAudio))`), lifecycle session drž STARTED po dobu nahrávání, nahrávej do `writer.videoOutputOptions()` s audiem jen s oprávněním. Stav z `VideoRecordEvent.Status` → `RecordingState`. `Finalize` → `classifyFinalize`: SAVED a SAVED_INTERRUPTED = uri do „poslední snímek“; SAVED_INTERRUPTED a FAILED → notifikace (je-li UI skryté) / snackbar. Stop z notifikace → `stopRecording()`. Po finalize stopni service a vrať lifecycle podle viditelnosti UI. Torch během nahrávání podle `settings.torch`. Při `Finalize` s chybou se už vzniklý soubor nemaže (je platný).

- [ ] **Step 1: Testy** `VideoRecorderRulesTest`: `classifyFinalize(ERROR_NONE) == SAVED`; `classifyFinalize(ERROR_INSUFFICIENT_STORAGE) == SAVED_INTERRUPTED`; `classifyFinalize(ERROR_SOURCE_INACTIVE) == SAVED_INTERRUPTED`; `classifyFinalize(ERROR_ENCODING_FAILED) == FAILED`; `classifyFinalize(ERROR_NO_VALID_DATA) == FAILED`; `foregroundServiceTypes(false) == FOREGROUND_SERVICE_TYPE_CAMERA`; `foregroundServiceTypes(true) == CAMERA or MICROPHONE`. Konstanty z `VideoRecordEvent.Finalize` a `ServiceInfo` jsou `static final int` — v JVM testu použij přímo (kompilátor je inlinuje); pokud ne, vytáhni hodnoty do vlastních konstant v `VideoRecorder.kt`.
- [ ] **Step 2: FAIL**, **Step 3: implementuj**, **Step 4: PASS** — `./gradlew :app:testDebugUnitTest --tests '*VideoRecorderRules*'`
- [ ] **Step 5: Emulátor** — (a) natoč 5 s video → `PXL_…mp4` v `content://media/external/video/media`, `ffprobe` délka ≈ 5 s; (b) spusť nahrávání, `adb shell input keyevent KEYCODE_HOME`, počkej 10 s, `adb shell am start` aplikace → UI ukazuje běžící nahrávání ≥ 10 s, stop → video ≥ 10 s; (c) `adb shell pm revoke cz.furmanek.motionphoto android.permission.RECORD_AUDIO`, natoč → video bez audio stopy, bez pádu; (d) během nahrávání `dumpsys activity services cz.furmanek.motionphoto` ukazuje foreground service.
- [ ] **Step 6: Commit** — `feat: video recording that continues in the background`

### Task 7: UI — režimy, rychlá nastavení, objektivy, expozice, mřížka, náhled, nastavení

**Files:**
- Create: `ui/components/*.kt` (viz struktura), `ui/SettingsScreen.kt`
- Modify: `ui/CameraScreen.kt`, `ui/CameraViewModel.kt`, `MainActivity.kt` (volume key: `onKeyDown` pro `KEYCODE_VOLUME_UP/DOWN` → spoušť, když `volumeKeyShutter`; jinak `super`), `res/values/strings.xml`

**Interfaces:**
- Consumes: `CameraSession` (Task 4–6), `SettingsRepository` (Task 1), `zoomPresets` (Task 3)

Rozvržení (tmavé, styl Google Camera): nahoře `QuickSettingsBar` (blesk/svítilna, samospoušť, poměr, živá fotka, ozubené kolo); náhled s `GridOverlay` a fokusem; nad spouští `ZoomChips` (+ přepínač objektivů, když `backLenses` není prázdné); `ModeBar` (Foto · Video, swipe i ťuknutí); dole `LastCaptureThumbnail` · `ShutterButton` (ve Videu červený, během nahrávání stop + pauza) · přepnutí kamery. Po ťuknutí na náhled se na 3 s ukáže svislý `ExposureSlider` (rozsah z capabilities). `RecordingIndicator` s časem `mm:ss`. Volby, které capabilities nemají, se nevykreslí; během nahrávání jsou ModeBar, přepnutí kamery, objektivy a poměr zakázané. Přepínač živé fotky při blesku AUTO/ON ukazuje přeškrtnutou ikonu (efektivně vypnuto). `SettingsScreen` (fullscreen, zpět přes `BackHandler`): mřížka, poloha (při zapnutí žádá `ACCESS_FINE_LOCATION`; při odmítnutí zůstane vypnuto), zvuk spouště (zakázaný přepínač, když `mustPlayShutterSound()`), spoušť hlasitostí, zrcadlení přední kamery, stabilizace (jen když ji capabilities mají), kvalita a 60 fps videa. Náhled posledního snímku: `ContentResolver.loadThumbnail(uri, Size(144, 144))`, ťuknutí → `ACTION_VIEW`.

- [ ] **Step 1: Implementuj komponenty a obrazovky.**
- [ ] **Step 2: Build + lint** — `./gradlew :app:assembleDebug :app:lintDebug` → bez chyb lintu
- [ ] **Step 3: Emulátor (screenshoty)** — zkontroluj: lišta režimů, rychlá nastavení, zoom chipy odpovídají rozsahu zoomu emulátoru, mřížka se zapne v nastavení, náhled posledního snímku po vyfocení, během nahrávání zakázaný ModeBar a přepnutí kamery, stisk `KEYCODE_VOLUME_DOWN` vyfotí.
- [ ] **Step 4: Commit** — `feat: full camera UI with modes, quick settings and settings screen`

### Task 8: Volání z jiných aplikací

**Files:**
- Create: `CaptureIntentActivity.kt`
- Modify: manifest (intent-filtery `android.media.action.IMAGE_CAPTURE` a `android.media.action.VIDEO_CAPTURE`, kategorie DEFAULT)

**Interfaces:**
- Consumes: `CameraSession`, `PhotoCapture.capturePhoto`, `VideoRecorder`
- Produces: zjednodušené UI (jen spoušť, přepnutí kamery, zoom; bez živé fotky, bez náhledu galerie, bez nastavení) a potvrzovací obrazovka (Použít / Znovu).

Chování: s `EXTRA_OUTPUT` zapiš výsledek do té URI (fotku jako JPEG, video jako MP4 přes `FileDescriptorOutputOptions`) a vrať `RESULT_OK`; bez `EXTRA_OUTPUT` u fotky vrať v `data` extra `"data"` zmenšený `Bitmap` (max 512 px), u videa ulož do MediaStore a vrať jeho URI. Zrušení → `RESULT_CANCELED`. Nic se neukládá do galerie, když je dané `EXTRA_OUTPUT`.

- [ ] **Step 1: Implementuj.**
- [ ] **Step 2: Emulátor** — `adb shell am start -W -a android.media.action.IMAGE_CAPTURE -n cz.furmanek.motionphoto/.CaptureIntentActivity` → otevře se zjednodušené UI; vyfocení + Použít ukončí aktivitu bez pádu (`adb logcat -d | grep AndroidRuntime` prázdné) a v galerii nepřibude soubor jen tehdy, když bylo dané `EXTRA_OUTPUT` (bez něj se fotka vrací jako náhled, také bez zápisu do galerie). Totéž pro `android.media.action.VIDEO_CAPTURE`: video se uloží do `content://media/external/video/media`.
- [ ] **Step 3: Commit** — `feat: handle IMAGE_CAPTURE and VIDEO_CAPTURE intents`

### Task 9: Vydání 0.2.0

**Files:**
- Modify: `app/build.gradle.kts` (`versionCode = 2`, `versionName = "0.2.0"`)

- [ ] **Step 1: Kompletní ověření** — `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease` → BUILD SUCCESSFUL; `apksigner verify --print-certs` ukazuje `CN=Radek Furmanek, O=cz.furmanek`.
- [ ] **Step 2: Smoke test release APK na emulátoru** — instalace přes stávající verzi (`adb install -r`, ne uninstall — ověří kompatibilitu podpisu), živá fotka, fotka, 5 s video.
- [ ] **Step 3: Commit + push** — `chore: release 0.2.0`, `git push`
- [ ] **Step 4: GitHub Release** — `gh release create release-0.2.0 MotionPhoto-0.2.0.apk --title "Release build 0.2.0" --prerelease` s českými poznámkami (co je nového).
