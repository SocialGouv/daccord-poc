package fr.gouv.travail.anonymisation.core.anonymization;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ce que l'anonymiseur met à la place d'une valeur : un marqueur {@code [TYPE_n]}, ou une suite
 * d'astérisques pour un .doc (qu'on ne peut réécrire qu'à longueur constante).
 */
public final class Placeholders {

  private static final Pattern PLACEHOLDER = Pattern.compile("\\[[A-Z_]+_\\d+]|\\*{2,}");

  private Placeholders() {}

  /** Positions {@code [début, fin[} des marqueurs, par ordre croissant. */
  public static List<int[]> spans(String text) {
    List<int[]> spans = new ArrayList<>();
    Matcher matcher = PLACEHOLDER.matcher(text);
    while (matcher.find()) {
      spans.add(new int[] {matcher.start(), matcher.end()});
    }
    return spans;
  }

  /**
   * Remplace chaque marqueur par des espaces, sans changer la longueur du texte : un marqueur n'est
   * pas une fuite (ni « PERSONNE » une personne), et les positions restent valables.
   */
  public static String mask(String text) {
    StringBuilder masked = new StringBuilder(text);
    for (int[] span : spans(text)) {
      masked.replace(span[0], span[1], " ".repeat(span[1] - span[0]));
    }
    return masked.toString();
  }
}
