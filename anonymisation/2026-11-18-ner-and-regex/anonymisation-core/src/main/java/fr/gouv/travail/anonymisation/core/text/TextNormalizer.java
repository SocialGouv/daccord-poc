package fr.gouv.travail.anonymisation.core.text;

import java.text.Normalizer;

public final class TextNormalizer {

  private TextNormalizer() {}

  /**
   * Passe en minuscules et retire les accents, caractère par caractère : la longueur est conservée,
   * les positions du texte plié restent donc valables dans le texte d'origine.
   */
  public static String fold(String text) {
    StringBuilder folded = new StringBuilder(text.length());
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c < 128) {
        folded.append(Character.toLowerCase(c));
      } else {
        char base = Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFD).charAt(0);
        folded.append(Character.toLowerCase(base));
      }
    }
    return folded.toString();
  }

  /**
   * Clé de comparaison de deux valeurs : « Jean-Pierre », « jean pierre » et « JEAN‑PIERRE »
   * coïncident.
   */
  public static String key(String value) {
    return fold(value).replaceAll("[^\\p{L}\\p{N}]", "");
  }
}
