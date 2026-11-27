# Calendrier des expérimentations

| Code | Semaine | Date | Expérimentation | Sujet | Statut |
|---|---|---|---|---|---|
| EXP-01 | S0 | 2026-11-18 | [ner-and-regex](anonymisation/2026-11-18-ner-and-regex/) | Anonymisation avec le modèle camembert-ner et quelques regex (email, IBAN, NIR, SIRET, téléphone) | Terminée |
| EXP-01.A | S1 | 2026-11-26 | [name-dictionary](anonymisation/2026-11-26-name-dictionary/) | Itération de EXP-01 : dictionnaire INSEE des prénoms et noms en complément du NER ; texte et Word seulement, sans PDF | En cours |

## Codes

| Cas | Code | Exemple |
|---|---|---|
| Nouvelle expérimentation | `EXP-NN` | `EXP-01` |
| Itération d'un code qui finit par un chiffre | ajout d'une lettre | `EXP-01.A` : 1ʳᵉ itération de `EXP-01` ; `EXP-01.B` : 2ᵉ |
| Itération d'un code qui finit par une lettre | ajout d'un chiffre | `EXP-01.A.1` : 1ʳᵉ itération de `EXP-01.A` ; `EXP-01.A.1.A` : 1ʳᵉ itération de `EXP-01.A.1` |

Chaque POC a son propre calendrier et la liste de ses technologies, à la racine de son dossier :

- anonymisation : [CALENDRIER.md](anonymisation/CALENDRIER.md), [TECHNOS.md](anonymisation/TECHNOS.md)
- classification : [CALENDRIER.md](classification/CALENDRIER.md), [TECHNOS.md](classification/TECHNOS.md)

Le détail (résultats, décisions, limites) est dans le README de chaque expérimentation.
