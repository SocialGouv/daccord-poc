package fr.gouv.travail.anonymisation.core.text;

public final class WordBoundaries {

  private WordBoundaries() {}

  /** Vrai si {@code [start, end[} n'est collé à aucune lettre ni chiffre de part et d'autre. */
  public static boolean isWordBoundary(String text, int start, int end) {
    boolean before = start == 0 || !Character.isLetterOrDigit(text.charAt(start - 1));
    boolean after = end == text.length() || !Character.isLetterOrDigit(text.charAt(end));
    return before && after;
  }
}
