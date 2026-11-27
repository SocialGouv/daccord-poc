package fr.gouv.travail.anonymisation.core.verification;

import fr.gouv.travail.anonymisation.core.anonymization.Placeholders;
import fr.gouv.travail.anonymisation.core.detection.EntityDetector;
import fr.gouv.travail.anonymisation.core.model.DetectedEntity;
import fr.gouv.travail.anonymisation.core.text.TextNormalizer;
import fr.gouv.travail.anonymisation.core.text.WordBoundaries;
import fr.gouv.travail.anonymisation.core.verification.VerificationReport.Finding;
import fr.gouv.travail.anonymisation.core.verification.VerificationReport.Kind;
import fr.gouv.travail.anonymisation.core.verification.VerificationReport.Verdict;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Contrôle qu'un texte est bien anonymisé, de deux façons complémentaires :
 *
 * <ul>
 *   <li>sans fichier d'origine : on relance la détection sur le texte anonymisé et tout ce qui est
 *       encore repéré est une fuite potentielle ;
 *   <li>avec le fichier d'origine : on détecte les entités de l'original puis on vérifie qu'aucune
 *       de ces valeurs ne subsiste dans le texte anonymisé (sans tenir compte de la casse ni des
 *       accents). C'est le contrôle le plus fiable, il ne dépend pas du rappel du modèle sur le
 *       texte anonymisé.
 * </ul>
 */
public final class AnonymizationVerifier {

  /** En dessous, une « valeur » (« M. », « Al ») est trop courte pour être cherchée sans bruit. */
  private static final int MIN_LEAK_LENGTH = 3;

  private final EntityDetector detector;

  public AnonymizationVerifier(EntityDetector detector) {
    this.detector = detector;
  }

  public VerificationReport verify(String anonymized) {
    return verify(null, anonymized);
  }

  /**
   * @param original texte avant anonymisation, ou {@code null} s'il n'est pas disponible
   */
  public VerificationReport verify(String original, String anonymized) {
    List<Finding> leaks =
        original == null ? List.of() : findLeaks(original, Placeholders.mask(anonymized));
    List<Finding> findings = new ArrayList<>(leaks);
    for (DetectedEntity entity : detectResidual(anonymized)) {
      if (leaks.stream().noneMatch(leak -> leak.overlaps(entity.start(), entity.end()))) {
        findings.add(
            new Finding(
                Kind.RESIDUAL_ENTITY,
                entity.type(),
                entity.start(),
                entity.end(),
                entity.text(),
                entity.score()));
      }
    }
    findings.sort(Comparator.comparingInt(Finding::start));

    List<String> warnings = new ArrayList<>();
    if (!detector.hasContextualRecognizer()) {
      warnings.add(
          "Aucun moteur NER actif : seuls les identifiants structurés (e-mail, téléphone, IBAN, "
              + "NIR, SIRET) sont contrôlés ; les noms, lieux et organisations ne le sont pas.");
    }
    return new VerificationReport(
        findings.isEmpty() ? Verdict.CONFORME : Verdict.NON_CONFORME,
        List.copyOf(findings),
        detector.engines(),
        List.copyOf(warnings));
  }

  /**
   * Entités que le détecteur trouve encore dans un texte anonymisé, hors marqueurs.
   *
   * <p>Le texte est présenté tel quel au NER, marqueurs compris : les remplacer par des espaces lui
   * ferait perdre le contexte, et il prend alors pour une personne un mot comme « Délégué ». Le
   * modèle reconnaît les marqueurs eux-mêmes comme des noms, ce qu'ils représentent : on écarte ce
   * qu'il y a de commun avec un marqueur. Une valeur oubliée juste à côté d'un marqueur, que le
   * modèle aurait fondue avec lui dans une même entité, reste signalée.
   */
  private List<DetectedEntity> detectResidual(String anonymized) {
    List<int[]> placeholders = Placeholders.spans(anonymized);
    List<DetectedEntity> residual = new ArrayList<>();
    for (DetectedEntity entity : detector.detect(anonymized)) {
      int from = entity.start();
      boolean overlapping = false;
      for (int[] placeholder : placeholders) {
        if (placeholder[1] <= entity.start() || placeholder[0] >= entity.end()) {
          continue;
        }
        overlapping = true;
        addPiece(residual, anonymized, entity, from, placeholder[0]);
        from = Math.max(from, placeholder[1]);
      }
      if (overlapping) {
        addPiece(residual, anonymized, entity, from, entity.end());
      } else {
        residual.add(entity);
      }
    }
    return residual;
  }

  /** Ajoute le morceau {@code [from, to[} d'une entité, sans la ponctuation qui l'entoure. */
  private static void addPiece(
      List<DetectedEntity> residual, String text, DetectedEntity entity, int from, int to) {
    int start = from;
    int end = to;
    while (start < end && !Character.isLetterOrDigit(text.charAt(start))) {
      start++;
    }
    while (end > start && !Character.isLetterOrDigit(text.charAt(end - 1))) {
      end--;
    }
    if (end > start) {
      residual.add(
          new DetectedEntity(
              entity.type(),
              start,
              end,
              text.substring(start, end),
              entity.score(),
              entity.source()));
    }
  }

  private List<Finding> findLeaks(String original, String scanned) {
    String foldedScanned = TextNormalizer.fold(scanned);
    Set<String> searched = new HashSet<>();
    List<Finding> leaks = new ArrayList<>();

    for (DetectedEntity entity : detector.detect(original)) {
      String needle = TextNormalizer.fold(entity.text()).strip();
      if (needle.length() < MIN_LEAK_LENGTH || !searched.add(needle)) {
        continue;
      }
      int from = 0;
      int index;
      while ((index = foldedScanned.indexOf(needle, from)) >= 0) {
        int end = index + needle.length();
        if (WordBoundaries.isWordBoundary(foldedScanned, index, end)) {
          leaks.add(
              new Finding(
                  Kind.ORIGINAL_VALUE_LEAK,
                  entity.type(),
                  index,
                  end,
                  scanned.substring(index, end),
                  1.0));
        }
        from = end;
      }
    }
    return keepLongestNonOverlapping(leaks);
  }

  /**
   * « Marie » et « Marie Dupont » peuvent toutes deux être cherchées : on ne garde que la plus
   * longue.
   */
  private static List<Finding> keepLongestNonOverlapping(List<Finding> leaks) {
    List<Finding> sorted = new ArrayList<>(leaks);
    sorted.sort(
        Comparator.comparingInt(Finding::start)
            .thenComparing(Comparator.comparingInt((Finding f) -> f.end() - f.start()).reversed()));
    List<Finding> kept = new ArrayList<>();
    int lastEnd = -1;
    for (Finding leak : sorted) {
      if (leak.start() >= lastEnd) {
        kept.add(leak);
        lastEnd = leak.end();
      }
    }
    return kept;
  }
}
