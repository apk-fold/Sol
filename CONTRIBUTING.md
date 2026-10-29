# Contribuer à FoldBook

1. Utilisez JDK 17 et Android SDK Platform 36.
2. Lancez `./gradlew testDebugUnitTest` puis `./gradlew assembleDebug`.
3. Pour tout changement de format, ajoutez un cas de test ou un exemple anonymisé quand c'est possible.
4. N'ajoutez jamais de livre soumis au droit d'auteur, de fichier privé, de clé de signature ou de données personnelles aux tests.
5. Ne faites pas confiance aux chemins contenus dans une archive. Toute extraction doit garder des limites de taille et empêcher les traversées de répertoires.
6. Les lecteurs de fichiers doivent rester hors ligne par défaut et ne pas activer JavaScript dans le HTML importé sans revue de sécurité.
7. Testez au minimum une fenêtre compacte et une fenêtre dépliée large ; évitez d'imposer une orientation.

Les Pull Requests doivent décrire le format testé, la version Android et les limites connues. Le workflow `Build FoldBook APK` vérifie les tests unitaires et construit l'APK debug.
