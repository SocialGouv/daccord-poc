package fr.gouv.travail.anonymisation.core.detection;

import fr.gouv.travail.anonymisation.core.model.DetectedEntity;
import fr.gouv.travail.anonymisation.core.model.EntityType;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Détection par expression régulière, avec validation optionnelle (clé de contrôle, Luhn…). */
public final class RegexRecognizer implements EntityRecognizer {

  private final String name;
  private final EntityType type;
  private final Pattern pattern;
  private final Predicate<String> validator;
  private final double scoreWhenInvalid;

  public RegexRecognizer(String name, EntityType type, Pattern pattern) {
    this(name, type, pattern, value -> true, 0);
  }

  /**
   * @param scoreWhenInvalid score d'un motif qui échoue à la validation ; 0 pour l'écarter (on
   *     préfère signaler un numéro mal saisi que le laisser passer).
   */
  public RegexRecognizer(
      String name,
      EntityType type,
      Pattern pattern,
      Predicate<String> validator,
      double scoreWhenInvalid) {
    this.name = name;
    this.type = type;
    this.pattern = pattern;
    this.validator = validator;
    this.scoreWhenInvalid = scoreWhenInvalid;
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public List<DetectedEntity> recognize(String text) {
    List<DetectedEntity> entities = new ArrayList<>();
    Matcher matcher = pattern.matcher(text);
    while (matcher.find()) {
      String value = matcher.group();
      double score = validator.test(value) ? 1.0 : scoreWhenInvalid;
      if (score > 0) {
        entities.add(new DetectedEntity(type, matcher.start(), matcher.end(), value, score, name));
      }
    }
    return entities;
  }
}
