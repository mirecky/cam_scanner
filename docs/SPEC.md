# Špecifikácia aplikácie Skener dokumentov

Verzia dokumentu: 1.1 (schválený rozsah fázy 1, doplnené AI funkcie fázy 2)
Dátum: 11. 10. 2026

## 1. Účel a kontext

Cieľom je vytvoriť natívnu aplikáciu pre Android na profesionálne skenovanie dokumentov fotoaparátom mobilného telefónu. Aplikácia je určená výhradne na osobné použitie jedného používateľa a nebude distribuovaná prostredníctvom Google Play.

Primárny scenár použitia je postupné skenovanie väčšieho počtu listov formátu A4: používateľ prikladá listy pod telefón a aplikácia bez ďalšej interakcie rozpozná dokument, nasníma ho, upraví perspektívu a po výmene listu nasníma ďalšiu stranu. Výsledkom je jeden súbor PDF s vyhľadávateľnou textovou vrstvou.

### 1.1 Východiská z analýzy existujúcich riešení

| Riešenie | Silné stránky | Identifikované nedostatky |
|---|---|---|
| CamScanner | spoľahlivá detekcia hrán, kvalitné zvýraznenie textu | reklamy, vodoznak v bezplatnej verzii, OCR len v predplatnom, väzba na vlastný cloud, bezpečnostný incident reklamného SDK (2019), nadbytočné povolenia |
| Adobe Scan | režimy podľa typu predlohy (dokument, tabuľa, kniha, karta), automatické snímanie, nástroje úprav po nasnímaní | nedostatočné overenie, či ide o dokument (falošné zábery ľubovoľnej scény), agresívne filtre |

Z uvedeného vyplývajú tieto princípy návrhu:

- plne offline spracovanie jadra (snímanie, OCR, PDF); cloudové AI len ako voliteľná nadstavba spúšťaná používateľom (kap. 4.1),
- žiadne reklamy, žiadna telemetria, žiadny cloud tretej strany,
- minimálny rozsah povolení,
- prísne overovanie kandidáta na dokument pred automatickým nasnímaním,
- OCR ako štandardná súčasť, nie platená nadstavba.

## 2. Cieľové prostredie

| Parameter | Hodnota |
|---|---|
| Zariadenie | Samsung Galaxy S25 Ultra |
| Operačný systém | Android 16 (One UI 8) |
| `minSdk` / `targetSdk` | 36 / 36 |
| Jazyk rozhrania | slovenčina, angličtina; výber podľa jazyka systému (záložne angličtina) |
| Distribúcia | podpísaný APK zostavovaný cez GitHub Actions, inštalácia mimo obchodu |

## 3. Rozsah fázy 1

### F1 – Živá detekcia dokumentu

- Analýza obrazu z kamery v reálnom čase (cieľ ≥ 15 snímok/s analýzy).
- Zobrazenie obrysu nájdeného dokumentu nad náhľadom kamery.
- Farebné rozlíšenie stavu obrysu: hľadanie, nestabilný, pripravený na snímanie.
- Podporované predlohy: list papiera (primárne A4) a tabuľa.

### F2 – Ochrana pred falošnými zábermi

Kandidát na dokument sa akceptuje, len ak spĺňa všetky tieto podmienky:

- konvexný štvoruholník,
- plocha v stanovenom rozsahu podielu záberu (predbežne 20–98 %),
- pomer strán po korekcii perspektívy zodpovedajúci režimu (dokument: 1,15–1,75 okolo A4 = 1,414; tabuľa: 1,0–3,0),
- v režime dokument orientácia na výšku (ochrana pred obrazovkami, televízormi a obrazmi, ktoré bývajú na šírku),
- vnútorné uhly v prijateľnom rozsahu (vylúčenie extrémne skosených tvarov),
- dostatočný kontrast hrany voči pozadiu: pozdĺž každej strany je vnútri svetlejšie než vonku (odolné voči vzorovaným podkladom),
- dostatočná ostrosť obrazu (napr. rozptyl Laplaceovho operátora nad prahom).

### F3 – Automatické snímanie a rozpoznanie ďalšieho listu

Funkcia je aktívna iba vtedy, keď ju používateľ zapne prepínacou ikonou.

**Prepínač automatického snímania:**

- umiestnený na spodnej ovládacej lište priamo vedľa spúšte (dosah palcom),
- jednoznačne rozlíšené stavy zapnuté/vypnuté (ikona aj farba),
- stav sa uchováva medzi spusteniami aplikácie,
- ručná spúšť je dostupná vždy, aj pri zapnutom automatickom snímaní.

**Stavový automat:**

```
HĽADANIE ──(platný kandidát)──► STABILIZÁCIA
STABILIZÁCIA ──(stabilný ≥ T_stab, ostrý)──► SNÍMANIE
STABILIZÁCIA ──(strata kandidáta / pohyb)──► HĽADANIE
SNÍMANIE ──(fotografia v plnom rozlíšení uložená)──► ČAKANIE_NA_ZMENU
ČAKANIE_NA_ZMENU ──(zistená zmena: pohyb v zábere alebo odlišný obsah)──► HĽADANIE
```

- `T_stab` predbežne 0,6 s (nastaviteľné).
- Zmena listu sa vyhodnocuje kombináciou dvoch signálov: (a) detekcia pohybu v zábere (ruka, výmena listu), (b) porovnanie obsahu aktuálneho kandidáta s naposledy nasnímanou stranou (percepčný odtlačok obrazu po korekcii perspektívy).
- Rovnaký list sa nesmie nasnímať dvakrát; nový list položený presne na miesto predchádzajúceho sa musí rozpoznať.
- Používateľ dostáva stavovú spätnú väzbu: „Hľadám dokument“, „Drž“, „Nasnímané – polož ďalší list“, počet nasnímaných strán, krátka haptická odozva pri nasnímaní.

### F4 – Úprava geometrie

- Korekcia perspektívy na pravouhlý obraz.
- V režime dokument sa výstup prispôsobí presne pomeru strán A4 (1 : 1,414), ak sa nameraný pomer od neho líši najviac o 15 %; pri výbere obrysu majú prednosť tvary blízke A4. Iné formáty si zachovajú nameraný pomer.
- Ručná úprava štyroch rohov s lupou pri ťahaní.
- Otočenie o 90°.

### F5 – Obrazové filtre

| Filter | Určenie |
|---|---|
| Dokument (čiernobiely) | text, adaptívne prahovanie, odstránenie tieňov |
| Dokument (odtiene šedej) | text s obrázkami, vyrovnanie pozadia |
| Farebne vylepšený | farebné predlohy, vybielenie pozadia, zachovanie farieb |
| Tabuľa | potlačenie odleskov a pozadia, zvýraznenie ťahov fixiek |
| Originál | bez úprav, iba geometrická korekcia |

Predvolený filter sa určuje podľa režimu (dokument/tabuľa) a dá sa zmeniť pre jednotlivú stranu aj pre celý dokument.

### F6 – Správa strán

- Náhľad všetkých strán rozpracovaného dokumentu; prehliadanie strany na celú obrazovku s približovaním dvoma prstami a dvojitým ťuknutím.
- Zmena poradia ťahaním, zmazanie, opätovné nasnímanie vybranej strany.
- Pokračovanie v skenovaní do existujúceho rozpracovaného dokumentu.

### F7 – OCR a vyhľadávateľné PDF

- Plne offline rozpoznávanie textu pre slovenčinu a angličtinu.
- Výstupom je PDF so stranou ako obrázkom a neviditeľnou textovou vrstvou umiestnenou podľa polôh slov (vyhľadávanie a kopírovanie textu v ľubovoľnom prehliadači PDF).
- Spracovanie beží na pozadí; priebeh sa zobrazuje v notifikácii typu Live Update.
- Výber OCR enginu sa rozhodne porovnaním (pozri kap. 6).

### F8 – Export a ukladanie

- Hotové PDF sa ukladajú do pevného priečinka `Dokumenty/Skener` vo verejnom úložisku telefónu (MediaStore, bez ďalších povolení).
- Rozpracované dokumenty a medzivýsledky sa ukladajú v internom úložisku aplikácie a do priečinka `Dokumenty/Skener` sa nedostávajú.
- Predvolený názov súboru: `Sken_RRRR-MM-DD_HH-MM.pdf`, s možnosťou premenovania pred uložením.
- Voľby exportu: veľkosť strany (A4 / podľa predlohy), kvalita obrazu (štandardná / vysoká).
- Zdieľanie hotového PDF systémovým dialógom.

### F9 – Integrácia so systémom Android 16

- Vzhľad Material 3 Expressive, dynamické farby, zobrazenie od okraja po okraj.
- Dlaždica v rýchlych nastaveniach na priame spustenie skenovania.
- Live Update notifikácia pre OCR a tvorbu PDF.
- Využitie rozšírení kamery cez CameraX, ak ich zariadenie podporuje (HDR, nočný režim).

## 4. Mimo rozsahu fázy 1

**Fáza 2:** knižnica dokumentov s fulltextovým vyhľadávaním v texte z OCR, kopírovanie a export textu (TXT), export strán ako JPG/PNG, voliteľné AI funkcie podľa kap. 4.1.

**Fáza 3:** režim kniha (rozdelenie dvojstrany), režim preukaz (obe strany karty na jednu stranu A4), podpis, anotácie, retuš škvŕn.

**Vylúčené:** ovládanie tlačidlami hlasitosti, cloudové úložiská a synchronizácia v aplikácii, reklamy, analytika.

### 4.1 AI funkcie (fáza 2)

**Zásady:**

- AI funkcie sú voliteľné a spúšťajú sa výlučne výslovným pokynom používateľa nad konkrétnym dokumentom. Nikdy sa nespúšťajú automaticky ani hromadne na pozadí.
- Snímanie, OCR a tvorba vyhľadávateľného PDF zostávajú plne lokálne. Textová vrstva PDF pochádza vždy z lokálneho OCR, nie z AI.
- Komunikácia prebieha s Claude API pomocou vlastného API kľúča používateľa, uloženého šifrovane (Android Keystore).
- Výstup AI sa pred uložením vždy zobrazí na kontrolu a úpravu. Pôvodný sken sa nikdy neprepisuje.
- Pri čísliciach a sumách (najmä pri tabuľkách) sa používateľovi zobrazí upozornenie na povinnú kontrolu voči originálu.

| ID | Funkcia | Výstup |
|---|---|---|
| AI1 | Prepis rukopisu a tabule | textový prepis uložený ako samostatný súbor (TXT/DOCX) vedľa PDF |
| AI2 | Návrh názvu súboru podľa obsahu (napr. `2026-10-11_Faktura_Orange.pdf`) | návrh názvu, ktorý používateľ potvrdí alebo upraví; bez potvrdenia platí predvolený názov podľa F8 |
| AI3 | Prevod dokumentu do formátu Word | DOCX so zachovanou štruktúrou (nadpisy, odseky, zoznamy, tabuľky) |
| AI4 | Prevod tabuliek do formátu Excel | XLSX, jedna tabuľka na hárok, číselné bunky ako čísla (nie text) |
| AI5 | Vyťahovanie údajov podľa šablóny | štruktúrované polia (napr. faktúra: dodávateľ, číslo, dátum vystavenia a splatnosti, suma, IBAN, variabilný symbol); zobrazenie, kopírovanie, pripojenie riadku do zvoleného súboru XLSX |

**Technický princíp AI3–AI5:** AI nevracia hotový súbor. Vracia štruktúrované údaje (JSON podľa pevnej schémy) a súbory DOCX a XLSX z nich zostavuje aplikácia lokálne. Formát výstupu je tak vždy platný a pod kontrolou aplikácie. AI pracuje s obrazom strany aj s textom z lokálneho OCR, čo znižuje riziko chybného prečítania.

**Dôsledok pre povolenia:** zavedením AI funkcií pribudne povolenie `INTERNET`, ktoré sa využíva výhradne na komunikáciu s AI API. Až do nastavenia API kľúča aplikácia nevykonáva žiadnu sieťovú komunikáciu.

## 5. Nefunkčné požiadavky

- **Súkromie:** vo fáze 1 žiadne sieťové povolenie (`INTERNET` sa nedeklaruje). Povolenia: `CAMERA`, `POST_NOTIFICATIONS`. Vo fáze 2 pribudne `INTERNET` výhradne pre AI funkcie (kap. 4.1).
- **Výkon:** detekcia v reálnom čase bez zasekávania náhľadu; OCR jednej strany A4 cieľovo do 3 s na S25 Ultra.
- **Spoľahlivosť:** nasnímané strany sa ukladajú priebežne, takže pád aplikácie ani jej ukončenie systémom nespôsobia stratu práce.
- **Kvalita výstupu:** text čitateľný pri tlači A4; veľkosť PDF pri štandardnej kvalite cieľovo do 300 kB na stranu.

## 6. Technické riešenie

| Oblasť | Riešenie |
|---|---|
| Jazyk a UI | Kotlin, Jetpack Compose, Material 3 Expressive |
| Kamera | CameraX (`Preview`, `ImageAnalysis`, `ImageCapture`) |
| Detekcia dokumentu | OpenCV (detekcia hrán, kontúry, aproximácia štvoruholníka); podľa výsledkov testov neskôr segmentačný model TFLite |
| Spracovanie obrazu | OpenCV (perspektívna transformácia, filtre) |
| OCR | kandidáti: Tesseract 5 s modelmi `tessdata_best` (slk, eng) a Google ML Kit Text Recognition v2 |
| PDF | PdfBox-Android (obrazová vrstva a neviditeľná textová vrstva) |
| Perzistencia | Room (metadáta), interné súbory (strany), DataStore (nastavenia) |
| Práca na pozadí | WorkManager |
| Zostavenie | Gradle, GitHub Actions (artefakt: podpísaný APK) |

**Voľba OCR enginu.** ML Kit rozpoznáva znaky latinky robustne, nemá však jazykový model pre slovenčinu. Tesseract s modelom `slk` jazykový model má a na geometricky korigovaných, binarizovaných stranách dosahuje vysokú presnosť. Konečná voľba sa urobí porovnaním oboch enginov na reprezentatívnej vzorke skutočných dokumentov používateľa. Kritériá sú chybovosť na úrovni znakov (CER) so zvláštnym zreteľom na diakritiku a čas spracovania.

## 7. Postup realizácie

1. Kostra projektu, zostavovanie APK cez GitHub Actions.
2. Kamera a živá detekcia dokumentu (F1, F2).
3. Automatické snímanie a stavový automat (F3); ladenie na zariadení podľa spätnej väzby.
4. Úprava geometrie a filtre (F4, F5), správa strán (F6).
5. Testovací režim OCR, porovnanie enginov, voľba (F7).
6. Generovanie vyhľadávateľného PDF a ukladanie do zvoleného priečinka (F7, F8).
7. Integrácia so systémom (F9), lokalizácia, záverečné ladenie.

## 8. Akceptačné kritériá fázy 1

- Pri zapnutom automatickom snímaní sa sada 20 listov A4 prikladaných po jednom nasníma bez dotyku obrazovky, bez duplicít a bez vynechania listu.
- Pri namierení na scénu bez dokumentu (podlaha, osoba, nábytok) nedôjde k automatickému nasnímaniu.
- Vo výslednom PDF možno vyhľadať slovo so slovenskou diakritikou a skopírovať text.
- PDF sa objaví v priečinku `Dokumenty/Skener`.
- Aplikácia funguje v režime lietadlo.
