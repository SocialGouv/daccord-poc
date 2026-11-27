package fr.gouv.travail.anonymisation.core.ner;

import fr.gouv.travail.anonymisation.core.model.DetectedEntity;
import fr.gouv.travail.anonymisation.core.model.EntityType;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Regroupe les prédictions par token (sous-mots SentencePiece) en entités sur le texte d'origine.
 * Sans dépendance au modèle ni au tokenizer, donc testable seul.
 */
final class NerDecoder {

  /** Prédiction d'un token ; {@code start}/{@code end} sont des offsets de caractères du texte. */
  record TokenPrediction(String label, double score, int start, int end) {}

  private static final Set<String> JOINERS = Set.of("'", "’", "-");

  private NerDecoder() {}

  static List<DetectedEntity> decode(
      String text, List<TokenPrediction> predictions, String source) {
    List<TokenPrediction> tokens =
        bridgeJoiners(text, predictions.stream().filter(t -> t.end() > t.start()).toList());
    List<DetectedEntity> entities = new ArrayList<>();
    EntityType currentType = null;
    int start = 0;
    int end = 0;
    double scoreSum = 0;
    int count = 0;

    for (TokenPrediction token : tokens) {
      Optional<EntityType> type = EntityType.fromNerLabel(token.label());
      boolean continues =
          type.isPresent() && type.get() == currentType && !token.label().startsWith("B-");
      if (continues) {
        end = token.end();
        scoreSum += token.score();
        count++;
        continue;
      }
      flush(entities, text, currentType, start, end, scoreSum / Math.max(count, 1), source);
      currentType = type.orElse(null);
      start = token.start();
      end = token.end();
      scoreSum = token.score();
      count = 1;
    }
    flush(entities, text, currentType, start, end, scoreSum / Math.max(count, 1), source);
    return entities;
  }

  /**
   * Une apostrophe ou un trait d'union collé entre deux tokens de même type fait partie de
   * l'entité, quoi qu'en dise le modèle. CamemBERT-NER classe souvent le « ' » de « Caisse
   * d'Allocations Familiales » hors entité (probabilité de « O » de 0,76 à 0,98 mesurée), ce qui
   * coupait l'organisation en deux.
   */
  private static List<TokenPrediction> bridgeJoiners(String text, List<TokenPrediction> tokens) {
    List<TokenPrediction> bridged = new ArrayList<>(tokens);
    for (int i = 1; i < tokens.size() - 1; i++) {
      TokenPrediction previous = tokens.get(i - 1);
      TokenPrediction token = tokens.get(i);
      TokenPrediction next = tokens.get(i + 1);
      Optional<EntityType> type = EntityType.fromNerLabel(previous.label());
      boolean joiner = JOINERS.contains(text.substring(token.start(), token.end()));
      boolean contiguous = previous.end() == token.start() && token.end() == next.start();
      if (joiner
          && contiguous
          && type.isPresent()
          && type.equals(EntityType.fromNerLabel(next.label()))
          && EntityType.fromNerLabel(token.label()).isEmpty()) {
        String label = "I-" + previous.label().substring(previous.label().indexOf('-') + 1);
        double score = (previous.score() + next.score()) / 2;
        bridged.set(i, new TokenPrediction(label, score, token.start(), token.end()));
      }
    }
    return bridged;
  }

  private static void flush(
      List<DetectedEntity> entities,
      String text,
      EntityType type,
      int start,
      int end,
      double score,
      String source) {
    if (type == null) {
      return;
    }
    // Selon le tokenizer, l'offset d'un token « ▁Jean » peut englober l'espace qui précède.
    while (start < end && Character.isWhitespace(text.charAt(start))) {
      start++;
    }
    while (end > start && Character.isWhitespace(text.charAt(end - 1))) {
      end--;
    }
    if (end > start) {
      entities.add(new DetectedEntity(type, start, end, text.substring(start, end), score, source));
    }
  }
}
