# Parabol voor macOS en Windows (desktop)

De desktopversie van Parabol, gebouwd met Electron. Parabol bevat geen content: je speelt af van je eigen aanbieder (Xtream Codes API). Vanaf **v2.2.0** heeft hij dezelfde opzet en functies als de Android-app v2.2.0.

## Nieuw in v2.4.1

Accountkeuze toont alleen de accountnaam; de naam is verplicht. Ondertiteling die in de film zelf zit (tekst-ondertitels zoals SRT/ASS in .mkv) kies je in de speler bovenin; ondertitels als beeld (PGS/DVD) worden getoond maar zijn niet te gebruiken.

## Nieuw in v2.4.0

Parabol start altijd op de accountkeuze, ook met één account. De instelling "Tijdcorrectie tv-gids" is verwijderd.

## Nieuw in v2.3.0: accountkeuze

Met meerdere accounts opent Parabol op **"Wie kijkt er?"**: kies een account en je komt op Home; Esc brengt je terug naar de keuze. Bij inloggen en toevoegen geef je het account een eigen naam, los van de gebruikersnaam. Wisselen kan ook via je accountnaam rechtsboven op Home.

## Nieuw in v2.2.4: geluid loopt gelijk bij HEVC-films en na verder kijken

Bij omgezette films (vooral HEVC/H.265 in .mkv) liep het geluid seconden achter wanneer de film niet vanaf het begin startte, bijvoorbeeld bij verder kijken of spoelen. Door een afrondingsverschil van een fractie van een milliseconde sprong het beeld naar een eerder beginpunt dan het geluid. Beide starten nu altijd op hetzelfde punt. De handmatige geluidsknop uit v2.2.3 is weer weg. Onder de titel staat nog wel het formaat van de film en hoe hij wordt afgespeeld.

## Nieuw in v2.2.2: geluid loopt gelijk

Bij omgezette films (mkv, AC3/DTS-geluid) kon het geluid een paar seconden voor of achter lopen. Dat gebeurde bij bestanden waarin geluid en beeld niet precies op hetzelfde moment beginnen (komt vaak voor) en na spoelen of verder kijken. Parabol knipt beeld en geluid nu op hetzelfde punt en legt het begin van het geluid exact gelijk met het eerste beeld.

## Nieuw in v2.2.1: films in elk formaat

Films en series zijn vaak .mkv-bestanden of hebben geluid in AC3, E-AC3, DTS of TrueHD. De ingebouwde speler van de desktop-app kon die niet afspelen ("mogelijk ondersteunt deze speler het formaat niet"). Parabol onderzoekt nu eerst elk bestand en zet wat niet direct afspeelbaar is tijdens het kijken om (beeld meestal ongewijzigd, geluid naar AAC). Bij zo'n film zie je een eigen afspeelbalk: spoelen (ook met ← en →), pauzeren (spatie), volledig scherm (F) en, als de film meerdere audiosporen heeft, een keuzelijst voor de taal. Ondertiteling blijft na het spoelen gelijk lopen. Lukt het toch niet, dan zegt de melding waarom (bijvoorbeeld "de server weigert deze video (403)").

Voor de Mac zijn er nu twee bestanden: **Apple Silicon** (M1, M2, M3, M4 …) en **Intel**. Welke je hebt, zie je bij Apple-menu › Over deze Mac.

Hiervoor wordt [FFmpeg](https://ffmpeg.org) meegeleverd (via het pakket `ffmpeg-static`, GPL-licentie; de licentie staat bij de app in `node_modules/ffmpeg-static`).

## Downloaden

Kant-en-klare bestanden staan bij de release **Parabol Desktop** op de [releasepagina](https://github.com/abouzaggaoui-oss/Parabol/releases):

- **macOS**: `Parabol-Desktop-<versie>-macOS-AppleSilicon.dmg` (M-chip) of `…-macOS-Intel.dmg`. Openen en Parabol naar Apps slepen. De eerste keer: rechtsklik op Parabol › **Open** (of Systeeminstellingen › Privacy en beveiliging › **Toch openen**), omdat de app niet bij Apple is geregistreerd.
- **Windows**: `…-Windows-Setup.exe` (installer) of `…-Windows-Portable.exe` (zonder installatie). SmartScreen kan de eerste keer waarschuwen: **Meer info › Toch uitvoeren**.

De bestanden worden automatisch gebouwd door de workflow *Desktop (macOS + Windows)* zodra er in deze map iets verandert en het versienummer in `package.json` nieuw is.

## Wat erin zit (v2.2.0)

- **Home** met drie tegels: Live tv, Films en Series; opnames, instellingen en je account rechtsboven.
- **Live tv**: groepen links (zoeken, Favorieten bovenaan), zenders met een **tijdlijn van de tv-gids**, en een gedeeld scherm met de zender die speelt, het programma en wat er hierna komt.
  - Een zender kiezen opent hem **volledig scherm**. **Esc** gaat terug naar het gedeelde scherm, nog een keer Esc stopt het kijken, nog een keer gaat naar Home.
  - Volledig scherm: **↑ ↓** of Page Up/Down = zappen, **Enter** of klikken = 3 seconden de zender en het programma.
  - Favoriet maken: **rechtsklik** op een zender of poster, of de ster.
  - Speelt een zender niet als HLS, dan probeert Parabol automatisch MPEG-TS.
- **Films en Series**: groepen links, posters rechts met zoeken; series met seizoenen en afleveringen. Films en afleveringen gaan **verder waar je was gebleven**.
- **Speler** voor films, series, terugkijken en opnames: afspeelbalk, ondertiteling laden (.srt/.vtt) of zoeken via OpenSubtitles, audiospoor kiezen, vorige/volgende aflevering.
- **Tv-gids** per zender met **opnemen** (ook "nu opnemen") en **terugkijken** bij zenders die dat ondersteunen.
- **Opnames** in *Films/Parabol* (Mac) of *Video's/Parabol* (Windows), af te spelen in de app. Sluit je het venster, dan blijft Parabol actief (menubalk / systeemvak) zodat opnames doorlopen; afsluiten met Cmd+Q of via het icoon › Afsluiten.
- **Instellingen**: meerdere accounts, OpenSubtitles-key en **Groepen beheren**: per land in één keer aan of uit, of per groep.
- Op de Mac de gewone menubalk (Cmd+Q, kopiëren/plakken, volledig scherm).

Je eerdere accounts, favorieten en verborgen groepen blijven bewaard.

## Zelf bouwen of starten

Vereist: Node.js.

```
npm install
npm start            # direct starten, zonder te bouwen
npm run dist:win     # Windows
npm run dist:mac     # macOS (alleen op een Mac)
```
