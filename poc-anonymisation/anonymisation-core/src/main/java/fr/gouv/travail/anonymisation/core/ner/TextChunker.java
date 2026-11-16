package fr.gouv.travail.anonymisation.core.ner;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;

/**
 * Découpe un long texte en morceaux que le modèle peut absorber (512 tokens pour CamemBERT), en
 * coupant là où un nom propre a le moins de chances d'être scindé en deux. Par ordre de préférence
 * : une ligne blanche, un saut de ligne qui suit une fin de phrase, une fin de phrase, n'importe
 * quel saut de ligne, un espace.
 *
 * <p>Un saut de ligne n'est pas une bonne coupure en soi : dans un PDF ou un texte à largeur fixe,
 * chaque ligne se termine ainsi, en plein milieu d'une phrase, parfois entre le prénom et le nom.
 */
final class TextChunker {

  record Chunk(int offset, String text) {}

  private TextChunker() {}

  static List<Chunk> chunk(String text, int maxChars) {
    List<Chunk> chunks = new ArrayList<>();
    int position = 0;
    while (position < text.length()) {
      int end = Math.min(text.length(), position + maxChars);
      if (end < text.length()) {
        end = findCut(text, position, end);
      }
      chunks.add(new Chunk(position, text.substring(position, end)));
      position = end;
    }
    return chunks;
  }

  /**
   * Renvoie l'index de coupe (exclu) dans {@code ]from, to]}, ou {@code to} si aucune coupure
   * propre.
   */
  private static int findCut(String text, int from, int to) {
    int half = from + (to - from) / 2;
    int cut = lastCut(text, to, half, c -> isBlankLine(text, from, c));
    cut = cut >= 0 ? cut : lastCut(text, to, half, c -> isNewlineAfterSentence(text, from, c));
    cut = cut >= 0 ? cut : lastCut(text, to, half, c -> isSentenceEnd(text, c));
    cut = cut >= 0 ? cut : lastCut(text, to, half, c -> text.charAt(c - 1) == '\n');
    cut = cut >= 0 ? cut : lastCut(text, to, from, c -> Character.isWhitespace(text.charAt(c - 1)));
    return cut >= 0 ? cut : to;
  }

  /** Dernière coupure {@code c} de {@code ]lowerBound, to]} qui satisfait la condition, ou -1. */
  private static int lastCut(String text, int to, int lowerBound, IntPredicate condition) {
    for (int cut = to; cut > lowerBound; cut--) {
      if (condition.test(cut)) {
        return cut;
      }
    }
    return -1;
  }

  private static boolean isBlankLine(String text, int from, int cut) {
    return cut - 2 >= from && text.charAt(cut - 1) == '\n' && text.charAt(cut - 2) == '\n';
  }

  private static boolean isNewlineAfterSentence(String text, int from, int cut) {
    if (text.charAt(cut - 1) != '\n') {
      return false;
    }
    int before = cut - 2;
    while (before >= from && (text.charAt(before) == ' ' || text.charAt(before) == '\r')) {
      before--;
    }
    return before >= from && ".!?;:".indexOf(text.charAt(before)) >= 0;
  }

  private static boolean isSentenceEnd(String text, int cut) {
    return Character.isWhitespace(text.charAt(cut - 1))
        && cut >= 2
        && ".!?;".indexOf(text.charAt(cut - 2)) >= 0;
  }
}
