package fr.gouv.travail.anonymisation.core.detection;

import fr.gouv.travail.anonymisation.core.model.DetectedEntity;
import fr.gouv.travail.anonymisation.core.model.EntityType;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Agrège plusieurs moteurs en une liste d'entités sans chevauchement. L'ordre des moteurs fixe la
 * priorité : un motif validé (IBAN…) l'emporte sur le NER, qui l'emporte sur ce qui suit.
 *
 * <p>Le même détecteur sert à anonymiser et à vérifier : ce qu'on s'engage à anonymiser est
 * exactement ce qu'on contrôle ensuite.
 */
public final class EntityDetector {

  private final List<EntityRecognizer> recognizers;
  private final Set<EntityType> types;
  private final double minScore;

  public EntityDetector(
      List<EntityRecognizer> recognizers, Set<EntityType> types, double minScore) {
    this.recognizers = List.copyOf(recognizers);
    this.types = Set.copyOf(types);
    this.minScore = minScore;
  }

  public List<DetectedEntity> detect(String text) {
    List<DetectedEntity> accepted = new ArrayList<>();
    BitSet occupied = new BitSet(text.length());
    Comparator<DetectedEntity> bestFirst =
        Comparator.comparingDouble(DetectedEntity::score)
            .reversed()
            .thenComparing(Comparator.comparingInt(DetectedEntity::length).reversed());

    for (EntityRecognizer recognizer : recognizers) {
      List<DetectedEntity> candidates =
          recognizer.recognize(text).stream()
              .filter(entity -> types.contains(entity.type()) && entity.score() >= minScore)
              .sorted(bestFirst)
              .toList();
      for (DetectedEntity candidate : candidates) {
        int next = occupied.nextSetBit(candidate.start());
        if (next < 0 || next >= candidate.end()) {
          accepted.add(candidate);
          occupied.set(candidate.start(), candidate.end());
        }
      }
    }
    accepted.sort(Comparator.comparingInt(DetectedEntity::start));
    return List.copyOf(accepted);
  }

  public List<String> engines() {
    return recognizers.stream().map(EntityRecognizer::name).toList();
  }

  public boolean hasContextualRecognizer() {
    return recognizers.stream().anyMatch(EntityRecognizer::contextual);
  }
}
