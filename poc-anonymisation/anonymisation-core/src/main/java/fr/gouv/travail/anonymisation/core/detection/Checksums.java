package fr.gouv.travail.anonymisation.core.detection;

import java.util.Locale;

/** Contrôles de cohérence des identifiants structurés. */
public final class Checksums {

  private Checksums() {}

  /**
   * IBAN : le reste de la division par 97 de l'IBAN réarrangé, lettres converties en nombres, vaut
   * 1.
   */
  public static boolean isValidIban(String raw) {
    String iban = raw.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
    if (iban.length() < 15 || iban.length() > 34) {
      return false;
    }
    String rearranged = iban.substring(4) + iban.substring(0, 4);
    int remainder = 0;
    for (char c : rearranged.toCharArray()) {
      int value = Character.digit(c, 36);
      if (value < 0) {
        return false;
      }
      remainder = (value < 10 ? remainder * 10 + value : remainder * 100 + value) % 97;
    }
    return remainder == 1;
  }

  /**
   * NIR : 13 chiffres suivis d'une clé de 2 chiffres égale à 97 - (nombre modulo 97). 2A/2B valent
   * 19/18.
   */
  public static boolean isValidNirKey(String raw) {
    String nir = raw.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
    if (nir.length() != 15) {
      return false;
    }
    try {
      long body = Long.parseLong(nir.substring(0, 13).replace("2A", "19").replace("2B", "18"));
      return 97 - body % 97 == Integer.parseInt(nir.substring(13));
    } catch (NumberFormatException e) {
      return false;
    }
  }

  /** Algorithme de Luhn, utilisé par les SIREN/SIRET. Espaces et points sont ignorés. */
  public static boolean isValidLuhn(String raw) {
    String digits = raw.replaceAll("[\\s.]", "");
    if (digits.isEmpty() || !digits.chars().allMatch(c -> c >= '0' && c <= '9')) {
      return false;
    }
    int sum = 0;
    boolean doubled = false;
    for (int i = digits.length() - 1; i >= 0; i--) {
      int digit = digits.charAt(i) - '0';
      if (doubled) {
        digit *= 2;
        if (digit > 9) {
          digit -= 9;
        }
      }
      sum += digit;
      doubled = !doubled;
    }
    return sum % 10 == 0;
  }
}
