# Journal des modifications

## 1.0.0 — 2026-10-29

- Création du projet Android natif FoldBook.
- Bibliothèque locale, import via Android Storage Access Framework, recherche et reprise de lecture.
- Rendu PDF avec `PdfRenderer`, navigation des pages, chapitres EPUB et navigation des pages CBZ/CBR.
- Lecteur texte pour TXT, Markdown, HTML/XHTML, FB2, RTF, DOCX et ODT.
- Prise en charge best-effort des MOBI/AZW legacy sans DRM, et solution de repli vers une application externe pour les formats non pris en charge.
- Réglage de la taille du texte, thèmes de lecture, marque-page de reprise et TTS Android.
- Interface responsive pour téléphone, fenêtre large, tablette et appareils pliables.
- Tests unitaires initiaux et workflow GitHub Actions pour produire l'APK debug.

## Préparation de la préversion

- Le workflow CI exécute les tests et compile l'APK debug avant de créer une préversion GitHub Release lorsque le commit porte le marqueur de publication.
