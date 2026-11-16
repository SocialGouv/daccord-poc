package fr.gouv.travail.anonymisation.core.detection;

import fr.gouv.travail.anonymisation.core.model.EntityType;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Identifiants structurés français. Un modèle NER ne les voit pas (il a été entraîné sur des noms
 * propres), d'où ces motifs. L'ordre compte : en cas de chevauchement, le premier l'emporte.
 */
public final class FrenchRecognizers {

  /**
   * Séparateur optionnel entre groupes de chiffres : espace, espace insécable, ou retour à la ligne
   * (un numéro peut être coupé en fin de ligne, par exemple dans un texte extrait d'un PDF, avec
   * une espace de chaque côté).
   */
  private static final String SP = "[\\s\\u00A0]{0,2}";

  private static final Pattern EMAIL =
      Pattern.compile(
          "(?<![\\p{L}\\p{N}_.+-])[\\p{L}\\p{N}_.+-]+@[\\p{L}\\p{N}_-]+(?:\\.[\\p{L}\\p{N}_-]+)+");

  private static final Pattern IBAN =
      Pattern.compile("\\b[A-Z]{2}\\d{2}(?:\\s{0,2}[A-Z0-9]{4}){2,7}(?:\\s{0,2}[A-Z0-9]{1,3})?\\b");

  private static final Pattern NIR =
      Pattern.compile(
          "(?<!\\d)[12]"
              + SP
              + "\\d{2}"
              + SP
              + "(?:0[1-9]|1[0-2]|[2-9]\\d)"
              + SP
              + "(?:\\d{2}|2[AB])"
              + SP
              + "\\d{3}"
              + SP
              + "\\d{3}"
              + SP
              + "\\d{2}(?!\\d)");

  private static final Pattern SIRET =
      Pattern.compile(
          "(?<!\\d)\\d{3}[\\s.\\u00A0]{0,2}\\d{3}[\\s.\\u00A0]{0,2}\\d{3}[\\s.\\u00A0]{0,2}\\d{5}(?!\\d)");

  private static final Pattern TELEPHONE =
      Pattern.compile(
          "(?<!\\d)(?:(?:\\+33|0033)[\\s.-]{0,2}|0)[1-9](?:[\\s.-]{0,2}\\d{2}){4}(?!\\d)");

  private FrenchRecognizers() {}

  public static List<EntityRecognizer> defaults() {
    return List.of(
        new RegexRecognizer("regex-email", EntityType.EMAIL, EMAIL),
        new RegexRecognizer("regex-iban", EntityType.IBAN, IBAN, Checksums::isValidIban, 0.6),
        new RegexRecognizer("regex-nir", EntityType.NIR, NIR, Checksums::isValidNirKey, 0.6),
        new RegexRecognizer("regex-siret", EntityType.SIRET, SIRET, Checksums::isValidLuhn, 0),
        new RegexRecognizer("regex-telephone", EntityType.TELEPHONE, TELEPHONE));
  }
}
