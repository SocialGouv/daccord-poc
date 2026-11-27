#!/usr/bin/env python3
"""Construit le dictionnaire des prénoms et noms de famille (sources INSEE) dans dictionnaires/.

Sources (Licence Ouverte 2.0, sauf Lexique) :
  - prénoms : fichier des prénoms de l'INSEE (naissances depuis 1900, édition 2022) ;
  - prénoms et noms : fichier des personnes décédées de l'INSEE depuis 1970, agrégé par
    data.gouv.fr (un seul CSV d'environ 3,5 Go, lu au fil du téléchargement, jamais stocké) ;
  - Lexique 3.83 (CC BY-SA 4.0) : mots du français courant, pour marquer les formes ambiguës
    (« Petit », « Blanc », « Pierre »).

Seules les formes sont gardées, jamais le lien entre un nom, un prénom et une personne. Les
décès ayant fait l'objet d'une opposition sont ignorés.

Résultat : prenoms.tsv et noms.tsv (colonnes forme, frequence, ambigu) et SOURCES.md.
  - frequence : naissances (fichier des prénoms) ou décès (fichier des décès) ;
  - une forme portée moins de MIN_OCCURRENCES fois est écartée ;
  - ambigu = oui si la forme, sans casse ni accents, est un mot du français courant (fréquence
    Lexique d'au moins AMBIGUITY_MIN_FREQ occurrences par million de mots, films + livres). Le
    seuil écarte les mots rares qui ne sont guère que des prénoms dans le corpus de Lexique (sous-
    titres de films) : Sophie (1,2), Robert (1,7), Claude (2,7), Olivier (5,0 : limite).

Variables : DICTIONARY_DIR (défaut dictionnaires), MIN_OCCURRENCES (défaut 10),
AMBIGUITY_MIN_FREQ (défaut 5.0), PRENOMS_URL, DECES_URL, LEXIQUE_URL (miroir interne éventuel).
Option --force : reconstruit même si les fichiers existent.

Durée : quelques minutes, dominée par le téléchargement du fichier des décès.
"""

import collections
import csv
import datetime
import io
import os
import sys
import unicodedata
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / os.environ.get("DICTIONARY_DIR", "dictionnaires")
MIN_OCCURRENCES = int(os.environ.get("MIN_OCCURRENCES", "10"))
AMBIGUITY_MIN_FREQ = float(os.environ.get("AMBIGUITY_MIN_FREQ", "5.0"))
PRENOMS_URL = os.environ.get(
    "PRENOMS_URL", "https://www.insee.fr/fr/statistiques/fichier/7633685/nat2022_csv.zip"
)
DECES_URL = os.environ.get(
    "DECES_URL", "https://data-pipeline-open.s3.sbg.io.cloud.ovh.net/deces/deces.csv"
)
LEXIQUE_URL = os.environ.get(
    "LEXIQUE_URL", "http://www.lexique.org/databases/Lexique383/Lexique383.tsv"
)


def fold(value):
    """Minuscules sans accents, comme TextNormalizer côté Java."""
    decomposed = unicodedata.normalize("NFD", value.lower())
    return "".join(c for c in decomposed if unicodedata.category(c) != "Mn")


def open_text(url):
    print(f"téléchargement : {url}", file=sys.stderr)
    return io.TextIOWrapper(urllib.request.urlopen(url, timeout=120), encoding="utf-8", newline="")


def lexique_frequencies():
    frequencies = collections.defaultdict(float)
    with open_text(LEXIQUE_URL) as stream:
        for row in csv.DictReader(stream, delimiter="\t"):
            frequency = float(row["freqfilms2"] or 0) + float(row["freqlivres"] or 0)
            key = fold(row["ortho"])
            frequencies[key] = max(frequencies[key], frequency)
    return frequencies


def birth_first_names():
    print(f"téléchargement : {PRENOMS_URL}", file=sys.stderr)
    with urllib.request.urlopen(PRENOMS_URL, timeout=120) as response:
        archive = zipfile.ZipFile(io.BytesIO(response.read()))
    counts = collections.Counter()
    with archive.open(archive.namelist()[0]) as raw:
        reader = csv.reader(io.TextIOWrapper(raw, encoding="utf-8", newline=""), delimiter=";")
        next(reader)
        for _sexe, prenom, _annee, nombre in reader:
            if not prenom.startswith("_"):  # _PRENOMS_RARES : regroupement, pas un prénom
                counts[prenom] += int(nombre)
    return counts


def deaths():
    surnames = collections.Counter()
    first_names = collections.Counter()
    csv.field_size_limit(10**7)
    with open_text(DECES_URL) as stream:
        reader = csv.DictReader(stream)
        for row in reader:
            if row["opposition"] == "True":
                continue
            surname = row["nom"].strip()
            if surname:
                surnames[surname] += 1
            for first_name in row["prenoms"].split(","):
                first_name = first_name.strip()
                if first_name:
                    first_names[first_name] += 1
    return surnames, first_names


def write(path, counts, frequencies):
    kept = ambiguous = 0
    part = path.with_suffix(".tsv.part")
    with open(part, "w", encoding="utf-8") as out:
        out.write("forme\tfrequence\tambigu\n")
        for form, count in counts.most_common():
            if count < MIN_OCCURRENCES:
                break
            is_ambiguous = frequencies.get(fold(form), 0) >= AMBIGUITY_MIN_FREQ
            out.write(f"{form}\t{count}\t{'oui' if is_ambiguous else 'non'}\n")
            kept += 1
            ambiguous += is_ambiguous
    part.replace(path)
    print(f"{path.name} : {kept} formes, dont {ambiguous} ambiguës", file=sys.stderr)
    return kept, ambiguous


def main():
    first_names_file = OUT / "prenoms.tsv"
    surnames_file = OUT / "noms.tsv"
    if "--force" not in sys.argv and first_names_file.is_file() and surnames_file.is_file():
        print(f"déjà présent : {OUT} (--force pour reconstruire)", file=sys.stderr)
        return
    OUT.mkdir(parents=True, exist_ok=True)

    frequencies = lexique_frequencies()
    births = birth_first_names()
    surnames, death_first_names = deaths()
    # Le fichier des prénoms ne publie pas les prénoms donnés moins de 20 fois : ceux du fichier
    # des décès complètent, avec leur nombre de décès.
    first_names = collections.Counter(death_first_names)
    for name, count in births.items():
        first_names[name] = count

    stats_first = write(first_names_file, first_names, frequencies)
    stats_last = write(surnames_file, surnames, frequencies)
    (OUT / "SOURCES.md").write_text(
        f"""# Dictionnaire des prénoms et noms

Construit le {datetime.date.today().isoformat()} par `scripts/download-dictionary.py`.

| Fichier | Formes | Dont ambiguës |
|---|---|---|
| prenoms.tsv | {stats_first[0]} | {stats_first[1]} |
| noms.tsv | {stats_last[0]} | {stats_last[1]} |

Seuils : formes portées au moins {MIN_OCCURRENCES} fois ; ambiguë si fréquence Lexique d'au moins
{AMBIGUITY_MIN_FREQ} par million de mots.

Sources :

- Fichier des prénoms, Insee, Licence Ouverte 2.0 : {PRENOMS_URL}
- Fichier des personnes décédées, Insee, agrégé par data.gouv.fr, Licence Ouverte 2.0 : {DECES_URL}
- Lexique 3.83, New et Pallier, CC BY-SA 4.0 : {LEXIQUE_URL}
""",
        encoding="utf-8",
    )
    print(f"Dictionnaire prêt dans {OUT}", file=sys.stderr)


if __name__ == "__main__":
    main()
