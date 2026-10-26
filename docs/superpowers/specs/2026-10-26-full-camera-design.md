# Plnohodnotný fotoaparát — design

Datum: 2026-10-26
Stav: návrh ke schválení (po kontrole revieweru)

## Záměr a proč

Aplikace vznikla kvůli **živým fotkám** (Motion Photo kompatibilní s Google Photos). To už funguje a uživatel
ověřil, že Google Photos fotky rozpozná. Teď z ní má být **plnohodnotný fotoaparát**, který jde používat místo
vestavěného: kromě živých fotek i video, zpomalené video, panorama, časosběr, režimy výrobce (noc, portrét, HDR)
a obvyklá nastavení.

Aplikace je **obecná**, není pro jeden konkrétní telefon. Při spuštění (a při každé změně kamery) zjistí, co daný
hardware umí, a nabídne jen to. Nepodporovaná funkce se v UI nezobrazí, místo aby skončila chybou.

Živé fotky zůstávají hlavní funkcí. Nic z nového nesmí zhoršit jejich kompatibilitu s Google Photos.

## Výchozí stav

Repo `RadekFurmanek/motionPhoto`, verze 0.1.0, distribuce přes GitHub Releases (podepsané APK; klíč
`~/keys/motionphoto-release.jks` přes `keystore.properties`, mimo git).

- Kotlin, Jetpack Compose, CameraX 1.6.2 (core, camera2, lifecycle, video, view), Adobe XMPCore 6.1.11.
- AGP 9.4.1, Kotlin 2.4.20, compileSdk/targetSdk 37, **minSdk 29**.
- `MotionPhotoMuxer` (XMP + JPEG + MP4, unit testy), `MotionPhotoStore` (MediaStore, `DCIM/Camera`, jména
  `PXL_yyyyMMdd_HHmmss.MP.jpg` v UTC), `CameraViewModel` (jeden režim: živá fotka), `CameraScreen`,
  `CameraPermissionGate`. Activity je zamčená na výšku; orientace snímku se bere z `OrientationEventListener`.
- Náhled je `PreviewView` ve `FIT_CENTER` (WYSIWYG: ukazuje celý ukládaný záběr).

## Cíle

### Režimy

Přepínají se swipem nebo ťuknutím na lištu režimů nad spouští (jako u Google Camera / Samsungu). Lišta ukazuje jen
režimy, které aktuální kamera umí. Pořadí: Časosběr · Zpomalené · Video · **Foto** (výchozí) · Portrét · Noc ·
Panorama.

1. **Foto**: výchozí režim.
   - Přepínač **živá fotka** (zap/vyp, výchozí zapnuto). Zapnutá živá fotka funguje jako dnes; vypnutá pořizuje
     obyčejné JPEG (`IMG_`/`PXL_` jméno bez `.MP`) a neváže VideoCapture (rychlejší spoušť, vyšší rozlišení).
   - Pokud kamera podporuje HDR přes Extensions (`HDR`), nabídne se přepínač HDR. Živá fotka a HDR
     z Extensions se vylučují (Extensions nepodporují VideoCapture); UI to dá najevo, jedno vypne druhé.
2. **Video**: nahrávání s pauzou/pokračováním a ukazatelem délky.
   - Rozlišení z `Recorder.getVideoCapabilities(cameraInfo)` (např. 720p / 1080p / 4K) a 30/60 fps, pokud kamera
     60 fps v daném rozlišení podporuje.
   - Stabilizace, pokud ji kamera podporuje.
   - Ukládá do `DCIM/Camera` jako `PXL_yyyyMMdd_HHmmss.mp4` (`video/mp4`).
3. **Zpomalené video**: `HighSpeedVideoSessionConfig` se `setSlowMotionEnabled(true)`, 120 nebo 240 fps podle
   `Recorder.getHighSpeedVideoCapabilities`. Režim se zobrazí jen tam, kde schopnosti existují.
4. **Portrét** a **Noc**: CameraX Extensions (`BOKEH`, `NIGHT`) přes `ExtensionsManager`; jen pokud je výrobce
   pro danou kameru dodává. Pouze fotky, bez živé fotky.
5. **Panorama**: vodorovné panorama otáčením telefonu.
   - Snímání řídí gyroskop (senzor rotation vector). UI ukazuje cílový bod a vodítko držení v rovině; snímek se
     pořídí automaticky po každém otočení o pevný úhel (přibližně 1/3 zorného úhlu, tj. s ~⅔ překryvem).
     Uživatel ukončí ťuknutím, nebo se snímání ukončí samo při ~360°.
   - Skládání je vlastní, bez OpenCV (verze pro Android nemá Javovské rozhraní ke `Stitcher` a přidala by desítky MB):
     cylindrická projekce podle ohniskové vzdálenosti z `CameraCharacteristics`, doladění posunu mezi sousedními
     snímky hledáním nejlepší shody v překryvu na zmenšených obrázcích, prolnutí přechodů. Běží na pozadí
     s průběhem v UI.
   - Ukládá JPEG `PXL_yyyyMMdd_HHmmss.PANO.jpg`.
6. **Časosběr**: snímek každých N sekund (volby např. 0,5 / 1 / 2 / 5 / 10 s), složené do MP4 s 30 fps.
   - CameraX `Recorder` neumí snímat pomaleji než přehrávat, takže časosběr má vlastní kodér (MediaCodec + MediaMuxer)
     krmený snímky z kamery.
   - Ukazuje uplynulý čas a výslednou délku videa.

### Nastavení

**Rychlá nastavení** (horní lišta náhledu, mění se podle režimu):
- blesk: vyp / auto / zap; ve videu svítilna zap/vyp; skryté, když kamera blesk nemá
- samospoušť: vyp / 3 s / 10 s (odpočet na displeji)
- poměr stran fotky: 4:3 / 16:9 / 1:1 (1:1 jako ořez přes `ViewPort`)
- živá fotka zap/vyp (ve Foto)
- rozlišení a fps (ve Videu), 120/240 (ve Zpomaleném), interval (v Časosběru)

**Na náhledu:**
- ťuknutím ostření (už je), pinch zoom (už je)
- posuvník **korekce expozice** po ťuknutí (rozsah z `ExposureState`)
- přepínání předních/zadních kamer (už je)

**Obrazovka nastavení** (ozubené kolo):
- mřížka (vyp / 3×3)
- ukládat polohu do fotek a videí (vyžádá oprávnění k poloze až při zapnutí)
- zvuk spouště (kde zákon zvuk vyžaduje, `MediaActionSound.mustPlayShutterSound()`, nejde vypnout)
- spoušť tlačítkem hlasitosti (zap/vyp)
- zrcadlit fotky z přední kamery
- stabilizace videa (zap/vyp, jen kde je podporovaná)

Nastavení se pamatují mezi spuštěními (DataStore Preferences).

### Natáčení na pozadí (rozhodnutí uživatele)

Video, zpomalené video a časosběr **pokračují i se zhasnutou obrazovkou a po přepnutí do jiné aplikace**.

- Při startu nahrávání se spustí foreground service (typy `camera` a `microphone`; pro časosběr stačí `camera`)
  s trvalou notifikací: co se natáčí, jak dlouho, tlačítko Zastavit. Nahrávání se pak ovládá ze service, ne z Activity.
- Kamera musí zůstat otevřená, i když Activity zanikne. Vazba use-casů na lifecycle se tedy během nahrávání přesouvá
  z Activity na service (nebo je session celá ve service a Activity jen připojuje náhled). Kamera se otevírá
  vždy, když je aplikace na popředí. Android nedovolí otevřít kameru z pozadí, jen ji tam nechat běžet.
- Po návratu do aplikace se UI připojí k běžícímu nahrávání: náhled, čas, tlačítko stop.
- Když systém nahrávání přeruší (jiná aplikace převezme kameru, dojde místo, service je ukončena), uloží se,
  co se stihlo natočit, a uživatel to uvidí v notifikaci.
- Délka je omezená jen volným místem. Při nedostatku místa se nahrávání ukončí a uloží.
- Fotky, živé fotky a panorama běží jen na popředí. Panorama se při odchodu z aplikace zruší.

### Ostatní

- **Náhled posledního snímku** v rohu u spouště. Ťuknutí otevře snímek v systémové galerii (`ACTION_VIEW`,
  typicky Google Photos). Vlastní galerie se nedělá.
- Přední kamera funguje ve všech režimech, které podporuje (typicky Foto, Video, Portrét).
- **Objektivy:** nad spouští jsou volby zoomu (např. 0.5× / 1× / 2× / 5×) odvozené z rozsahu zoomu kamery.
  Ultraširokoúhlý a teleobjektiv dosáhnou přes zoom, pokud je telefon zpřístupní jako jednu logickou kameru.
  Když jsou to samostatné zadní kamery, objeví se přepínač objektivů. Schopnosti se zjišťují pro každou kameru zvlášť.
- **Volání z jiných aplikací:** podpora `ACTION_IMAGE_CAPTURE` a `ACTION_VIDEO_CAPTURE` (zjednodušené UI: jedna fotka
  nebo video, potvrzení, zápis do `EXTRA_OUTPUT` nebo vrácení výsledku, bez živé fotky a bez náhledu galerie).

## Mimo rozsah (non-goals)

Rozhodnuto implementátorem, uživatel může změnit:
- vlastní galerie a úpravy fotek
- manuální „Pro“ režim (ISO, čas závěrky, ruční ostření), RAW/DNG
- vlastní noční nebo HDR algoritmy (jen to, co dodá výrobce přes Extensions)
- bufferování snímků **před** stiskem spouště u živé fotky (Google Camera to dělá; my natáčíme od stisku)
- svislé panorama, 360° fotosféra, panorama z přední kamery
- jiné jazyky UI než čeština

## Čemu se vyhnout (anti-goals)

- Funkce, která se nabídne a pak na daném telefonu selže. Co hardware neumí, se nezobrazí.
- Zhoršení živých fotek: formát souboru, jeho rozpoznání v Google Photos a spolehlivost pořízení musí zůstat
  jako ve verzi 0.1.0.
- Ztracený snímek: pokud ukládání selže, uživatel to musí vidět. Žádné tiché selhání.

## Omezení

- Zůstává stávající stack (Kotlin, Compose, CameraX 1.6.2, minSdk 29). Nové knihovny jen tam, kde nejsou
  zbytné: `camera-extensions`, DataStore. OpenCV ani jiné nativní knihovny ne.
- Distribuce dál přes GitHub Releases (po etapách, viz níže), podepsané stávajícím klíčem (jinak by nešla aktualizace přes nainstalovanou
  verzi). Každé vydání zvýší `versionCode`.
- Na emulátoru nejde ověřit zpomalené video ani Extensions (emulátor je nemá). Tam se ověří jen to, že se tyto
  režimy skryjí. Skutečnou funkčnost ověří uživatel na telefonu.

## Rozhodnuto (implementátorem, jako výchozí volba)

- Panorama bez OpenCV, vlastní cylindrické skládání (viz výše).
- Časosběr vlastním kodérem (MediaCodec + MediaMuxer).
- Živá fotka a ostatní nastavení Foto:
  - **Blesk** (auto/zap) živou fotku pro daný snímek vypne. Předblesk by posunul fotku v klipu a záblesk by byl vidět
    ve videu. UI to ukáže na přepínači živé fotky.
  - **Zrcadlení přední kamery** se použije na fotku i na video stejně, aby si odpovídaly.
  - **Poměr stran** (4:3 / 16:9 / 1:1) se použije na fotku i na video přes společný `ViewPort`. Kompatibilitu
    nečtvercových poměrů ověří uživatel v Google Photos.
- Zamykací obrazovka (spuštění fotoaparátu nad zámkem, `STILL_IMAGE_CAMERA_SECURE`) je mimo rozsah.
- **Vydávání po etapách.** Každá etapa je stabilní aplikace a vyjde jako samostatný GitHub Release:
  1. jádro (session vrstva, nastavení, Foto vč. živé fotky, Video vč. natáčení na pozadí, náhled posledního snímku,
     objektivy, volání z jiných aplikací)
  2. Zpomalené video a Extensions (Portrét, Noc, HDR)
  3. Časosběr
  4. Panorama

  Nedokončený režim se do vydání nedostane, ani skrytý.
- Jména souborů dál ve stylu Pixel (`PXL_…`, UTC), přípony `.MP.jpg` (živá fotka), `.jpg`, `.PANO.jpg`, `.mp4`.
- Nastavení v DataStore Preferences.

## Necháno na implementátorovi

- Rozdělení kódu (např. jedna session vrstva nad CameraX + controller pro každý režim) a navigace mezi
  obrazovkou fotoaparátu a nastavením.
- Přesný vizuál lišty režimů, ikon, odpočtu a vodítka panoramatu (styl blízký Google Camera, tmavý).
- Algoritmus doladění posunu u panoramatu a prolnutí, pokud výsledek nemá viditelné švy u statické scény.
- Výběr bitrate a kodeku pro časosběr (H.264, 30 fps).
- Testy: unit testy pro čistou logiku (geometrie panoramatu, časování časosběru, jména souborů, muxer), ruční
  ověření režimů na emulátoru.
