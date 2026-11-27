package fr.gouv.travail.anonymisation.core.anonymization;

import fr.gouv.travail.anonymisation.core.model.EntityType;
import java.util.List;
import java.util.Map;

/**
 * Ce qu'il faut remplacer dans un document découpé en fragments (pages, paragraphes…), avec une
 * numérotation commune à tout le document. Le plan ne touche à aucun fichier : chaque format
 * l'applique à sa propre structure.
 *
 * @param replacements pour chaque fragment, les remplacements par position croissante, sans
 *     chevauchement
 * @param counts nombre de remplacements par type (jamais les valeurs d'origine)
 * @param engines moteurs de détection utilisés
 * @param knownValues valeurs remplacées, pour contrôler qu'aucune ne subsiste dans le résultat
 */
public record AnonymizationPlan(
    List<List<Replacement>> replacements,
    Map<EntityType, Integer> counts,
    List<String> engines,
    KnownValues knownValues) {

  /** Positions dans le fragment d'origine ; {@code placeholder} vaut par exemple « [EMAIL_1] ». */
  public record Replacement(int start, int end, EntityType type, String placeholder) {}

  public int total() {
    return counts.values().stream().mapToInt(Integer::intValue).sum();
  }

  public String apply(int fragment, String text) {
    StringBuilder result = new StringBuilder(text.length());
    int cursor = 0;
    for (Replacement replacement : replacements.get(fragment)) {
      result.append(text, cursor, replacement.start()).append(replacement.placeholder());
      cursor = replacement.end();
    }
    return result.append(text, cursor, text.length()).toString();
  }
}
