package fr.gouv.travail.anonymisation.api;

import fr.gouv.travail.anonymisation.core.anonymization.AnonymizationResult;
import fr.gouv.travail.anonymisation.core.anonymization.Anonymizer;
import fr.gouv.travail.anonymisation.core.verification.AnonymizationVerifier;
import fr.gouv.travail.anonymisation.core.verification.VerificationReport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Le texte arrive dans un corps JSON, sans fichier. Comme pour les fichiers, il n'est jamais
 * journalisé : ce sont des données personnelles.
 */
@RestController
@RequestMapping("/api")
class ChaineController {

  record AnonymisationRequest(
      @Schema(description = "Texte à anonymiser", example = "Jean Dupont habite à Lyon.")
          String text) {}

  record VerificationRequest(
      @Schema(
              description = "Texte dont on veut savoir s'il est anonymisé",
              example = "[PERSONNE_1] habite à [LIEU_1].")
          String text,
      @Schema(
              description =
                  "Texte avant anonymisation (facultatif) : si présent, on contrôle aussi qu'aucune"
                      + " de ses valeurs ne subsiste dans « text »",
              example = "Jean Dupont habite à Lyon.")
          String original) {}

  private final Anonymizer anonymizer;
  private final AnonymizationVerifier verifier;

  ChaineController(Anonymizer anonymizer, AnonymizationVerifier verifier) {
    this.anonymizer = anonymizer;
    this.verifier = verifier;
  }

  @Operation(summary = "Chaîne de caractères → texte anonymisé")
  @PostMapping(value = "/anonymisations/chaine", consumes = MediaType.APPLICATION_JSON_VALUE)
  AnonymizationResult anonymise(@RequestBody AnonymisationRequest request) {
    return anonymizer.anonymize(required(request.text()));
  }

  @Operation(
      summary = "Chaîne de caractères → est-elle anonymisée ?",
      description =
          "Recherche les données personnelles restantes dans « text ». Si « original » est fourni,"
              + " contrôle aussi qu'aucune valeur de l'original ne subsiste dans « text ».")
  @PostMapping(value = "/verifications/chaine", consumes = MediaType.APPLICATION_JSON_VALUE)
  VerificationReport verify(@RequestBody VerificationRequest request) {
    String anonymized = required(request.text());
    return request.original() == null
        ? verifier.verify(anonymized)
        : verifier.verify(request.original(), anonymized);
  }

  private static String required(String text) {
    if (text == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Le champ « text » est obligatoire.");
    }
    return text;
  }
}
