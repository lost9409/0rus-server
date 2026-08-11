# 0rus Server — v0.2.0

Ce petit serveur protège la clé OpenAI et donne à l’application Android :

- l’analyse directe des photos avec GPT‑5.6 ;
- la détection de plusieurs questions, sous-questions et QCM ;
- une longueur de réponse adaptée à la consigne ;
- la recherche dans les documents du projet avec `file_search` ;
- la recherche web contrôlée avec `web_search` ;
- une mémoire de conversation via `previous_response_id` ;
- une réponse structurée, affichable et lisible question par question.

La clé OpenAI ne doit jamais être copiée dans l’APK ou dans le téléphone.

## Configuration

Le serveur exige Node.js 20 ou plus récent et les variables suivantes :

```text
OPENAI_API_KEY=clé du projet OpenAI
OPENAI_MODEL=gpt-5.6
ORUS_APP_TOKEN=secret long et aléatoire propre à l’application
OPENAI_VECTOR_STORE_ID=vs_...   # facultatif au premier test
PORT=8787
```

Générez par exemple le jeton 0rus avec :

```bash
openssl rand -hex 32
```

Lancez ensuite le serveur derrière une adresse **HTTPS** :

```bash
npm test
npm start
```

Le point de contrôle est `GET /health` et l’application appelle `POST /v1/analyze`.
Le dépôt contient aussi un `Dockerfile` pour un hébergeur compatible avec les
conteneurs. Configurez toutes les valeurs comme secrets chez l’hébergeur ; ne
commitez jamais un fichier `.env`.

Dans l’application, ouvrez **Paramètres IA**, saisissez l’adresse HTTPS publique
du serveur et la même valeur que `ORUS_APP_TOKEN`.

## Ajouter les documents du projet

Copiez les PDF, DOCX, PPTX, TXT, Markdown et autres fichiers pris en charge dans
un dossier local, par exemple `documents/`, puis lancez :

```bash
OPENAI_API_KEY="votre-clé" npm run ingest -- ./documents
```

Le script affiche un identifiant `vs_...`. Ajoutez-le à
`OPENAI_VECTOR_STORE_ID` sur le serveur, puis redémarrez/redéployez celui-ci.
Si un identifiant existe déjà, fournissez-le lors de l’import pour enrichir la
même base :

```bash
OPENAI_API_KEY="votre-clé" OPENAI_VECTOR_STORE_ID="vs_..." npm run ingest -- ./documents
```

Les fichiers placés sur Google Drive doivent d’abord être téléchargés/synchronisés
sur le PC dans ce dossier. Une synchronisation Drive automatique pourra être
ajoutée dans une version ultérieure.

## Fonctionnement de la mémoire

Après chaque analyse, l’API renvoie un identifiant de réponse. L’application le
conserve localement et le renvoie à la capture suivante. **Effacer la mémoire**
dans Paramètres IA démarre une nouvelle conversation.

## Sécurité minimale incluse

- jeton applicatif obligatoire et comparé en temps constant ;
- limite de 20 analyses par adresse IP et par tranche de dix minutes ;
- taille maximale de requête ;
- absence de cache HTTP ;
- aucune clé OpenAI dans le code ou les réponses du serveur.
