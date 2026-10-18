# Antrenor coordonate șah

Aplicație Android nativă în Kotlin, creată pornind de la interfața din
`/storage/emulated/0/Download/Meta AI/antrenor-sah.html`.

## Regula diagonalelor

- `a1–h8` este diagonala neagră: `a1`, `b2`, `c3`, `d4`, `e5`, `f6`, `g7`, `h8`.
- `a8–h1` este diagonala albă: `a8`, `b7`, `c6`, `d5`, `e4`, `f3`, `g2`, `h1`.

Notă: `a1–a8` este coloana `a`, nu o diagonală.

## Structură

Aplicația folosește controale Android native și o tablă responsive, fără internet
și fără dependențe UI suplimentare. Include:

- modurile „Găsește pătrățelul” și „Ghicește coordonata”;
- modul „Învață diagonale” cu explicații și coordonate vizibile;
- modul „Rapid 30s” cu cronometru și scor final;
- scor și streak;
- buton pentru afișarea diagonalelor;
- tabla orientată corect, cu `a1` în stânga-jos;
- feedback vizual și vibrație la răspuns;
- stare păstrată la rotirea ecranului;
- controale și pătrățele cu etichete pentru TalkBack;
- profil cu poreclă, dashboard de sesiuni/jocuri, istoric și greșeli frecvente;
- dificultate adaptivă pe trei niveluri și buton pentru antrenarea greșelilor;
- test JVM pentru regula culorilor diagonalelor și logica rundelor.

## Pictograma aplicației

Pictograma folosește designul cu pion alb pe fundal verde și magenta, inclusiv
variante adaptive pentru Android 8+ și variante PNG pentru dispozitivele mai
vechi. Sursa vectorială editabilă este:

```text
art/sah_ro_icon.svg
```

Manifestul declară `@mipmap/ic_launcher` și `@mipmap/ic_launcher_round`.

## Mascota DragonBones (scoată în v1.2.1)

Aplicația nu mai include runtime-ul DragonBones și mascota reginei. Motivul:
biblioteca apela `GLSurfaceView.onPause()` de pe firul GL, iar main thread-ul
aștepta firul GL în `surfaceDestroyed()` — un deadlock care producea ANR și
uciderea procesului de fiecare dată când un ecran acoperea `MainActivity`
(Lecții, Puzzle-uri). Randarea GL rămânea activă în fundal.

Eliminat în v1.2.1:

```text
app/libs/dragonbones-release.aar
app/src/main/assets/chess/queen_mascot.svg
app/src/main/assets/chess/queen_ske.json
app/src/main/assets/chess/queen_tex.json
app/src/main/assets/chess/queen_tex.png
app/src/main/java/com/sah/DragonBonesOverlay.kt
```

## Build

Rulează din acest director cu un Android SDK disponibil:

```bash
gradle :app:assembleDebug
```

Pe Termux, build-ul Gradle local rămâne intenționat nerulat conform regulii
workspace-ului; APK-ul trebuie construit în CI dacă este nevoie de un artefact.

## APK pe GitHub

Workflow-ul `.github/workflows/build-apk.yml` rulează automat la fiecare push pe
`main` și poate fi pornit manual din tab-ul **Actions**. După build, APK-ul apare
în secțiunea **Artifacts** ca `sah-debug-apk-<commit>`, disponibil 14 zile.

Workflow-ul `.github/workflows/release.yml` construiește `sah_ro-<versiune>.apk` și
`sah_ro-<versiune>.aab` semnate cu
keystore-ul ținut în secretele GitHub și se poate porni manual sau printr-un tag
`v*`. Pentru un tag, APK-ul este atașat automat unui GitHub Release. Secretele
necesare sunt `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`,
`ANDROID_KEY_ALIAS` și `ANDROID_KEY_PASSWORD`.

Fiecare release include și `SHA256SUMS`. Un tag precum `v1.2.3` produce
`versionName=1.2.3` și un `versionCode` determinist.

Fingerprint-ul public SHA-256 al certificatului de semnare este:

```text
0F:D3:55:62:A5:1C:8F:F4:9A:47:DD:92:FF:58:03:60:73:57:7A:86:D9:35:B2:EF:D0:B4:78:CA:5B:E2:26:B7
```
