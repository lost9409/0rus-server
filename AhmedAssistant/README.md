# 0rus — assistant visuel v0.2.0

Cette APK conserve toute la chaîne matérielle validée sur l'Universal Phone UBS1 et ajoute l'assistant IA :

- détection dynamique de la caméra UVC SYX / Hua Que (`VID 0x0DBA`, `PID 0xD565`) ;
- demande d'autorisation USB et aperçu en direct ;
- capture JPEG avec le bouton à l'écran ou `Lecture/Pause` du bouton Bluetooth ;
- journal des commandes `Lecture/Pause`, `Suivant`, `Précédent` et volume ;
- confirmation vocale Android « Photo capturée » ;
- sauvegarde dans `Images/0rus` via MediaStore.
- diagnostic de tous les périphériques USB vus directement par Android ;
- détection de secours par interface UVC si les identifiants annoncés diffèrent.
- autorisation USB explicite Android 14 avec possibilité de réessayer à l’écran.
- demande préalable de la permission Caméra, obligatoire sous Android 9+ pour une caméra USB vidéo.
- envoi HTTPS de la photo vers le serveur privé 0rus ;
- détection de plusieurs questions, sous-questions et QCM ;
- réponses de longueur adaptée, affichées puis lues en français ;
- navigation vocale avec `Suivant` et `Précédent` ;
- pause/reprise avec `Lecture/Pause` pendant la lecture ;
- mémoire de conversation réinitialisable dans les paramètres.

L'application n'enregistre ni audio ni vidéo, ne demande pas l'accès global au
stockage et ne contient aucune clé OpenAI. Elle utilise uniquement un jeton limité
pour joindre le serveur privé décrit dans le dossier `0rus-server`.

## Installation

1. Autoriser l'installation d'applications inconnues sur le téléphone.
2. Installer l'APK de débogage fourni, ou utiliser :
   `adb install -r 0rus-v0.2.0-debug.apk`
3. Brancher la caméra USB-A via l'adaptateur OTG.
4. Ouvrir **0rus**, autoriser la permission Android **Caméra**, puis l'accès USB.
5. Ouvrir **Paramètres IA**, saisir l’adresse HTTPS du serveur et son jeton 0rus.
6. Vérifier l'aperçu, puis appuyer sur `Lecture/Pause` du bouton Bluetooth.
7. Contrôler l’état `Analyse en cours`, puis la réponse affichée et lue.

## Compilation

Ouvrir ce dossier avec Android Studio (JDK 17, SDK Android 34), puis lancer la
configuration `app`. En ligne de commande :

```bash
./gradlew :app:assembleDebug
```

L'APK est générée dans `app/build/outputs/apk/debug/`.

## Commandes du bouton

- sans lecture active : `Lecture/Pause` capture et lance l’analyse ;
- pendant la lecture : `Lecture/Pause` met en pause ou reprend la section ;
- `Suivant` et `Précédent` changent de question ;
- les touches de volume conservent le réglage audio Android.

Utilisation prévue uniquement sur des documents et dans des situations où la
capture et l'assistance sont autorisées.
