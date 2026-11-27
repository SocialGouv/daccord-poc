package fr.gouv.travail.anonymisation.core.anonymization;

import fr.gouv.travail.anonymisation.core.anonymization.AnonymizationPlan.Replacement;
import fr.gouv.travail.anonymisation.core.detection.EntityDetector;
import fr.gouv.travail.anonymisation.core.model.DetectedEntity;
import fr.gouv.travail.anonymisation.core.model.EntityType;
import fr.gouv.travail.anonymisation.core.text.TextNormalizer;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Remplace chaque entité par un marqueur {@code [TYPE_n]}. Une même valeur reçoit toujours le même
 * numéro dans le document, ce qui garde le texte lisible (« [PERSONNE_1] a écrit à [PERSONNE_2] »).
 *
 * <p>Une valeur repérée quelque part est aussi remplacée partout ailleurs dans le document (voir
 * {@link KnownValues}).
 *
 * <p>Il s'agit de pseudonymisation au sens du RGPD tant que le texte contient des
 * quasi-identifiants (fonction, date, situation rare) que ni le NER ni les motifs ne voient.
 */
public final class Anonymizer {

  private final EntityDetector detector;

  public Anonymizer(EntityDetector detector) {
    this.detector = detector;
  }

  /** Faux si seuls des motifs structurés sont actifs : les noms, lieux et organisations passent. */
  public boolean hasContextualRecognizer() {
    return detector.hasContextualRecognizer();
  }

  public AnonymizationResult anonymize(String text) {
    AnonymizationPlan plan = plan(List.of(text));
    return new AnonymizationResult(plan.apply(0, text), plan.counts(), plan.engines());
  }

  /**
   * Analyse un document découpé en fragments. La détection porte sur l'ensemble, pour que le NER
   * garde le contexte des paragraphes voisins ; une entité à cheval sur deux fragments est coupée
   * en autant de morceaux.
   */
  public AnonymizationPlan plan(List<String> fragments) {
    List<List<DetectedEntity>> detected =
        splitByFragment(fragments, detector.detect(String.join("\n", fragments)));

    KnownValues knownValues = new KnownValues();
    detected.forEach(entities -> entities.forEach(knownValues::add));

    Map<EntityType, Map<String, Integer>> numbers = new EnumMap<>(EntityType.class);
    Map<EntityType, Integer> counts = new EnumMap<>(EntityType.class);
    List<List<Replacement>> replacements = new ArrayList<>(fragments.size());

    for (int i = 0; i < fragments.size(); i++) {
      List<Replacement> inFragment = new ArrayList<>();
      for (DetectedEntity entity :
          withKnownValues(fragments.get(i), detected.get(i), knownValues)) {
        Map<String, Integer> known = numbers.computeIfAbsent(entity.type(), t -> new HashMap<>());
        known.putIfAbsent(TextNormalizer.key(entity.text()), known.size() + 1);
        int number = known.get(TextNormalizer.key(entity.text()));
        inFragment.add(
            new Replacement(
                entity.start(),
                entity.end(),
                entity.type(),
                "[%s_%d]".formatted(entity.type(), number)));
        counts.merge(entity.type(), 1, Integer::sum);
      }
      replacements.add(List.copyOf(inFragment));
    }
    return new AnonymizationPlan(
        List.copyOf(replacements), counts, detector.engines(), knownValues);
  }

  /** Entités détectées, ramenées aux coordonnées de leur fragment. */
  private static List<List<DetectedEntity>> splitByFragment(
      List<String> fragments, List<DetectedEntity> entities) {
    List<List<DetectedEntity>> result = new ArrayList<>(fragments.size());
    int[] offsets = new int[fragments.size()];
    int offset = 0;
    for (int i = 0; i < fragments.size(); i++) {
      result.add(new ArrayList<>());
      offsets[i] = offset;
      offset += fragments.get(i).length() + 1;
    }

    int fragment = 0;
    for (DetectedEntity entity : entities) {
      while (offsets[fragment] + fragments.get(fragment).length() <= entity.start()) {
        fragment++;
      }
      for (int i = fragment; i < fragments.size() && offsets[i] < entity.end(); i++) {
        int start = Math.max(entity.start(), offsets[i]) - offsets[i];
        int end = Math.min(entity.end(), offsets[i] + fragments.get(i).length()) - offsets[i];
        String piece = end > start ? fragments.get(i).substring(start, end) : "";
        if (!piece.isBlank()) {
          result
              .get(i)
              .add(
                  new DetectedEntity(
                      entity.type(), start, end, piece, entity.score(), entity.source()));
        }
      }
    }
    return result;
  }

  /** Les entités détectées gardent la priorité ; les valeurs connues comblent ce qui reste. */
  private static List<DetectedEntity> withKnownValues(
      String fragment, List<DetectedEntity> detected, KnownValues knownValues) {
    BitSet occupied = new BitSet(fragment.length());
    detected.forEach(entity -> occupied.set(entity.start(), entity.end()));

    List<DetectedEntity> all = new ArrayList<>(detected);
    for (KnownValues.Occurrence occurrence : knownValues.find(fragment)) {
      int next = occupied.nextSetBit(occurrence.start());
      if (next < 0 || next >= occurrence.end()) {
        all.add(
            new DetectedEntity(
                occurrence.type(),
                occurrence.start(),
                occurrence.end(),
                fragment.substring(occurrence.start(), occurrence.end()),
                1.0,
                "known-value"));
      }
    }
    all.sort(Comparator.comparingInt(DetectedEntity::start));
    return all;
  }
}
