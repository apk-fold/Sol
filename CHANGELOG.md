# Journal des modifications

## 1.1.0 — 2026-10-29

- Correction de la résolution des chemins EPUB encodés (espaces, accents et caractères spéciaux), du chemin OPF et des documents SVG dans le spine.
- Chargement des chapitres EPUB avec une URI de fichier locale adaptée aux chemins.
- Adaptation automatique de la grille à la largeur de fenêtre mesurée ; nouveau rendu des pages PDF quand la fenêtre change de taille.
- Ajout de tests de régression pour les chemins EPUB avec caractères encodés.

## 1.0.0 — 2026-10-29

- Création du projet Android natif FoldBook.
- Bibliothèque locale, import via Android Storage Access Framework, recherche et reprise de lecture.
- Rendu PDF avec `PdfRenderer`, navigation des pages, chapitres EPUB et navigation des pages CBZ.
- Lecteur texte pour TXT, Markdown, HTML/XHTML, FB2, RTF, DOCX et ODT.
- Solution de repli vers une application externe pour les formats non pris en charge, notamment les fichiers Kindle et les archives RAR dans cette version.
- Réglage de la taille du texte, thèmes de lecture, marque-page de reprise et TTS Android.
- Interface responsive pour téléphone, fenêtre large, tablette et appareils pliables.
- Tests unitaires initiaux et workflow GitHub Actions pour produire l'APK debug.

## Préparation de la préversion

- Le workflow CI exécute les tests et compile l'APK debug avant de créer une préversion GitHub Release lorsque le commit porte le marqueur de publication.
