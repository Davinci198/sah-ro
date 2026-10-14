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
- test JVM pentru regula culorilor diagonalelor și logica rundelor.

## DragonBones v1.1.0

Aplicația include artefactul publicat:

```text
app/libs/dragonbones-release.aar
SHA-256: 639238ed2d6d2c68fb7f4954c809475aaee25e4c5eeaebfcc690f53d7f775807
```

Proiectul folosește Java/Kotlin target 17 pentru compatibilitate cu bytecode-ul
publicat în AAR.

Integrarea este opțională și nu afectează antrenorul dacă nu există un model
DragonBones. Pentru a afișa animația, adaugă exportul modelului în:

```text
app/src/main/assets/chess/piece_ske.json
app/src/main/assets/chess/piece_tex.json
app/src/main/assets/chess/piece_tex.png
```

Când cele trei fișiere există, `DragonBonesOverlay` le încarcă automat și pornește
prima animație disponibilă. Modelul inclus este `dragon_boy` din DragonBonesJS și
are animațiile `stand`, `walk`, `jump` și `fall`. Atribuirea este în
`THIRD_PARTY_NOTICES.md`.

Amprentele asset-urilor incluse:

```text
piece_ske.json:  89961578d447697de5a26911f398635d5c2ba658b087cff2383d4382b0c36713
piece_tex.json:  4dc7bc32d983d1c5819752bb86b707803897880ddc3c1a7033e3f4d874a88ef2
piece_tex.png:   21cda91ab0df91093f2176a883d40a1b58b2b93e777515f5238c20cb3711b250
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
