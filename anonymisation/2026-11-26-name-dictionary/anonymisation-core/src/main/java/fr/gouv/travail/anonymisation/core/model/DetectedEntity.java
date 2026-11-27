package fr.gouv.travail.anonymisation.core.model;

/**
 * Entité repérée dans un texte. Les offsets sont des positions de caractères (UTF-16) dans le texte
 * analysé, {@code end} exclu.
 */
public record DetectedEntity(
    EntityType type, int start, int end, String text, double score, String source) {

  public DetectedEntity {
    if (start < 0 || end <= start) {
      throw new IllegalArgumentException("Intervalle invalide : [" + start + ", " + end + "[");
    }
  }

  public boolean overlaps(DetectedEntity other) {
    return start < other.end && other.start < end;
  }

  public int length() {
    return end - start;
  }

  public DetectedEntity shift(int offset) {
    return new DetectedEntity(type, start + offset, end + offset, text, score, source);
  }

  /**
   * Ne reproduit volontairement pas {@code text} : une donnée personnelle n'a rien à faire dans un
   * log.
   */
  @Override
  public String toString() {
    return "DetectedEntity[type=%s, start=%d, end=%d, score=%.2f, source=%s]"
        .formatted(type, start, end, score, source);
  }
}
