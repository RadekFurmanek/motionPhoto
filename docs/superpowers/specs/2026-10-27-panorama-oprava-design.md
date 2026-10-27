# Oprava panoramy — design

Datum: 2026-10-27
Stav: návrh ke schválení

## Záměr a proč

Panorama (0.5.0) uloží spojenou fotku, ale uživatel hlásí tři problémy:

1. **Google Fotky ji neberou jako panoramu.** Nejde ji prohlížet pohybem telefonu.
2. **Duchové a rozmazání.** Snímky jsou přes sebe a obraz je dvojitý a měkký.
3. **Chybí vodováha.** Vodorovná čára ukazuje jen sklon nahoru/dolů. Uživatel nevidí, jestli telefon drží
   nakloněný do strany, takže neví, jak ho správně držet.

Rozbor dnešního kódu (`panorama/`, `CameraSession.startPanorama`):

- Ukládá se obyčejný JPEG bez metadat GPano, takže ho Google Fotky nemají jak poznat.
- `PANORAMA_STEPS_PER_FOV = 3`: sousední snímky se překrývají ze 2/3 a `stitch()` prolíná celý překryv
  lineárně. Jakákoli nepřesnost, pohyb nebo paralaxa se tak rozmaže přes široký pás.
- Zarovnání je jen posun (dx, dy) válcové projekce. Náklon do strany (roll) ani změna sklonu (pitch) se
  nekompenzují, proto snímky nesedí.
- Analýza běží v 1280×960 a snímky se drží nejvýš 960 px vysoké, takže výsledek je měkký.

## Cíle

- Uložená panorama se v **Google Fotkách otevře jako panorama**, kterou lze prohlížet pohybem telefonu
  (ikona panoramy).
- Spojení bez viditelných duchů ve statické scéně. Pohybující se předměty (lidé, auta) se nesmějí
  zdvojit přes široký pás.
- Ostřejší výsledek než dnes, tedy vyšší rozlišení snímků.
- **Vodováha** na obrazovce: čára, která se naklání podle náklonu telefonu do strany a zbarví se, když je
  telefon rovně. Plus ukazatel sklonu nahoru/dolů.

## Mimo rozsah

- Svislá panorama, sférická fotka 360°×180° (Photo Sphere) a víc řad snímků.
- Korekce expozice mezi snímky nad rámec prolnutí ve švu. *(Volba Claude, lze přidat později.)*
- Úpravy panoramy po pořízení.

## Anti-cíle

- **Ztracená panorama:** když spojení selže nebo dojde paměť, uživatel dostane hlášku a aplikace nespadne
  (jako dnes).
- **Rozbitá v Google Fotkách:** panorama, kterou Fotky zobrazí deformovanou (špatné úhly, posunutý
  horizont), je horší než obyčejná fotka.
- **Pomalé zpracování:** spojování smí trvat déle než dnes, ale ne řádově (cíl do ~10 s na telefonu).
- **Zhoršení ostatních režimů.**

## Rozhodnutí

### Projekce a metadata (bod 1)

- Výsledek bude ve **sférické projekci (equirectangular)**: vodorovně úhel otočení, svisle úhel nad/pod
  horizontem, oba lineárně. To je formát, který Google Fotky pro panoramy čtou.
- Do JPEG se zapíše XMP **GPano**:
  - `ProjectionType=equirectangular`, `UsePanoramaViewer=True`;
  - `CroppedAreaImageWidthPixels/HeightPixels` = rozměr fotky;
  - `FullPanoWidthPixels` = 360° v pixelech, `FullPanoHeightPixels` = polovina;
  - `CroppedAreaLeftPixels` a `CroppedAreaTopPixels` podle toho, kde výřez leží. Horizont je ve středu plné výšky.
    Bez kompasu (game rotation vector) není známý sever, takže výřez se vodorovně vycentruje.
  *(Volba Claude.)*
- Horizont se určí ze senzoru orientace (game rotation vector), tedy ze skutečného sklonu, ne ze středu
  prvního snímku.

### Spojování (bod 2)

- Každý snímek se promítne podle **plné orientace telefonu** (otočení, sklon i náklon) ze senzoru v okamžiku
  snímku. Pak se obrazovým porovnáním doladí malý zbytkový posun (jako dnes `estimateShift`).
- Sousední snímky se spojí **úzkým švem** uprostřed překryvu s plynulým přechodem několika desítek pixelů,
  ne prolnutím celého překryvu. Šev smí vést tam, kde se snímky nejméně liší. Přesnou metodu volí builder.
- Překryv sousedů se sníží zhruba na 1/3 snímku, krok bude ~2/3 zorného pole. *(Volba Claude.)* Méně
  snímků znamená méně švů a méně paměti.
- Vyšší rozlišení snímků, cíl kolem **1600 px** na kratší straně. Paměť musí vyjít i při 360°. Builder
  rozhodne, jak snímky držet (např. komprimované nebo ve zmenšeném formátu) a jaký strop šířky výsledku
  použít (dnes 16 000 px).

### Vodováha a sklon (bod 3)

- Uprostřed náhledu **čára horizontu, která se naklání** podle náklonu telefonu do strany. Když je náklon do
  ±2°, zbarví se žlutě („rovně“). *(Volba Claude: žlutá jako cílová tečka.)*
- **Sklon nahoru/dolů** ukáže cílová tečka svou výškou: posune se nahoru nebo dolů o tolik, o kolik se
  telefon odklonil od sklonu na začátku. Kroužek uprostřed se na ni míří ve vodorovném i svislém směru.
  Dnešní červená hláška „drž telefon rovně“ zůstává pro velký sklon.
- Vodováha se ukazuje už **před startem** panoramy, aby šlo telefon srovnat předem.

## Omezení

- minSdk 29, CameraX 1.6.2, žádné nativní knihovny (OpenCV apod. ne). *(Volba Claude, drží velikost APK.)*
- Zachovat dnešní tok: start a stop spouští, tečka a kroužek, automatické ukončení po ~360°, spojování na
  pozadí s průběhem, uložení jako `PXL_…PANO.jpg` *(dnešní jméno beze změny)*.
- Verze **0.8.0**.

## Na builderovi

- Matematika projekce z rotační matice, interpolace a jak doladit orientaci obrazem.
- Hledání švu a šířka přechodu.
- Paměťová strategie a strop rozlišení.
- Testy: JVM testy projekce (známý úhel se trefí do známého pixelu), švu (statická scéna bez zdvojení na
  syntetických datech), XMP GPano (hodnoty a parsovatelnost) a výpočtu náklonu/sklonu pro UI.

## Ověření

- JVM testy výše.
- Emulátor: panorama se uloží a XMP obsahuje GPano s konzistentními čísly. Emulátor má virtuální scénu a
  senzory, takže otočení lze simulovat.
- Release build (R8) na emulátoru.
- Telefon uživatele: Google Fotky ukáží panoramu s ovládáním pohybem; žádní duchové ve statické scéně;
  vodováha reaguje na náklon.
