package fr.gouv.travail.anonymisation.api;

import static fr.gouv.travail.anonymisation.api.WordTextExtractor.unprocessable;

import fr.gouv.travail.anonymisation.api.WordTextExtractor.Extracted;
import fr.gouv.travail.anonymisation.api.WordTextExtractor.Format;
import fr.gouv.travail.anonymisation.core.anonymization.AnonymizationPlan;
import fr.gouv.travail.anonymisation.core.anonymization.Anonymizer;
import fr.gouv.travail.anonymisation.core.verification.AnonymizationVerifier;
import fr.gouv.travail.anonymisation.core.verification.VerificationReport;
import fr.gouv.travail.anonymisation.documents.IncompleteAnonymisationException;
import fr.gouv.travail.anonymisation.documents.InvalidDocumentException;
import fr.gouv.travail.anonymisation.documents.word.DocAnonymizer;
import fr.gouv.travail.anonymisation.documents.word.DocxAnonymizer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.io.IOException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Documents Word (.docx et .doc). Le type est reconnu sur le contenu. L'anonymisation réécrit le
 * document ; la vérification lit son texte avec Tika, comme Daccord. Le contenu n'est jamais
 * journalisé.
 */
@RestController
@RequestMapping("/api")
class WordController {

  private static final Logger LOG = LoggerFactory.getLogger(WordController.class);

  static final String REPLACEMENTS_HEADER = "X-Anonymisation-Replacements";
  static final String WARNINGS_HEADER = "X-Anonymisation-Warnings";

  private final WordTextExtractor extractor;
  private final Anonymizer anonymizer;
  private final AnonymizationVerifier verifier;
  private final DocxAnonymizer docx = new DocxAnonymizer();
  private final DocAnonymizer doc = new DocAnonymizer();

  WordController(
      WordTextExtractor extractor, Anonymizer anonymizer, AnonymizationVerifier verifier) {
    this.extractor = extractor;
    this.anonymizer = anonymizer;
    this.verifier = verifier;
  }

  @Operation(
      summary = "Document Word (.docx ou .doc) → document Word anonymisé",
      description =
          "Réponse : le document anonymisé, au même format que celui reçu. Un .docx est réécrit en"
              + " XML (corps, tableaux, zones de texte, en-têtes, notes, commentaires, champs,"
              + " liens, propriétés) avec des marqueurs [TYPE_n]. Un .doc est réécrit sans changer"
              + " la longueur du texte : chaque valeur devient une suite d'astérisques. Créateur,"
              + " dernier auteur, société, responsable et auteurs des révisions sont effacés. Le"
              + " résultat est relu avec Tika : s'il contient encore une valeur remplacée ailleurs"
              + " (un document incorporé, par exemple), la réponse est 422. En-têtes de réponse :"
              + " "
              + REPLACEMENTS_HEADER
              + " (nombre de remplacements par type, absent s'il n'y en a aucun) et "
              + WARNINGS_HEADER
              + " (NER_INACTIF : seuls les identifiants structurés ont été traités).")
  @ApiResponse(
      responseCode = "200",
      content = {
        @Content(
            mediaType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            schema = @Schema(type = "string", format = "binary")),
        @Content(
            mediaType = "application/msword",
            schema = @Schema(type = "string", format = "binary"))
      },
      headers = {
        @Header(name = REPLACEMENTS_HEADER, description = "Ex. PERSONNE=8,EMAIL=2"),
        @Header(name = WARNINGS_HEADER, description = "Ex. NER_INACTIF")
      })
  @PostMapping(value = "/anonymisations/word", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  ResponseEntity<byte[]> anonymise(@RequestPart("file") MultipartFile file) {
    byte[] content = bytes(file);
    Format format = extractor.format(content);

    byte[] output;
    AnonymizationPlan plan;
    try {
      if (format == Format.DOCX) {
        DocxAnonymizer.Result result = docx.anonymize(content, anonymizer);
        output = result.content();
        plan = result.plan();
      } else {
        DocAnonymizer.Result result = doc.anonymize(content, anonymizer);
        output = result.content();
        plan = result.plan();
      }
    } catch (InvalidDocumentException | IncompleteAnonymisationException e) {
      throw unprocessable(e.getMessage());
    } catch (RuntimeException e) {
      // On ne journalise que le type : le message d'une bibliothèque peut citer le document.
      LOG.warn("Réécriture impossible ({})", e.getClass().getName());
      throw unprocessable("Ce document ne peut pas être réécrit de façon sûre.");
    }
    checkNothingRemains(output, plan);

    ResponseEntity.BodyBuilder response =
        ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(format.mediaType))
            // Nom neutre : celui du fichier reçu peut contenir un nom de personne.
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment()
                    .filename("anonymise." + format.extension)
                    .build()
                    .toString());
    if (!plan.counts().isEmpty()) {
      response.header(
          REPLACEMENTS_HEADER,
          plan.counts().entrySet().stream()
              .map(entry -> entry.getKey() + "=" + entry.getValue())
              .collect(Collectors.joining(",")));
    }
    if (!anonymizer.hasContextualRecognizer()) {
      response.header(WARNINGS_HEADER, "NER_INACTIF");
    }
    return response.body(output);
  }

  @Operation(
      summary = "Document Word (.docx ou .doc) → est-il anonymisé ?",
      description =
          "Le texte est extrait avec Apache Tika, comme le fait Daccord (corps, tableaux, en-têtes,"
              + " notes, commentaires), puis recherche les données personnelles restantes. Si"
              + " « original » (un document Word aussi) est fourni, contrôle de plus qu'aucune"
              + " valeur de l'original ne subsiste dans « file ». Les positions des restes sont"
              + " celles du texte extrait, pas celles du fichier. Les propriétés du document"
              + " (auteur, titre) ne sont pas lues.")
  @PostMapping(value = "/verifications/word", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  VerificationReport verify(
      @RequestPart("file") MultipartFile file,
      @RequestPart(value = "original", required = false) MultipartFile original) {
    String anonymized = extractor.extract(bytes(file)).text();
    return original == null || original.isEmpty()
        ? verifier.verify(anonymized)
        : verifier.verify(extractor.extract(bytes(original)).text(), anonymized);
  }

  /**
   * Le document produit est relu avec Tika, comme Daccord le lirait : s'il contient encore une
   * valeur que l'on s'était engagé à remplacer (texte dans une zone que l'on ne sait pas réécrire),
   * on le refuse au lieu de le livrer comme anonymisé.
   */
  private void checkNothingRemains(byte[] output, AnonymizationPlan plan) {
    Extracted extracted = extractor.extract(output);
    int remaining = plan.knownValues().find(extracted.text()).size();
    for (String property : extracted.properties()) {
      remaining += plan.knownValues().find(property).size();
    }
    if (remaining > 0) {
      throw unprocessable(
          ("L'anonymisation est incomplète : %d valeur(s) remplacée(s) ailleurs dans le document"
                  + " figurent encore dans une zone que l'on ne sait pas réécrire.")
              .formatted(remaining));
    }
  }

  private static byte[] bytes(MultipartFile file) {
    try {
      if (file.isEmpty()) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Fichier vide.");
      }
      return file.getBytes();
    } catch (IOException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Fichier illisible.");
    }
  }
}
