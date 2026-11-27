# Technologies du POC anonymisation

Une ligne par expérimentation (voir [CALENDRIER.md](CALENDRIER.md)), une colonne par technologie d'anonymisation : ✗ si l'expérimentation l'utilise.

| Code | CamemBERT-NER | ONNX Runtime | DJL tokenizers | Regex + clés de contrôle | Apache Tika | Apache POI HWPF | Réécriture XML OOXML | Dictionnaire INSEE | Lexique 3 | Avantages | Inconvénients | Commentaire |
|---|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|---|---|---|
| [EXP-01](2026-11-18-ner-and-regex/) | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | | | • NER exécuté dans la JVM, sans service Python<br>• Prénoms courants et noms complets trouvés (0 fuite sur 8 phrases)<br>• Identifiants structurés validés par clé (modulo 97, Luhn)<br>• Extraction identique à Daccord (Tika 3.2.3)<br>• `.docx` réécrit sans toucher à la mise en page | • Modèle de 440 Mo, ~1 Go de RAM, ~6 700 car./s sur CPU<br>• Sigles d'administration (« DREETS ») et numéros de rue non reconnus<br>• `.doc` masqué par astérisques, sans marqueurs numérotés<br>• Pas de PDF<br>• Tika sans isolation | Qualité observée sur des textes fictifs, pas mesurée sur un corpus réel |
| [EXP-01.A](2026-11-26-name-dictionary/) | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | • Prénoms et noms connus trouvés même quand le NER les rate<br>• Décision explicable : forme du dictionnaire, contexte (civilité, prénom ou nom voisin)<br>• Sources ouvertes et datées (INSEE, Licence Ouverte), dictionnaire reproductible par script | • Noms rares (portés moins de 10 fois) absents<br>• Mots courants (« Petit », « Pierre ») masqués seulement avec un contexte<br>• Une ville qui est aussi un nom de famille (« Lyon ») devient une personne si le NER est inactif<br>• Construction : téléchargement d'environ 3,5 Go | Même NER et mêmes motifs que EXP-01 ; le dictionnaire ne remplace pas le NER, il le complète |

## Technologies

- **CamemBERT-NER** : modèle NER français `Xenova/camembert-ner` (export ONNX, licence MIT).
- **ONNX Runtime**, **DJL tokenizers** : exécution du modèle et de son tokenizer dans la JVM.
- **Regex + clés de contrôle** : email, IBAN, NIR, SIRET, téléphone.
- **Apache Tika** : détection du format et extraction du texte des documents Word.
- **Apache POI HWPF** : réécriture des `.doc`. **Réécriture XML OOXML** : réécriture des `.docx` avec le XML du JDK.
- **Dictionnaire INSEE** : prénoms (fichier des prénoms) et noms de famille (fichier des personnes décédées), Licence Ouverte 2.0 ; construit par `scripts/download-dictionary.py` (Python, au build seulement).
- **Lexique 3** : lexique du français courant (CC BY-SA 4.0), pour repérer les noms qui sont aussi des mots courants.
