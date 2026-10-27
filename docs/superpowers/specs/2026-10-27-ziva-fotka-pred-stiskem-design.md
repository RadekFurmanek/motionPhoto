# Živá fotka i před stiskem — design

Datum: 2026-10-27
Stav: návrh ke schválení

## Záměr a proč

Živá fotka dnes začne natáčet klip až po stisku spouště. Klip má 2 s a fotka se pořídí 1 s po začátku
(`PhotoCapture.recordClipWithStill`, `CLIP_DURATION_MS = 2000`, `STILL_OFFSET_MS = 1000`). Uživatel chce zachytit
**okamžik, kdy stiskne spoušť**. Slovy uživatele: „po 1 sekundě je už třeba pozdě“. Chce to **jako na iPhonu**:
klip obsahuje i chvíli **před** stiskem.

Živé fotky jsou hlavní funkce aplikace (viz `2026-10-26-full-camera-design.md`). Nic nesmí zhoršit jejich
kompatibilitu s Google Photos.

## Cíle

- Klip živé fotky má **1,5 s před stiskem a 1,5 s po stisku**, celkem kolem 3 s. Rozhodnutí uživatele, stejně
  jako iPhone.
- Fotka (JPEG) je **z okamžiku stisku**, ne o sekundu později. Má co nejmenší zpoždění, jaké telefon dovolí.
- Značka fotky v klipu (Motion Photo presentation timestamp) ukazuje na okamžik fotky, takže Google Photos
  ukáže fotku uprostřed klipu.
- Výsledný soubor zůstává stejný formát jako dnes: `PXL_….MP.jpg`, XMP + JPEG + MP4, `MotionPhotoMuxer`.

## Mimo rozsah

- Změna délky klipu v nastavení.
- Pre-roll pro Video, Portrét, noční režim, HDR a další režimy. Týká se jen **Foto se zapnutou živou fotkou**.
- Stabilizace klipu, „Klíčová fotka“ (výběr jiného snímku jako fotky) nebo úpravy klipu po pořízení.

## Anti-cíle (selhání, i kdyby to technicky fungovalo)

- **Ztracená fotka:** když klip selže (encoder, paměť), uloží se aspoň obyčejná fotka. Dnes to tak funguje a
  musí to tak zůstat.
- **Zpožděná fotka:** fotka z doby zjevně po stisku, kdy okamžik už utekl.
- **Rozbité živé fotky v Google Photos:** klip se nepřehraje, chybí, nebo je značka fotky mimo klip.
- **Zhoršení ostatních režimů:** když je živá fotka vypnutá, nebo v jiném režimu, nesmí nic běžet navíc
  (baterie, teplo, plynulost náhledu).
- **Spoušť, která nereaguje:** po stisku musí být jasné, že se fotí. Opakovaný stisk během dotáčení nesmí
  rozbít předchozí snímek.

## Rozhodnutí

### Průběžné natáčení

- Když je ve Foto zapnutá živá fotka a náhled běží, kamera **průběžně kóduje video do krátké paměti**
  (kruhový buffer) v RAM. Drží se jen posledních několik sekund a nic se nezapisuje na disk, dokud uživatel
  nestiskne spoušť. *(Volba Claude.)*
- Po stisku se vezme úsek z paměti od ~1,5 s před stiskem, dotočí se 1,5 s po stisku, a vše se složí do MP4.
  Klip musí začínat klíčovým snímkem. Interval klíčových snímků proto má být krátký (např. 0,5 s), aby začátek
  vyšel co nejblíž 1,5 s před stiskem. Přesnou hodnotu volí builder. Tolerance začátku je ±0,5 s.
- **Zvuk** jako dnes: nahrává se, když je udělené oprávnění k mikrofonu, a to i v pre-rollu. *(Volba Claude.)*
- Průběžné natáčení **běží jen** ve Foto se zapnutou živou fotkou a s viditelnou aplikací. Při přepnutí režimu,
  vypnutí živé fotky, odchodu z aplikace nebo zhasnutí obrazovky se zastaví a paměť se uvolní.
- **Krátce po spuštění** (paměť ještě nemá 1,5 s): klip bude mít před stiskem, co je k dispozici. Nečeká se.
- Kvalita klipu zůstává jako dnes, tedy rozlišení a datový tok, které dnes používá `VideoCapture` pro živou
  fotku.

### Fotka

- Fotka se pořizuje hned po stisku. Builder zkusí **zero-shutter-lag** (`ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG`),
  pokud ho telefon podporuje spolu s průběžným videem. Jinak `MINIMIZE_LATENCY` jako dnes. Rozhoduje to, co na
  Motorole skutečně funguje, a builder volbu zapíše.
- Značka fotky v klipu = čas pořízení fotky − čas prvního snímku klipu, na stejných hodinách. Dnes
  `motionPhotoPresentationTimestampUs` takto počítá z konce klipu, nová cesta bude počítat z časů snímků v
  bufferu.

### Spoušť

- Po stisku spoušť krátce „cvakne“ jako dnes. Červený kruh se oběhne za **1,5 s** (dotáčení po stisku), pak se
  točí bílý oblouk, dokud se neuloží. *(Volba Claude: kruh zobrazuje jen zbývající natáčení.)*
- Během dotáčení a ukládání je spoušť zakázaná, jako dnes.

### Selhání

- Když průběžné kódování nelze spustit (telefon nepodporuje kombinaci, encoder selže), živá fotka funguje
  **jako dnes**: natáčí až po stisku. Kombinaci Preview + ImageCapture + video teď obstarává `BindingPlan`, s
  pádem na Preview + ImageCapture. Uživatel tak nikdy nepřijde o živou fotku úplně. *(Volba Claude.)*
- Když selže klip po stisku, uloží se obyčejná fotka (beze změny oproti dnešku).

## Omezení

- minSdk 29, targetSdk 37, Kotlin, Compose, CameraX 1.6.2. Žádné nativní knihovny navíc.
- CameraX `Recorder` neumí kruhový buffer. Builder musí kódovat sám (`MediaCodec` + `MediaMuxer`), např. přes
  vlastní `VideoOutput`, `Preview`/`SurfaceProcessor` nebo jinou cestu, kterou CameraX 1.6.2 veřejně dovolí.
  Volbu i důvod builder zapíše. Na emulátoru API 36 i na Motorole edge 60 fusion.
- Paměť: buffer kódovaného videa (~3 s) je řádově jednotky MB. Nesmí držet nekódované snímky.
- Distribuce přes GitHub Releases, další verze **0.7.0**.

### Proveditelnost (zjištěno při kontrole návrhu)

- CameraX 1.6.2 nemá veřejnou cestu k vlastnímu encoderu. `VideoOutput` má veřejné jen `onSurfaceRequested`.
  Jeho výchozí `getMediaSpec()` vrací `null` a `getMediaCapabilities()` vrací `VideoCapabilities.EMPTY`, takže
  není jisté, že s ním `VideoCapture` vybere rozlišení. Pro buffer jsou dvě kandidátní cesty:
  1. vlastní `VideoOutput` přes `VideoCapture.withOutput`: Surface rovnou do `MediaCodec`, bez kopírování;
  2. `ImageAnalysis` (YUV) → `MediaCodec` přes vstupní buffery: jen veřejné API, ale kopie snímku na CPU.
- **Prvním krokem plánu je spike na emulátoru**, který rozhodne mezi nimi. Výsledek se zapíše do plánu.
  *(Volba Claude.)*
- Rozlišení klipu: **nejvýš 1080p**, podle toho, co zvolená cesta zvládne plynule při ~30 snímcích/s. Dnešní
  klip je ze `VideoCapture` s výchozí kvalitou. *(Volba Claude.)*
- Přední kamera se zrcadlením: klip musí být zrcadlený stejně jako dnes.

## Na builderovi

- Architektura průběžného kódování a kruhového bufferu (video + zvuk), synchronizace hodin a ořez na klíčový
  snímek.
- Zda zůstane `VideoCapture<Recorder>` pro fallback, nebo se nahradí.
- Testy: čistá logika (výběr úseku z bufferu, ořez na klíčový snímek, výpočet značky fotky, zahazování starých
  dat) JVM testy; zbytek emulátor + telefon.

## Ověření

- JVM testy výběru úseku, značky a zahazování.
- Emulátor API 36: živá fotka se uloží, klip ~3 s, v Google Photos (nebo `MotionPhotoMuxer` čtení) je značka
  uprostřed. Klip obsahuje dění **před** stiskem (např. pohyb kamery emulátoru).
- Release build (R8) na emulátoru před předáním.
- Telefon uživatele: zachycený okamžik stisku, přehrání v Google Photos, teplota a baterie při delším držení
  aplikace ve Foto.
