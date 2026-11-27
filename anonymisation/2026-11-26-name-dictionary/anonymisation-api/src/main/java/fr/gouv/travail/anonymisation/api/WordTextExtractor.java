package fr.gouv.travail.anonymisation.api;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.tika.config.TikaConfig;
import org.apache.tika.exception.EncryptedDocumentException;
import org.apache.tika.exception.TikaException;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.xml.sax.SAXException;

/**
 * Lit un document Word avec Apache Tika, comme Daccord le fait. Le type est reconnu sur le contenu
 * : ni le nom du fichier ni le {@code Content-Type} annoncé ne comptent. Pas d'OCR.
 *
 * <p>Un texte trop long est refusé au lieu d'être tronqué : le tronquer en silence ferait déclarer
 * « conforme » un document dont seul le début a été lu (la façade {@code Tika.parseToString}
 * s'arrête à 100 000 caractères par défaut).
 */
final class WordTextExtractor {

  enum Format {
    DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"),
    DOC("application/msword", "doc");

    final String mediaType;
    final String extension;

    Format(String mediaType, String extension) {
      this.mediaType = mediaType;
      this.extension = extension;
    }
  }

  /**
   * @param text texte du document
   * @param properties valeurs renseignées des propriétés qui peuvent désigner quelqu'un (auteur,
   *     société, titre…)
   */
  record Extracted(String text, List<String> properties) {}

  /** Un document Office chiffré n'est plus reconnu comme un .docx. */
  private static final String PROTECTED_OOXML = "application/x-tika-ooxml-protected";

  private static final int MAX_CHARACTERS = 20_000_000;

  private static final List<String> PROPERTY_KEYS =
      List.of(
          "dc:creator",
          "meta:author",
          "meta:last-author",
          "extended-properties:Manager",
          "extended-properties:Company",
          "dc:title",
          "dc:subject",
          "dc:description",
          "meta:keyword");

  private final TikaConfig config = TikaConfig.getDefaultConfig();
  private final AutoDetectParser parser = new AutoDetectParser(config);

  /** Format du document, ou 415 s'il n'est ni un .docx ni un .doc. */
  Format format(byte[] content) {
    String mediaType = detect(content);
    if (PROTECTED_OOXML.equals(mediaType)) {
      throw unprocessable("Document protégé par mot de passe.");
    }
    for (Format format : Format.values()) {
      if (format.mediaType.equals(mediaType)) {
        return format;
      }
    }
    throw new ResponseStatusException(
        HttpStatus.UNSUPPORTED_MEDIA_TYPE,
        "Le fichier doit être un document Word (.docx ou .doc) ; type détecté : %s."
            .formatted(mediaType));
  }

  Extracted extract(byte[] content) {
    format(content);

    BodyContentHandler handler = new BodyContentHandler(MAX_CHARACTERS);
    Metadata metadata = new Metadata();
    try (TikaInputStream in = TikaInputStream.get(content)) {
      parser.parse(in, handler, metadata, new ParseContext());
    } catch (EncryptedDocumentException e) {
      throw unprocessable("Document protégé par mot de passe.");
    } catch (SAXException e) {
      throw unprocessable(
          WriteLimitReachedException.isWriteLimitReached(e)
              ? "Document trop volumineux : plus de %d caractères de texte."
                  .formatted(MAX_CHARACTERS)
              : "Document illisible.");
    } catch (IOException | TikaException | RuntimeException e) {
      throw unprocessable("Document illisible.");
    }

    String text = handler.toString();
    if (text.isBlank()) {
      throw unprocessable("Aucun texte n'a pu être extrait du document (images seules ?).");
    }
    List<String> properties = new ArrayList<>();
    for (String key : PROPERTY_KEYS) {
      for (String value : metadata.getValues(key)) {
        if (value != null && !value.isBlank()) {
          properties.add(value);
        }
      }
    }
    return new Extracted(text, List.copyOf(properties));
  }

  private String detect(byte[] content) {
    try (TikaInputStream in = TikaInputStream.get(content)) {
      return config.getDetector().detect(in, new Metadata()).toString();
    } catch (IOException e) {
      throw unprocessable("Document illisible.");
    }
  }

  static ResponseStatusException unprocessable(String message) {
    return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
  }
}
