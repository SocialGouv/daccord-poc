package fr.gouv.travail.anonymisation.api;

import fr.gouv.travail.anonymisation.core.anonymization.AnonymizationResult;
import fr.gouv.travail.anonymisation.core.anonymization.Anonymizer;
import fr.gouv.travail.anonymisation.core.verification.AnonymizationVerifier;
import fr.gouv.travail.anonymisation.core.verification.VerificationReport;
import io.swagger.v3.oas.annotations.Operation;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** Le contenu des fichiers n'est jamais journalisé : ce sont des données personnelles. */
@RestController
@RequestMapping("/api")
class AnonymisationController {

  private final Anonymizer anonymizer;
  private final AnonymizationVerifier verifier;

  AnonymisationController(Anonymizer anonymizer, AnonymizationVerifier verifier) {
    this.anonymizer = anonymizer;
    this.verifier = verifier;
  }

  @Operation(summary = "Anonymise un fichier texte (UTF-8)")
  @PostMapping(value = "/anonymisations", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  AnonymizationResult anonymise(@RequestPart("file") MultipartFile file) {
    return anonymizer.anonymize(readText(file));
  }

  @Operation(
      summary = "Vérifie qu'un fichier est anonymisé",
      description =
          "Recherche les données personnelles restantes dans « file ». Si « original » est fourni,"
              + " contrôle aussi qu'aucune valeur de l'original ne subsiste dans « file ».")
  @PostMapping(value = "/verifications", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  VerificationReport verify(
      @RequestPart("file") MultipartFile file,
      @RequestPart(value = "original", required = false) MultipartFile original) {
    String anonymized = readText(file);
    return original == null || original.isEmpty()
        ? verifier.verify(anonymized)
        : verifier.verify(readText(original), anonymized);
  }

  private static String readText(MultipartFile file) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(file.getBytes()))
          .toString();
    } catch (CharacterCodingException e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Le fichier doit être un texte encodé en UTF-8.");
    } catch (IOException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Fichier illisible.");
    }
  }
}
