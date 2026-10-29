# FoldBook

**Lecteur de livres et de documents Android pensé pour les téléphones pliables.** Bibliothèque locale, lecture hors ligne, rendu PDF, chapitres EPUB, bandes dessinées CBZ, affichage du texte, taille de police réglable et mémorisation de la position.

> État du projet : source initiale en cours de validation. Le workflow GitHub Actions ci-dessous doit compiler l'application et publier un APK debug en artefact. Le binaire doit être testé sur appareil avant toute distribution ; aucun APK déjà compilé n'est présenté comme validé.

## Fonctionnalités

- Import par le sélecteur de fichiers Android (Storage Access Framework) ; pas besoin d'accès global au stockage.
- Bibliothèque locale avec recherche par nom/extension et reprise de la dernière position.
- PDF : rendu natif via Android `PdfRenderer`, navigation de page.
- EPUB : lecture des chapitres ordonnés via le manifeste et le spine, avec rendu HTML/XHTML local.
- Texte et documents : TXT/TEXT, Markdown, HTML/HTM/XHTML, FB2, RTF, DOCX et ODT (extraction du texte, sans reproduction parfaite de la mise en page).
- Bandes dessinées : CBZ et images JPG/JPEG/PNG/WEBP/GIF.
- Lecteur texte : recherche sur la page/section, taille du texte, marque-page, lecture vocale Android Text-to-Speech.
- Interface responsive : bibliothèque sur une, deux ou trois colonnes selon la largeur disponible ; pas d'orientation imposée, convient à la fenêtre externe et au grand écran déplié.
- Hors ligne : aucun compte nécessaire, aucune autorisation réseau déclarée, les fichiers d'origine ne sont jamais modifiés.
- Intention Android VIEW/SEND pour recevoir un document depuis une autre application.
- GitHub Actions : tests unitaires et construction de l'APK debug à chaque push/PR sur `main`.

## Formats pris en charge et limites

| Extension | Lecture intégrée | Détails |
|---|---|---|
| PDF | Oui | `.pdf`; certains PDF protégés ou endommagés peuvent échouer. |
| EPUB | Oui | `.epub`; les styles et mises en page inhabituels peuvent varier. |
| Texte | Oui | `.txt`, `.text`, `.md`, `.markdown`. |
| HTML | Oui | `.html`, `.htm`, `.xhtml`; JavaScript est désactivé dans WebView. |
| Livres/XML | Oui | `.fb2` ; extraction du texte principal. |
| Documents bureautiques | Partiel | `.rtf`, `.docx`, `.odt` convertis en texte ; tables et mise en page complexes peuvent être simplifiées. |
| Bandes dessinées | Oui | `.cbz` et fichiers image JPG/JPEG/PNG/WEBP/GIF. |
| Kindle et archives RAR | Externe | `.mobi`, `.azw`, `.azw3`, `.cbr` : pas de prise en charge intégrée garantie dans cette version ; utiliser « Ouvrir avec ». |
| Autres formats | Externe | Par exemple `.djvu`, `.xps`, `.oxps`, `.doc`, `.xls`, `.ppt`, livres chiffrés et fichiers DRM. |

**« Tous les formats » au sens littéral n'est pas une promesse réaliste** : certains formats sont propriétaires, chiffrés, protégés par DRM ou nécessitent des moteurs de rendu spécialisés. Le tableau indique ce que cette version ouvre réellement et ses limites.

## Compilation de l'APK

### Android Studio

1. Ouvrir le dossier comme projet Gradle.
2. Utiliser JDK 17, Gradle 8.13, Android Gradle Plugin 8.11.1, Kotlin 2.1.21 et Android SDK Platform 36 (Build Tools 36.0.0).
3. Synchroniser Gradle puis choisir **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
4. L'APK debug se trouve dans `app/build/outputs/apk/debug/app-debug.apk`.

Commande équivalente, avec Android SDK installé et réseau vers les dépôts Maven :

```bash
./gradlew testDebugUnitTest assembleDebug
```

Sur Windows : `gradlew.bat testDebugUnitTest assembleDebug`. Le script `gradlew` fourni télécharge Gradle 8.13 si aucun Gradle système n'est installé ; le SDK Android doit être installé localement.

### GitHub Actions

Le workflow [Build FoldBook APK](.github/workflows/android.yml) installe Android SDK 36, exécute les tests, construit l'APK debug et téléverse l'artefact `foldbook-debug-apk`. Il se lance sur chaque push/PR vers `main` et peut être démarré manuellement depuis l'onglet Actions.

Cet APK debug n'est pas une version de production signée. Une distribution exige une clé de signature conservée en secret, des essais sur appareils réels et une revue des licences tierces.

## Appareils pliables

La grille de bibliothèque s'adapte à la largeur de fenêtre. Le lecteur occupe l'espace disponible en mode déplié et fonctionne aussi sur l'écran externe et en multi-fenêtre. La première version n'a pas encore de mode PDF à deux pages côte à côte ni de détection dédiée de la charnière.

## Confidentialité et sécurité

- Traitement local : aucune synchronisation distante et aucune autorisation INTERNET.
- Les fichiers sont importés avec un URI accordé par Android ; pas de recherche du stockage complet.
- L'extraction ZIP/EPUB/CBZ vérifie les chemins pour bloquer les traversées de répertoires et limite le volume extrait et le nombre d'entrées.
- Les documents source ne sont jamais édités. Le cache de lecture peut être nettoyé par Android si l'espace manque.
- Le parseur XML désactive les entités externes et refuse les déclarations DOCTYPE.
- Les chapitres HTML sont rendus avec JavaScript désactivé ; les scripts et les attributs d'événements sont retirés dans les documents convertis.

## Dépendances principales

- AndroidX Core et AppCompat
- Material Components
- Android `PdfRenderer`
- Configuration Gradle / Kotlin documentée dans `app/build.gradle.kts`

Voir [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md), [SECURITY.md](SECURITY.md) et [CONTRIBUTING.md](CONTRIBUTING.md).

## Organisation du dépôt

```text
FoldBook/
├── .github/workflows/android.yml
├── app/src/main/java/com/apkfold/foldbook/
│   ├── MainActivity.kt
│   ├── ReaderActivity.kt
│   ├── Book.kt
│   └── BookEngine.kt
├── app/src/main/res/
├── app/src/test/java/com/apkfold/foldbook/BookEngineTest.kt
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

## Feuille de route

- Tests instrumentés sur Galaxy Z Fold / Pixel Fold et fenêtre externe.
- Affichage PDF double-page et recherche plein texte.
- Table des matières EPUB, signets multiples et position de lecture plus précise.
- Amélioration de l'extraction des métadonnées et de la conversion FB2/RTF/DOCX/ODT.
- Support spécialisé de DjVu/XPS et d'autres formats après choix de bibliothèques appropriées et revue de leurs licences.
