# POC anonymisation

Anonymise du texte (chaîne de caractères, fichier texte, document Word) et **vérifie** qu'un texte est bien anonymisé. Java 21, Maven, Spring Boot, Apache Tika.

La détection combine :

- un modèle **NER** (CamemBERT, français) pour les noms de personnes, lieux et organisations ;
- des **motifs structurés** validés par clé de contrôle pour les e-mails, téléphones, IBAN, NIR (sécurité sociale) et SIRET, que le NER ne voit pas.

## Les six services

Les services « texte » et « chaîne » rendent le texte anonymisé en JSON. Le service **Word** d'anonymisation rend le **document Word anonymisé**, au même format que celui reçu.

| Entrée | Anonymiser | Vérifier |
|---|---|---|
| Fichier texte UTF-8 | `POST /api/anonymisations` | `POST /api/verifications` |
| Chaîne de caractères (JSON) | `POST /api/anonymisations/chaine` | `POST /api/verifications/chaine` |
| Document Word `.docx` ou `.doc` | `POST /api/anonymisations/word` | `POST /api/verifications/word` |

- **Fichier texte** et **Word** : requête `multipart/form-data`, partie `file` ; pour la vérification, une partie `original` facultative (un document Word pour le service Word).
- **Chaîne** : corps JSON `{"text": "…"}` ; pour la vérification `{"text": "…", "original": "…"}` (`original` facultatif).
- **Word** : le type est reconnu **sur le contenu** par Tika : ni l'extension ni le `Content-Type` annoncé ne comptent. L'anonymisation réécrit le document (voir plus bas). La vérification en lit le texte avec **Apache Tika 3.2.3**, comme Daccord (corps, tableaux, en-têtes, pieds de page, notes, commentaires, documents incorporés) ; les positions des restes sont celles du texte extrait, pas celles du fichier.

| Code | Cas |
|---|---|
| 400 | fichier vide ou illisible, texte non UTF-8, JSON invalide, champ `text` absent |
| 415 | fichier Word attendu : le type détecté est autre (le message donne le type trouvé) |
| 422 | document Word corrompu, protégé par mot de passe, sans texte extractible (images seules), de plus de 20 millions de caractères, ou **anonymisation incomplète** (voir plus bas) |

Un texte trop long est refusé au lieu d'être tronqué : le tronquer en silence ferait déclarer « conforme » un document dont seul le début a été lu (`Tika.parseToString` s'arrête à 100 000 caractères par défaut).

Les erreurs sont au format RFC 9457 (`detail` en français). Le contenu des fichiers et des chaînes n'est jamais journalisé.

### Anonymisation d'un document Word

Le corps de la réponse est le document : le compte rendu passe par les en-têtes.

- `X-Anonymisation-Replacements` : nombre de remplacements par type, par exemple `PERSONNE=8,EMAIL=2`. Absent s'il n'y en a aucun.
- `X-Anonymisation-Warnings` : `NER_INACTIF` si seuls les identifiants structurés ont été traités, pas les noms.
- `Content-Disposition` : toujours `anonymise.docx` ou `anonymise.doc`. Le nom du fichier reçu peut contenir un nom de personne, on ne le renvoie pas.

**`.docx`** : réécrit **en XML**, sans bibliothèque de mise en page : ce qui n'est pas du texte est recopié octet pour octet, et seules les parties modifiées sont réécrites. Sont traités : corps, tableaux, zones de texte, en-têtes et pieds de page, notes, commentaires, texte des formes et graphiques, codes de champ (un lien `mailto:`), textes alternatifs des images, données liées aux contrôles de contenu, propriétés du document, liens externes (remplacés par `about:blank` s'ils contiennent une donnée). Le créateur, le dernier auteur, la société, le responsable et les auteurs des commentaires et révisions sont effacés ; la vignette et les signatures numériques (invalidées par toute modification) sont retirées. Un nom coupé en plusieurs « runs » (« Jean » en gras puis « Dupont ») est retrouvé, et le marqueur `[TYPE_n]` prend la mise en forme du premier run concerné.

**`.doc`** (Word 97-2003) : réécrit avec HWPF, **en remplaçant chaque caractère d'une valeur par une astérisque : la longueur du texte ne change jamais**. C'est volontaire. Le format binaire garde, en plus du texte, des tables de positions (limites entre en-tête et pied de page, notes, champs, signets) que HWPF ne met pas à jour quand on insère un texte de longueur différente : essayé avec des marqueurs `[TYPE_n]`, le `]` final d'un marqueur d'en-tête glissait dans le pied de page. Les `.doc` n'ont donc pas de marqueurs numérotés : on ne distingue pas deux personnes entre elles. Les zones de texte des en-têtes et pieds de page, que HWPF n'atteint pas, sont analysées : si l'une contient une donnée, le document est refusé (422) et il faut le convertir en `.docx`. Ni les objets incorporés ni le code VBA ne sont analysés.

**Contrôle final.** Le document produit est relu avec Tika, comme Daccord le lirait. S'il contient encore une valeur remplacée ailleurs (par exemple dans un document incorporé, que l'on ne sait pas réécrire), le service répond **422** au lieu de livrer un fichier présenté comme anonymisé.

Le rapport de vérification :

```json
{
  "verdict": "NON_CONFORME",
  "findings": [{"kind": "RESIDUAL_ENTITY", "type": "EMAIL", "start": 212, "end": 234, "text": "…", "score": 1.0}],
  "engines": ["regex-email", "…", "onnx-ner"],
  "warnings": []
}
```

- `CONFORME` : rien trouvé. `NON_CONFORME` : au moins un reste. `start` et `end` sont des positions de caractères dans le texte reçu (ou extrait, pour Word).
- Si `original` est fourni, on contrôle aussi qu'**aucune valeur de l'original ne subsiste** : `kind: ORIGINAL_VALUE_LEAK`. C'est le contrôle le plus fiable.

## Comment le NER est branché sur Java

Le NER est un modèle d'apprentissage ; on le fait tourner **dans la JVM**, sans service Python :

```
texte ──► tokenizer HuggingFace (DJL, natif livré dans le jar)
      ──► ONNX Runtime (modèle CamemBERT exporté en ONNX, ~440 Mo)
      ──► label par token (PER/LOC/ORG/MISC) ──► entités avec offsets dans le texte d'origine
```

Code : [`OnnxNerRecognizer`](anonymisation-core/src/main/java/fr/gouv/travail/anonymisation/core/ner/OnnxNerRecognizer.java).
Tout modèle HuggingFace de classification de tokens (`tokenizer.json`, `config.json`, `.onnx`) peut le remplacer.

Un long texte est découpé en morceaux de 512 tokens au plus. On coupe de préférence sur une ligne blanche, puis un saut de ligne qui suit une fin de phrase, puis une fin de phrase : un saut de ligne ordinaire n'est pas une bonne coupure, car dans un texte extrait d'un PDF chaque ligne se termine ainsi, parfois entre le prénom et le nom (défaut repéré en essayant un texte de 25 pages : « Fatima » restait en clair).

### Options examinées

| Option | Verdict |
|---|---|
| **DJL tokenizers + ONNX Runtime** (retenu) | En processus, Apache 2.0, aucune dépendance Python, tout modèle HF exportable. Vérifié : les natifs du tokenizer sont dans le jar, pas de téléchargement à l'exécution. |
| Presidio (Python) en service REST | Le plus riche en détecteurs (spaCy `fr_core_news_lg`), mais un second runtime à exploiter ; aucun client Java trouvé (client à générer depuis l'OpenAPI). Bonne alternative si l'exploitation Python ne gêne pas. |
| Stanford CoreNLP | Bon NER français, mais **GPL v3** (licence commerciale possible auprès de Stanford). |
| Apache OpenNLP | Apache 2.0 et module ONNX (`opennlp-dl`), mais pas de modèle NER français officiel ; son tokenizer WordPiece ne convient pas, à ma connaissance, à CamemBERT (SentencePiece) — non vérifié. |
| GLiNER multi-PII (`urchade/gliner_multi_pii-v1`, Apache 2.0) | Intéressant (PII multilingue dont le français) mais pas d'ONNX dans le dépôt du modèle, et je n'ai pas pu vérifier un runtime Java. À réévaluer. |
| Spring AI | Pas de NER officiel. Des starters tiers existent, orientés protection des appels LLM. |

**Spring** ne fournit pas le NER : il sert ici d'ossature pour l'API (REST, configuration, Swagger UI). Le moteur est dans le module `core`, **sans dépendance Spring**, donc réutilisable tel quel (par exemple dans Daccord).

## Architecture

```
anonymisation-core   bibliothèque Java pure
  detection/         EntityRecognizer, RegexRecognizer, FrenchRecognizers, EntityDetector
  ner/               OnnxNerRecognizer, NerDecoder, TextChunker
  anonymization/     Anonymizer         → remplace par [PERSONNE_1], [EMAIL_1]…
                     KnownValues        → retrouve ailleurs une valeur déjà repérée
  verification/      AnonymizationVerifier → rapport CONFORME / NON_CONFORME
anonymisation-documents   bibliothèque Java pure : réécriture des documents Word
  word/              DocxAnonymizer (XML), DocAnonymizer (HWPF), OoxmlPackage
anonymisation-api    Spring Boot : REST + configuration
  WordTextExtractor  Apache Tika : format et texte d'un .docx ou d'un .doc
```

`EntityDetector` est partagé par l'anonymiseur et le vérificateur : ce qu'on s'engage à anonymiser est exactement ce qu'on contrôle. L'ordre des moteurs fixe la priorité en cas de chevauchement (motifs validés avant NER).

### Ce que fait l'anonymiseur

- Chaque entité devient un marqueur `[TYPE_n]` ; une même valeur reçoit toujours le même numéro (« Jean-Pierre », « jean pierre » et « JEAN‑PIERRE » coïncident).
- Une valeur repérée quelque part est aussi **remplacée partout ailleurs** dans le texte, sans tenir compte de la casse, des accents ni des retours à la ligne : un nom que le NER a vu dans neuf phrases sur dix doit aussi disparaître de la dixième. Pour les noms, lieux et organisations, une occurrence en minuscules est écartée : un faux positif sur « Pour » en début de phrase n'efface pas tous les « pour ». Les valeurs de moins de 3 caractères ne sont pas propagées.
- Les identifiants structurés tolèrent une coupure en fin de ligne (« 06 12 34 56 ⏎78 »), les adresses e-mail les lettres accentuées.

### Deux contrôles de vérification

1. **Sans original** : on relance la détection sur le texte anonymisé ; tout ce qui est encore repéré est signalé. Le texte est présenté au NER **marqueurs compris** : les remplacer par des espaces lui fait perdre le contexte et il prend pour une personne un mot comme « Délégué ». Le modèle reconnaît les marqueurs eux-mêmes comme des noms ; on écarte ce qu'il y a de commun avec un marqueur. Une valeur oubliée juste à côté d'un marqueur reste signalée.
2. **Avec l'original** : on détecte les entités de l'original, puis on cherche chacune dans le texte anonymisé (sans casse ni accents, mots entiers). C'est le contrôle le plus fiable : il ne dépend pas du rappel du modèle sur le texte anonymisé.

Si le NER est désactivé, le rapport contient un avertissement explicite : un « conforme » sans NER ne couvre ni les noms, ni les lieux, ni les organisations.

## Démarrage

Prérequis : **JDK 21** (`.tool-versions` identique à Daccord). Sur cette machine le JDK par défaut est le 17 et le paquet `openjdk-21` n'a pas `javac` : utiliser par exemple

```bash
export JAVA_HOME=~/.jdks/jbr-21.0.11
```

Télécharger le modèle complet (~440 Mo, licence MIT, depuis `huggingface.co/Xenova/camembert-ner` ; l'application consomme ensuite environ 1 Go de RAM) :

```bash
./scripts/download-model.sh
```

La version quantifiée int8 (111 Mo, environ 1,5 fois plus rapide) est disponible avec `ONNX_FILE=onnx/model_quantized.onnx ./scripts/download-model.sh` puis `--anonymisation.ner.model-file=onnx/model_quantized.onnx`, mais **elle rate des prénoms courants et des noms complets** (voir « Limites »).

Construire et lancer (depuis la racine) :

```bash
mvn verify
java -jar anonymisation-api/target/api-0.1.0-SNAPSHOT.jar --anonymisation.ner.model-dir=models/camembert-ner
```

Sans modèle, l'application **refuse de démarrer** (message indiquant le script). Pour tester sans NER : `--anonymisation.ner.enabled=false`. Si le port 8080 est pris : `--server.port=8099`.

Swagger UI : <http://localhost:8080/swagger-ui.html>

```bash
# Fichier texte : anonymiser, puis vérifier (« original » est facultatif)
curl -F "file=@dossier.txt" http://localhost:8080/api/anonymisations
curl -F "file=@dossier.anonymise.txt" -F "original=@dossier.txt" http://localhost:8080/api/verifications

# Chaîne de caractères
curl -H "Content-Type: application/json" -d '{"text": "Jean Dupont habite à Lyon."}' http://localhost:8080/api/anonymisations/chaine
curl -H "Content-Type: application/json" -d '{"text": "[PERSONNE_1] habite à [LIEU_1].", "original": "Jean Dupont habite à Lyon."}' http://localhost:8080/api/verifications/chaine

# Document Word (.docx ou .doc) : anonymiser rend un document, vérifier rend un rapport
curl -F "file=@accord.docx" -o accord.anonymise.docx -D - http://localhost:8080/api/anonymisations/word
curl -F "file=@accord.anonymise.docx" -F "original=@accord.docx" http://localhost:8080/api/verifications/word
```

Page de test : `index.html` (à la racine) envoie le texte saisi aux services « chaîne » de vérification et d'anonymisation, et affiche les deux résultats côte à côte. Le build la copie dans le jar : elle est servie par l'application sur <http://localhost:8080/>, sans réglage. Pour l'ouvrir directement depuis le fichier (ou depuis un autre serveur), l'API n'autorise par défaut que son propre domaine : la démarrer avec `--anonymisation.cors.allowed-origins=*`, et ajouter `?api=http://localhost:8080` à l'adresse si la page n'est pas ouverte en `file://`.

Les tests sur le vrai modèle (`OnnxNerRecognizerModelTest`) sont ignorés tant que `models/camembert-ner` n'existe pas.

Les documents d'essai `accord.docx` et `accord.doc` (dans `anonymisation-documents/src/test/resources/fixtures` et `anonymisation-api/src/test/resources/fixtures`) sont fictifs, générés avec LibreOffice.

## Configuration (`application.yml`)

| Clé | Défaut | Rôle |
|---|---|---|
| `anonymisation.types` | tous sauf `DIVERS` | types anonymisés **et** contrôlés |
| `anonymisation.min-score` | `0.3` | score minimal pour retenir une entité (voir « Limites ») |
| `anonymisation.ner.enabled` | `true` | `false` : motifs structurés seulement |
| `anonymisation.ner.model-dir` | `../models/camembert-ner` (`ANONYMISATION_MODEL_DIR`) | répertoire du modèle |
| `anonymisation.ner.model-file` | `onnx/model.onnx` | fichier ONNX (`onnx/model_quantized.onnx` pour la version int8) |
| `anonymisation.cors.allowed-origins` | absent (pas de CORS) | origines autorisées à appeler `/api/**` depuis un navigateur, par exemple `*` pour ouvrir `index.html` hors de l'application |

## Performances

Mesurées sur un texte fictif de 111 000 caractères (retours à la ligne tous les 90 caractères, comme un texte extrait d'un PDF) : 520 noms, 520 lieux, 260 organisations, 260 e-mails, 260 téléphones. CPU seul.

| | Durée |
|---|---|
| Anonymisation | 22 s |
| Vérification sans original | 23 s |
| Vérification avec original | 45 s |

Le NER représente presque tout (environ 5 000 à 6 700 caractères par seconde) ; la vérification avec original le fait tourner deux fois. Les requêtes sont synchrones.

Le même contenu en document Word de 25 pages : anonymisation 27 s (`.docx` et `.doc`), vérification avec original 53 s (`.docx`) et 65 s (`.doc`). Mesuré alors que la machine était très chargée (charge de 16 environ, d'autres applications Java y tournent) ; un essai précédent de la même réécriture du `.docx` avait pris 18 s, l'écart vient sans doute de cette charge. Le jar pèse environ 140 Mo (Tika, POI, ONNX Runtime) et le serveur occupait environ 1 Go de mémoire après ces essais.

## Limites connues

- **Qualité du NER : observée sur des phrases et des textes fictifs écrits à la main, pas mesurée sur un corpus réel.** Le modèle (CamemBERT-NER, entraîné sur WikiNER, c'est-à-dire Wikipédia) est livré en deux variantes, comparées sur 19 phrases :

  | | complet (défaut) | quantifié int8 |
  |---|---|---|
  | Fichier / pic mémoire du moteur seul | 440 Mo / ~0,8 Go | 111 Mo / ~0,45 Go |
  | Débit (CPU, 44 000 car.) | ~6 700 car./s | ~10 000 car./s |
  | Prénoms courants en tête de texte (Jean, Marie, Pierre) | trouvés (probabilité 0,91 à 0,98) | **ratés** dans 3 phrases sur 11 : « Jean [PERSONNE_1] habite à [LIEU_1]. » |
  | Noms complets sur 8 phrases fraîches | 0 fuite | **2 fuites** (« Nicolas Martin », « Camille Roux ») |

  Les probabilités du modèle quantifié sont basses sur ces prénoms (PER entre 0,26 et 0,39) : le seuil `min-score` n'y change rien. Un test (`OnnxNerRecognizerModelTest`) échoue si l'on repasse au quantifié.

  Défauts communs aux deux variantes :
  - les sigles d'administration (« DREETS ») ne sont pas reconnus ;
  - pour une adresse, la voie est masquée (« rue de la Paix ») mais le numéro de rue reste ;
  - dans un nom d'organisation long, les types sont parfois incohérents (« Caisse d'Allocations Familiales de Lyon » donne `[ORGANISATION_1] [LIEU_1] [ORGANISATION_2]`, « de » étant masqué comme un lieu) : rien ne fuit, mais le texte est moins lisible ;
  - une même organisation reçoit parfois deux numéros selon que le NER y inclut ou non « Société ».

  Avec le modèle complet, `min-score` 0,3 et 0,5 donnent les mêmes résultats ; 0,3 est gardé par prudence. **La mesure de la qualité se fait avec l'API actuelle de Daccord**, sur de vrais textes (voir « Pistes »).
- **Pas de PDF** ; pour Word, seulement `.docx` et `.doc` (pas `.docm`, `.rtf`, `.odt`).
- **Word, vérification** : les **propriétés** du document (auteur, titre) ne sont pas lues : un document dont les propriétés disent « Auteur : Jean Dupont » peut être déclaré conforme, alors que l'anonymisation les efface. Pas d'OCR : le texte d'une image n'est vu ni par l'anonymisation ni par la vérification, et un document sans aucun texte est refusé (422) plutôt que déclaré conforme. Le texte rendu par Tika contient des marques comme `[footnoteRef:2]` pour les notes de bas de page.
- **Word, anonymisation** : voir les limites du `.doc` plus haut (astérisques, zones de texte d'en-tête). Un document incorporé dans un `.docx` n'est pas réécrit : 422 si une valeur y subsiste.
- Tika parse ici des fichiers non fiables dans le processus de l'API, sans isolation (délai, mémoire) ; seul le volume de texte est borné.
- **Pseudonymisation, pas anonymisation au sens du RGPD** : fonctions, dates, situations rares et autres quasi-identifiants ne sont pas traités. Une même personne désignée différemment (« M. Dupont » / « Jean Dupont ») reçoit deux numéros.
- Un `CONFORME` signifie « rien trouvé par ces moteurs », pas « zéro donnée personnelle ».
- Le contenu des fichiers n'est jamais journalisé ; `DetectedEntity` et `Finding` omettent la valeur dans leur `toString()`.

## Pistes

- Comparer avec l'API actuelle de Daccord sur un lot de vrais textes : anonymiser, passer les sorties dans l'API, et passer les originaux dans `POST /api/verifications` pour confronter les verdicts.
- Intégration dans Daccord : l'extraction Tika existe déjà (`ServiceDocument.getTextFromDocument`) ; il suffit de remplacer l'appel distant `verifierAnonymisation(text)` par `AnonymizationVerifier.verify(text)`, sans réseau ni reprise sur erreur. Il faudrait ajouter à chaque reste un extrait de contexte, comme Daccord en reçoit (`context`).
- Dictionnaires (prénoms INSEE, noms de famille, communes) en moteurs supplémentaires, pour renforcer le rappel du NER ; reconnaissance des adresses postales et des sigles d'administration.
- Comparer avec un modèle plus gros ou GLiNER multi-PII.
- Anonymiser des PDF eux-mêmes : une version existait (pages rendues en image avec rectangles noirs et couche de texte invisible) ; l'instantané est dans `../poc-anonymisation-avec-documents.tar.gz`.
- Contrôler aussi les propriétés des documents Word à la vérification.

## Ce qui est repris de Daccord, et ce qui diffère

Repris : `spring-boot-starter-parent`, structure multi-modules, JaCoCo, Spotless (google-java-format), `.tool-versions`, groupId `fr.gouv.travail`, Apache Tika 3.2.3 pour extraire le texte des documents.

Différences : Spring Boot **3.5.16** (Daccord : 3.4.10), `java.version` **21** (Daccord : 17), pas de Lombok ni MapStruct (records Java), pas de module BOM séparé (`dependencyManagement` dans le parent), pas de Sonar/dependency-check/Makefile. Le contrôle ne passe pas par le service externe `/xp/analyse` mais par le moteur embarqué ; Daccord déclare un document non anonymisé si la liste `persons` de la réponse n'est pas vide, ici tous les types configurés comptent (lieux et organisations compris, ce qui est plus strict : `anonymisation.types` permet de s'aligner).
