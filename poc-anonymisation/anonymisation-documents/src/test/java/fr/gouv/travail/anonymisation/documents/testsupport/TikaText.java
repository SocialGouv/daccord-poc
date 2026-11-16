package fr.gouv.travail.anonymisation.documents.testsupport;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.tika.exception.TikaException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.xml.sax.SAXException;

/** Relit un document avec Tika, comme le fait Daccord, pour contrôler ce que l'on a produit. */
public final class TikaText {

  public static final String AUTHOR = "dc:creator";
  public static final String LAST_AUTHOR = "meta:last-author";
  public static final String COMPANY = "extended-properties:Company";
  public static final String TITLE = "dc:title";

  /**
   * @param metadata propriétés du document, sous leurs noms Tika, celles qui sont renseignées
   */
  public record Extracted(String text, Map<String, String> metadata) {}

  private static final AutoDetectParser PARSER = new AutoDetectParser();

  private TikaText() {}

  public static Extracted extract(byte[] content) {
    BodyContentHandler handler = new BodyContentHandler(-1);
    Metadata metadata = new Metadata();
    try (TikaInputStream in = TikaInputStream.get(content)) {
      PARSER.parse(in, handler, metadata, new ParseContext());
    } catch (IOException | SAXException | TikaException e) {
      throw new IllegalStateException("Document illisible par Tika", e);
    }
    Map<String, String> properties = new LinkedHashMap<>();
    for (String key : new String[] {AUTHOR, LAST_AUTHOR, COMPANY, TITLE}) {
      String value = metadata.get(key);
      if (value != null && !value.isBlank()) {
        properties.put(key, value);
      }
    }
    return new Extracted(handler.toString(), properties);
  }
}
