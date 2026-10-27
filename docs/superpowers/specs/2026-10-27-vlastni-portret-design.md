# Vlastní portrét — design

Datum: 2026-10-27
Stav: návrh ke schválení (po kontrole builderem)

## Záměr a proč

Režim **Portrét** v aplikaci je, ale zobrazí se jen tam, kde výrobce zpřístupní svůj portrétní režim přes
CameraX Extensions (`ExtensionMode.BOKEH`). Uživatel má **Motorolu edge 60 fusion** a ta ho nezpřístupňuje,
takže portrét v liště nevidí. Chce portrét, který funguje na jeho telefonu, a obecně na každém telefonu:
vyfotit hlavní objekt ostře s rozmazaným pozadím.

Fotí **hlavně lidi**, ale hodí se i **zvířata a věci** (pes, hrnek na stole).

Rozmazání chce **vidět už v náhledu před stisknutím**. Souhlasí s tím, že náhled v Portrétu bude o něco
méně plynulý než ve Foto a že ostrá oblast při rychlém pohybu telefonu na zlomek sekundy dobíhá za objektem.

## Cíle

- Režim PORTRÉT v liště režimů je vidět i na telefonech bez portrétu od výrobce.
- Náhled ukazuje živě rozmazané pozadí a ostrý objekt.
- Po stisku spouště se fotka zpracuje znovu v plném rozlišení a uloží jako JPEG do `DCIM/Camera`.
- Výsledek odpovídá tomu, co uživatel viděl v náhledu (stejný objekt ostrý), jen s přesnějšími okraji.

## Mimo rozsah

- Ukládání originálu vedle portrétu (uživatel nechce).
- Nastavitelná síla rozmazání, změna ostrého místa ťuknutím, dodatečná úprava v galerii.
- Hloubková mapa uložená do fotky (Google Photos „změnit rozostření“ apod.).
- Živá fotka v Portrétu: portrét je jen statická fotka (stejně jako dosud s portrétem výrobce).
- Video s rozmazaným pozadím.

## Anti-cíle (selhání, i kdyby to technicky fungovalo)

- **Rozmazaný objekt:** objekt, na který uživatel míří, je ve výsledné fotce rozmazaný, nebo mu chybí kus
  (useknutá hlava, ruka).
- **Ostrý „výstřižek“:** tvrdá hrana mezi ostrým objektem a rozmazaným pozadím, která vypadá jako nalepená.
- **Ztracená fotka:** když rozpoznání selže nebo nic nenajde, uloží se aspoň obyčejná fotka. Uživatel nesmí
  zmáčknout spoušť a odejít s ničím.
- **Zhoršení ostatních režimů:** Foto, živá fotka, Video atd. se nesmí zpomalit ani změnit.

## Rozhodnutí

### Kdy se použije vlastní portrét

- Když telefon umí portrét výrobce (`BOKEH` extension), zůstává **portrét výrobce** jako dosud. Je kvalitnější.
  *(Volba Claude, uživatel to neurčil.)*
- Jinak se v liště zobrazí PORTRÉT s vlastním zpracováním. Podmínka: na zařízení jsou služby Google Play
  (viz níže). Bez nich se režim nezobrazí.
- Zadní i přední kamera (portrét selfie dává smysl). *(Volba Claude.)*

### Rozpoznání objektu

- **Google ML Kit Subject Segmentation** (`com.google.android.gms:play-services-mlkit-subject-segmentation`).
  Pozná lidi, zvířata i věci, běží v telefonu, fotky nikam neposílá. Rozhodnutí uživatele: lidi + zvířata + věci.
- Model se stahuje přes služby Google Play. **První použití potřebuje internet.** Dokud model není stažený,
  náhled zůstane ostrý a po stisku se uloží obyčejná fotka s hláškou, že se portrét připravuje (stažení se
  spustí při přepnutí do Portrétu, ne až po stisku). *(Volba Claude.)*
- ML Kit výsledek je maska popředí (pravděpodobnost na pixel). Ostrá oblast = maska popředí (všechny nalezené
  objekty dohromady).

### Náhled

- V Portrétu se místo `PreviewView` kreslí náhled z vlastních snímků: `ImageAnalysis` (RGBA,
  `KEEP_ONLY_LATEST`, rozlišení kolem 720p) → zmenšené pozadí → rozmazané → složené s ostrým snímkem přes masku.
- Cíl plynulosti: **15–20 snímků/s** náhledu. Maska se přepočítává pomaleji (**3–5×/s**) a mezi přepočty se
  použije poslední maska. Proto při rychlém pohybu ostrá oblast dobíhá (uživatel s tím souhlasí).
- Když maska nic nenajde (nebo ještě není), náhled je celý ostrý — uživatel tak hned vidí, že portrét nezabere.
- Rozmazání musí fungovat na **minSdk 29** (bez `RenderEffect`, ten je až od API 31). Přesná technika
  rozmazání (zmenšení + zvětšení, box blur, …) je na builderovi.
- Náhled musí odpovídat ukládanému záběru (stejně jako `PreviewView` ve `FIT_CENTER` v ostatních režimech).

### Výsledná fotka

- Po stisku `ImageCapture` v plném rozlišení (jako Foto bez živé fotky), pak segmentace celé fotky, rozmazání
  pozadí a složení s **měkkým přechodem** na okraji masky (feather), aby nevznikl tvrdý výstřižek.
- Zpracování smí trvat **řádově 1–3 s** ve standardním rozlišení, v plném rozlišení déle (viz níže). Během něj se na spoušti točí bílý oblouk (už existuje: `processing`
  v `ShutterButton`, 0.5.1). Spoušť je mezitím zakázaná.
- Výsledek: JPEG v `DCIM/Camera`, jméno jako běžná fotka (`PXL_yyyyMMdd_HHmmss.jpg`, UTC), EXIF orientace
  a poloha jako u Foto. *(Volba Claude: bez zvláštní přípony.)*
- **Ukládá se jen portrét**, originál bez rozmazání se neukládá (rozhodnutí uživatele). Výjimka: když
  zpracování selže, uloží se nezpracovaná fotka (viz níže), aby se snímek neztratil.
- **Rozlišení portrétu je nastavitelné** v obecném nastavení aplikace (rozhodnutí uživatele: „portrét musí být
  kvalitní … dej tam možnost pro přepnutí“):
  - **Plné rozlišení** (co dá `ImageCapture` jako ve Foto, u Motoroly až 50 Mpx) — **výchozí** *(volba Claude
    podle důrazu uživatele na kvalitu)*. Zpracování smí trvat déle (řádově do ~5 s) a musí počítat s pamětí:
    skládání po pruzích / na místě v dekódované bitmapě, maska spočítaná ve zmenšeném rozlišení a zvětšená.
    Při nedostatku paměti se uloží nezpracovaná fotka s hláškou (anti-cíl „ztracená fotka“).
  - **Standardní (~12 Mpx)** — rychlejší a spolehlivější; fotka se před zpracováním zmenší.
- **Nic nenalezeno nebo chyba segmentace:** uloží se nezpracovaná fotka a snackbar řekne, že portrét se nepovedl
  a uložila se běžná fotka. Při nedostatku paměti stejně.

### Ovládání v Portrétu

- Zaostřování jako ve Foto (automatické + ťuknutí); maska ostření neřídí. *(Volba Claude.)*
- Jako u dnešního Portrétu výrobce: bez živé fotky, bez HDR. Zoom, přepnutí kamery, samospoušť, blesk a poloměr
  stran zůstávají jako ve Foto, pokud to builder neodůvodní jinak. Poměr stran je dnes pro Portrét pevně 4:3
  (`Labels.kt`, `CameraSession.kt`), to platí dál.

## Omezení

- minSdk 29, targetSdk 37, Kotlin + Compose + CameraX 1.6.2 (viz hlavní spec
  `2026-10-26-full-camera-design.md`).
- Nová závislost: jen ML Kit Subject Segmentation (služby Google Play). Žádné nativní knihovny navíc.
- Paměť: plné rozlišení (např. 50 Mpx u Motoroly) se nesmí zpracovávat celé v jednom ARGB bufferu, pokud to
  hrozí OOM — builder zvolí zpracování ve zmenšené masce + zvětšení masky na plné rozlišení.
- Distribuce dál přes GitHub Releases, další verze **0.6.0**.

## Na builderovi

- Struktura kódu (nový balíček např. `portrait/`), napojení do `CameraSession` / `BindingPlan` / `Modes`.
- Technika rozmazání a feathering, rozlišení analýzy, přesná frekvence masky.
- Jak zjistit dostupnost služeb Google Play a stav stažení modelu.
- Testy: čistá logika (skládání přes masku, feather, zmenšení/zvětšení masky) JVM testy; zbytek na emulátoru.

## Ověření

- JVM testy pro skládání a masku.
- Emulátor API 36: režim PORTRÉT se zobrazí (emulátor nemá BOKEH extension), náhled ukazuje rozmazané pozadí
  u virtuální scény, pokud ML Kit v emulátoru něco najde; fotka se uloží. Pokud segmentace na emulátoru
  nefunguje, zapsat to a ověřit na telefonu uživatele.
- Telefon uživatele (Motorola edge 60 fusion): člověk, pes/zvíře, věc na stole.
