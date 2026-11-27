package fr.gouv.travail.anonymisation.core.testsupport;

import fr.gouv.travail.anonymisation.core.detection.EntityRecognizer;
import fr.gouv.travail.anonymisation.core.model.DetectedEntity;
import fr.gouv.travail.anonymisation.core.model.EntityType;
import fr.gouv.travail.anonymisation.core.text.TextNormalizer;
import java.util.ArrayList;
import java.util.List;

/** Faux moteur « contextuel » : repère des termes connus, sans casse ni accents, mots entiers. */
public final class DictionaryRecognizer implements EntityRecognizer {

  private final EntityType type;
  private final List<String> terms;

  public DictionaryRecognizer(EntityType type, String... terms) {
    this.type = type;
    this.terms = List.of(terms);
  }

  @Override
  public String name() {
    return "dictionary";
  }

  @Override
  public boolean contextual() {
    return true;
  }

  @Override
  public List<DetectedEntity> recognize(String text) {
    String folded = TextNormalizer.fold(text);
    List<DetectedEntity> entities = new ArrayList<>();
    for (String term : terms) {
      String needle = TextNormalizer.fold(term);
      int index = folded.indexOf(needle);
      while (index >= 0) {
        int end = index + needle.length();
        boolean before = index == 0 || !Character.isLetterOrDigit(folded.charAt(index - 1));
        boolean after = end == folded.length() || !Character.isLetterOrDigit(folded.charAt(end));
        if (before && after) {
          entities.add(
              new DetectedEntity(type, index, end, text.substring(index, end), 0.9, name()));
        }
        index = folded.indexOf(needle, end);
      }
    }
    return entities;
  }
}
