# Makro a 0,5× přes skrytý ultraširoký fotoaparát — design

Datum: 2026-10-27
Stav: návrh ke schválení

## Záměr a proč

Uživatel chce v režimu Foto pravé makro a zoom 0,5×. Jeho telefon (Motorola edge 60 fusion) má tři zadní
fotoaparáty, ale aplikacím v seznamu ukazuje jen hlavní (id 0, 5,6 mm, ostří od 10 cm). Ultraširoký fotoaparát
(id 2, 2,1 mm, ostří od 4 cm) v seznamu chybí. Zkouška v aplikaci (0.9.3) ale ukázala, že ho jde otevřít přes
Camera2 a dává obraz. CameraX, na které aplikace stojí, vidí jen fotoaparáty ze seznamu, takže ho otevřít neumí.

Dnešní makro (0.9.0) na tomto telefonu jen přiblíží hlavní fotoaparát 2×.

## Cíle

- **Makro** použije skrytý fotoaparát, když ostří výrazně blíž než hlavní. Na Motorole tedy id 2 od 4 cm.
- **Zoom 0,5×:** čip před „1×“, když telefon má skrytý zadní fotoaparát s kratším ohniskem než hlavní. Popisek
  je poměr zorných polí zaokrouhlený na desetinu, třeba „0,5×“. *(Volba Claude.)*
- Obě funkce dávají **obyčejnou fotku**: JPEG se správným otočením, datem a polohou (když je zapnutá), uloženou
  jako `PXL_….jpg` do Fotek jako dnes. Rozhodnutí uživatele: pro začátek jen fotka.

## Mimo rozsah

- Živá fotka, video, ostatní režimy (portrét, noc, HDR, panorama…) na skrytém fotoaparátu. Živá fotka může
  přijít v dalším kroku.
- Blesk na skrytém fotoaparátu. Ikona blesku bude neaktivní. *(Volba Claude.)*
- Jiný poměr stran než 4:3. Čip poměru bude neaktivní. *(Volba Claude.)*
- Plynulý přechod zoomu mezi fotoaparáty: 0,5× ↔ 1× je přepnutí, ne plynulý zoom.

## Anti-cíle

- **Ztracená fotka nebo zaseknutý fotoaparát:** když skrytý fotoaparát selže (nejde otevřít, odpojí se, chyba při
  focení), aplikace se vrátí k hlavnímu fotoaparátu a ukáže hlášku. Makro pak přejde na dnešní 2× přiblížení.
- **Rozbití dnešního chování:** na telefonech bez skrytých fotoaparátů se nic nemění.
- **Otočená nebo zrcadlená fotka**, nebo náhled, který neodpovídá fotce.

## Rozhodnutí

### Kdy se použije skrytý fotoaparát

- Zjišťování zůstává jako dnes v `readLenses`: skrytý fotoaparát = odpoví na id, ale v seznamu chybí. Jestli
  ho jde opravdu otevřít, se zjistí až při prvním použití. Když to selže, aplikace si to pamatuje do restartu a
  nabídku schová. *(Volba Claude.)*
- `chooseMacro` smí vybrat i skrytý fotoaparát. Makro pak běží přes novou cestu.
- Čip 0,5× se ukáže jen ve Foto se zadním fotoaparátem, stejně jako květina. Při zapnutém makru se schová
  (makro má vlastní fotoaparát).

### Přímá cesta přes Camera2

- Když je aktivní skrytý fotoaparát, aplikace **uvolní CameraX** a otevře ho sama přes Camera2. Při návratu
  (1×, vypnutí makra, jiný režim, odchod z aplikace) ho zavře a CameraX se znovu připojí.
- **Náhled:** vlastní plocha přes celý náhled, se stejným umístěním 4:3 jako dnes, aby se nehýbala mřížka,
  ostřicí kroužek ani ovládání.
- **Funguje:** spoušť, samospoušť, zvuk spouště, klepnutí pro zaostření a expozici, posuvník expozice,
  přiblížení prsty v rozsahu skrytého fotoaparátu, mřížka, uložení polohy, náhled poslední fotky.
- **Neaktivní** (zašedlé): blesk, poměr stran, živá fotka (fotí se obyčejná fotka), HDR.
- **Fotka:** JPEG v plném rozlišení 4:3 skrytého fotoaparátu, otočení podle polohy telefonu (`JPEG_ORIENTATION`),
  uložení přes dnešní `MediaStoreWriter.savePhoto` (EXIF poloha, jméno `PXL_…`).

## Omezení

- minSdk 29, CameraX 1.6.2 zůstává pro všechno ostatní. Žádné nové knihovny.
- Uživatel nemůže připojit telefon přes adb, takže ověřování probíhá přes release buildy a diagnostiku v
  aplikaci. Přímá cesta má logovat klíčové kroky a chyby se musí projevit hláškou na obrazovce.
- Verze **0.10.0**.

## Na builderovi

- Struktura přímé cesty (třída pro Camera2, napojení na `CameraSession` a `CameraScreen`), výběr velikostí
  náhledu a JPEG, AF/AE regiony, zoom (`CONTROL_ZOOM_RATIO` nebo ořez), přepnutí stavu UI.
- Testy: JVM testy čisté logiky (výběr 0,5× a makra, popisek zoomu, otočení JPEG, převod bodu klepnutí na
  oblast senzoru, výběr velikostí); zbytek emulátor a telefon.

## Ověření

- JVM testy výše.
- Emulátor: skrytý fotoaparát nemá, takže se přímá cesta vyzkouší s dočasně „skrytým“ zadním fotoaparátem.
  Náhled, ostření, focení, otočení fotky a návrat na CameraX.
- Release build (R8) na emulátoru.
- Telefon uživatele: 0,5× a makro od 4 cm, otočení fotek na výšku i na šířku, návrat na 1×.
