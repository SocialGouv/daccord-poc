package fr.gouv.travail.anonymisation.core.verification;

import fr.gouv.travail.anonymisation.core.model.EntityType;
import java.util.List;

/**
 * @param verdict {@link Verdict#CONFORME} si rien n'a été trouvé
 * @param findings données personnelles restantes, par position croissante
 * @param engines moteurs de détection utilisés
 * @param warnings limites de la vérification effectuée (par exemple NER inactif)
 */
public record VerificationReport(
    Verdict verdict, List<Finding> findings, List<String> engines, List<String> warnings) {

  public enum Verdict {
    CONFORME,
    NON_CONFORME
  }

  public enum Kind {
    /** Entité détectée dans le texte anonymisé. */
    RESIDUAL_ENTITY,
    /**
     * Valeur détectée dans le fichier d'origine et retrouvée telle quelle dans le texte anonymisé.
     */
    ORIGINAL_VALUE_LEAK
  }

  /** Positions en caractères dans le texte anonymisé ; {@code text} est la valeur restante. */
  public record Finding(Kind kind, EntityType type, int start, int end, String text, double score) {

    boolean overlaps(int otherStart, int otherEnd) {
      return start < otherEnd && otherStart < end;
    }

    /** Ne reproduit pas {@code text} : une donnée personnelle n'a rien à faire dans un log. */
    @Override
    public String toString() {
      return "Finding[kind=%s, type=%s, start=%d, end=%d, score=%.2f]"
          .formatted(kind, type, start, end, score);
    }
  }
}
