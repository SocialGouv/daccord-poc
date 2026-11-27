package fr.gouv.travail.anonymisation.core.model;

import java.util.Locale;
import java.util.Optional;

/** Nature d'une donnée à anonymiser. */
public enum EntityType {
  PERSONNE,
  LIEU,
  ORGANISATION,
  /**
   * Entités diverses du modèle NER (événements, œuvres, nationalités…) : bruyant, désactivé par
   * défaut.
   */
  DIVERS,
  EMAIL,
  TELEPHONE,
  IBAN,
  /** Numéro de sécurité sociale. */
  NIR,
  SIRET;

  /**
   * Convertit un label de modèle NER (« PER », « I-LOC », « B-ORG »…). « O » et les inconnus
   * donnent vide.
   */
  public static Optional<EntityType> fromNerLabel(String label) {
    String bare = label.substring(label.indexOf('-') + 1).toUpperCase(Locale.ROOT);
    return switch (bare) {
      case "PER", "PERSON" -> Optional.of(PERSONNE);
      case "LOC" -> Optional.of(LIEU);
      case "ORG" -> Optional.of(ORGANISATION);
      case "MISC" -> Optional.of(DIVERS);
      default -> Optional.empty();
    };
  }
}
