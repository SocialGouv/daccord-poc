package fr.gouv.travail.anonymisation.core.anonymization;

import fr.gouv.travail.anonymisation.core.model.DetectedEntity;
import fr.gouv.travail.anonymisation.core.model.EntityType;
import fr.gouv.travail.anonymisation.core.text.TextNormalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Valeurs déjà repérées dans un document, que l'on cherche ensuite partout ailleurs : un nom que le
 * NER a vu dans neuf paragraphes sur dix doit aussi disparaître du dixième.
 *
 * <p>La recherche ignore la casse, les accents et la mise en forme des espaces, et ne retient que
 * des mots entiers. Pour les noms, lieux et organisations, une occurrence en minuscules est écartée
 * : un faux positif du NER sur un mot courant en début de phrase (« Pour ») ne doit pas effacer
 * tous les « pour » du document.
 */
public final class KnownValues {

  /** En dessous, une « valeur » (« M. », « Al ») est trop courte pour être cherchée sans bruit. */
  static final int MIN_LENGTH = 3;

  /** Identifiants structurés : une correspondance exacte, quelle que soit la casse, est sûre. */
  private static final Set<EntityType> STRUCTURED =
      EnumSet.of(
          EntityType.EMAIL,
          EntityType.TELEPHONE,
          EntityType.IBAN,
          EntityType.NIR,
          EntityType.SIRET);

  private static final Pattern WHITESPACE = Pattern.compile("\\s+");

  private final Map<String, EntityType> typeByFoldedValue = new LinkedHashMap<>();
  private Pattern pattern;

  public record Occurrence(EntityType type, int start, int end) {}

  public void add(DetectedEntity entity) {
    String folded = canonical(TextNormalizer.fold(entity.text()).strip());
    if (folded.length() >= MIN_LENGTH
        && typeByFoldedValue.putIfAbsent(folded, entity.type()) == null) {
      pattern = null;
    }
  }

  /** Occurrences sans chevauchement, par position croissante ; la plus longue l'emporte. */
  public List<Occurrence> find(String text) {
    if (typeByFoldedValue.isEmpty() || text.isEmpty()) {
      return List.of();
    }
    // Les marqueurs d'un texte déjà anonymisé ne comptent pas (« PERSONNE » dans [PERSONNE_1]).
    String searched = Placeholders.mask(text);
    Matcher matcher = pattern().matcher(TextNormalizer.fold(searched));
    List<Occurrence> occurrences = new ArrayList<>();
    while (matcher.find()) {
      EntityType type = typeByFoldedValue.get(canonical(matcher.group()));
      boolean lowerCase = Character.isLowerCase(text.charAt(matcher.start()));
      if (type != null && (STRUCTURED.contains(type) || !lowerCase)) {
        occurrences.add(new Occurrence(type, matcher.start(), matcher.end()));
      }
    }
    return occurrences;
  }

  private Pattern pattern() {
    if (pattern == null) {
      // Les plus longues d'abord : à position égale, « marie dupont » doit l'emporter sur « marie
      // ».
      String alternatives =
          typeByFoldedValue.keySet().stream()
              .sorted(Comparator.comparingInt(String::length).reversed())
              .map(KnownValues::regexFor)
              .collect(Collectors.joining("|"));
      pattern = Pattern.compile("(?<![\\p{L}\\p{N}])(?:" + alternatives + ")(?![\\p{L}\\p{N}])");
    }
    return pattern;
  }

  private static String regexFor(String foldedValue) {
    return Arrays.stream(WHITESPACE.split(foldedValue))
        .map(Pattern::quote)
        .collect(Collectors.joining("\\s+"));
  }

  private static String canonical(String foldedMatch) {
    return WHITESPACE.matcher(foldedMatch).replaceAll(" ");
  }

  /** Ne reproduit pas les valeurs : ce sont des données personnelles. */
  @Override
  public String toString() {
    return "KnownValues[size=%d]".formatted(typeByFoldedValue.size());
  }
}
