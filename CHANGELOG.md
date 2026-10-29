# Journal des modifications

## 1.2.0 — 2026-10-29

- Lecture immersive : plus aucun bouton plein écran. Le livre occupe tout l'écran, sans barre.
- Navigation façon Kindle : toucher le tiers gauche ou droit tourne la page, et un balayage horizontal fait de même.
- Toucher le centre affiche le menu (titre, taille du texte, pagination, sommaire, thème, recherche) ; toucher le texte le referme.
- Messages « Début du livre » / « Fin du livre » aux extrémités.
- Animation de changement de page : l'ancienne page glisse hors de l'écran pendant que la nouvelle arrive.
- Lecture : mode plein écran (masque la barre système et le titre ; le retour arrière le quitte).
- Lecture : thèmes Clair, Sépia et Nuit, mémorisés. En mode nuit, les pages PDF sont inversées.
- Lecture : sommaire pour les EPUB et documents découpés en parties.
- Lecture : « Aller à » la page (ou toucher le compteur), progression en pourcentage.
- Taille du texte mémorisée entre les sessions ; l'écran reste allumé pendant la lecture.
- Lecture : mise en page des chapitres (interligne, largeur de colonne confortable).
- BD : décodage des images hors du thread principal, réduit à la taille de l'écran (moins de risques de lag ou de mémoire saturée).

- EPUB, FB2, DOCX et ODT : ils s'ouvrent enfin sur téléphone. Le parseur XML d'Android refusait l'option de sécurité `disallow-doctype-decl` (« Ce format ne peut pas être ouvert ici »). Les options sont désormais facultatives et les déclarations `<!ENTITY` sont refusées par l'application elle-même.
- Affichage : les boutons ne passent plus sous la barre d'état ni la barre de navigation (Android 15+, bord à bord) ; icônes des barres lisibles en mode nuit.
- Sécurité : le filtre des attributs `on*=` (onclick, onerror…) et des balises `<script>`/`<iframe>` orphelines fonctionne enfin (l'expression régulière était inopérante).
- PDF : la page n'est plus recadrée ; le rendu s'adapte à la taille du bitmap et le ratio est conservé lors du plafonnement.
- Synthèse vocale : le premier appui lit bien le texte, même si le moteur TTS n'est pas encore prêt.
- RTF : les accents (`\'e9`) et les caractères Unicode (`\uN?`) sont décodés au lieu d'être remplacés par des espaces.
- PDF : le descripteur de fichier est fermé après le comptage des pages.
- Tests de régression ajoutés pour le nettoyage HTML et le RTF.

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
