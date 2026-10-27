# Vlastní noční režim (jako Pixel) — design

Datum: 2026-10-27
Stav: návrh ke schválení

## Záměr a proč

Režim NOC dnes jen předává fotku nočnímu zpracování Motoroly (CameraX Extensions, `ExtensionMode.NIGHT`).
Aplikace ho nijak neovládá. Uživatel hlásí, že noční fotky z aplikace „nejsou tak dobře vidět jako v nativní
apce“. Chce delší expozici. Původně zmínil i žlutooranžový filtr, ale řekl, že když noc bude fungovat jako na
Pixelu, filtr není potřeba.

Řešení je vlastní noční režim po vzoru Pixel Night Sight: série kratších snímků, které se zarovnají a sečtou,
místo jednoho dlouhého snímku, který by se z ruky rozmazal.

## Cíle

- Noční fotka z ruky je **světlejší a čitelnější** než z dnešního režimu NOC, stíny jsou vidět a světla (lampy,
  okna) nejsou přepálená.
- **Celé focení trvá nejvýš 2 s** od stisku spouště do konce posledního snímku (rozhodnutí uživatele). Zpracování
  potom může běžet déle, s ukazatelem „Zpracovávám…“ jako u portrétu.
- Původní noční režim Motoroly zůstane **jako přepínatelná záloha** (rozhodnutí uživatele): Nastavení → „Noční
  režim: Vlastní / Motorola“. Výchozí je Vlastní. Volba se ukáže jen na telefonu, který noční rozšíření má.
- Výsledek je obyčejná fotka: JPEG se správným otočením, datem a polohou (když je zapnutá), uložená jako
  `PXL_….jpg` do Fotek jako dnes.

## Mimo rozsah

- Žlutooranžový ani jiný filtr (rozhodnutí uživatele).
- Expozice delší než 2 s, stativ, astrofotografie, RAW/DNG.
- Živá fotka, blesk a HDR v nočním režimu (dnes je NOC také nemá).
- Skrytý ultraširoký fotoaparát v nočním režimu. Noc běží na hlavním fotoaparátu, případně na předním.
  *(Volba Claude.)*
- Zesvětlený náhled jako na Pixelu. Náhled zůstane, jak ho dá automatická expozice. *(Volba Claude, může přijít
  později.)*

## Čemu se vyhnout

- Rozmazaná fotka nebo „duchové“ (zdvojené pohybující se postavy, auta) horší než od Motoroly.
- Pád aplikace pro nedostatek paměti při práci s plnými snímky.
- Držení telefonu déle než 2 s.

## Jak to funguje

### Fotoaparát

Noc otevře fotoaparát přímo přes Camera2, stejně jako dnes skrytý ultraširoký: CameraX fotoaparát pustí,
aplikace počká, až se zavře, a otevře ho sama (stávající přímá cesta `DirectCamera`, nový druh `DirectKind.NIGHT`).
Důvod: jen přes Camera2 jde spolehlivě nastavit ruční expozici a citlivost a vyfotit sérii snímků těsně za sebou.
*(Volba Claude.)* Náhled běží přes TextureView jako u skrytého fotoaparátu, ťuknutí ostří, posuvník expozice
funguje.

Snímky série jsou YUV v největším rozlišení 4:3, které telefon pro YUV nabídne.

### Plán expozice

Z posledního snímku náhledu aplikace přečte, co zvolila automatická expozice (doba × citlivost). Cílová celková
světlost série je zhruba 8× víc světla než jeden snímek náhledu, při velmi tmavé scéně víc (doladí se podle
zkoušek na telefonu). Rozdělí se na snímky takto:

- doba jednoho snímku nejvýš **1/4 s**; když se telefon hýbe (gyroskop, jako u živého rozmazání portrétu), nejvýš
  **1/8 s**;
- počet snímků **6–15**, aby se série vešla do 2 s;
- zbytek dorovná citlivost (ISO), nejvýš do maxima telefonu.

Když telefon ruční expozici nepodporuje (chybí `MANUAL_SENSOR`), série se vyfotí s automatickou expozicí
a sečte se jen kvůli šumu. *(Volba Claude.)*

### Sloučení

1. První ostrý snímek je referenční. Ostatní se k němu **zarovnají posunem**: hledá se posun na zmenšených
   jasových obrázcích, od hrubého po jemný.
2. Snímky, které jsou výrazně rozmazané nebo posunuté víc, než jde spolehlivě zarovnat, se zahodí.
3. Sčítá se po dlaždicích. Dlaždice, která se od reference hodně liší (něco se v ní pohnulo), dostane menší váhu,
   aby nevznikali duchové.
4. Sčítá se průběžně, jak snímky přicházejí, do 16bitového součtu. V paměti tak není celá série najednou.
   *(Volba Claude.)*

Zarovnává se jen posunem, ne otočením. *(Volba Claude, otočení přijde, až by zkoušky ukázaly, že je potřeba.)*

### Tónování

Z průměru se udělá výsledek: jasová křivka zvedne stíny a zachová světla, mírné doostření, převod na JPEG
s kvalitou jako ostatní fotky.

### Během focení

Na obrazovce je „Drž telefon v klidu“ a kroužek, který se vyplní za dobu série. Pak „Zpracovávám…“ jako u portrétu.

### Když to nejde

Když se vlastní noc nepovede otevřít nebo vyfotit, aplikace ukáže hlášku s důvodem a do restartu použije noční
režim Motoroly, má-li ho telefon, jinak obyčejnou fotku. *(Volba Claude, stejně jako u skrytého fotoaparátu.)*

### Režim NOC v liště

Dnes se NOC ukazuje jen tam, kde telefon má noční rozšíření. Vlastní noc funguje všude, kde jde fotoaparát
otevřít přes Camera2, takže se NOC ukáže vždy (zadní i přední fotoaparát).

### Diagnostika

Nastavení → Fotoaparáty dostane řádek „Noc: expozice až X s, ISO až Y, ruční expozice ano/ne“. Uživatel je
vzdálený a nemůže připojit telefon kabelem. Podle tohoto řádku se doladí plán expozice.

## Ověření

- Jednotkové testy pro plán expozice, zarovnání (umělé obrázky s posunem), váhy dlaždic proti duchům,
  tónovou křivku.
- Emulátor: režim NOC se otevře, série se vyfotí, fotka se uloží, aplikace nespadne.
- Telefon: uživatel porovná noční fotku s nativní aplikací Motoroly.

## Nechává se na stavitele

Přesné prahy (velikost dlaždic, mez rozmazání, váhy), rozlišení pro zarovnání a tvar tónové křivky.
