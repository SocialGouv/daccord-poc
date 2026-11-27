package fr.gouv.travail.anonymisation.documents.word;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

/** Lecture et retouche de fichiers .docx dans les tests. */
public final class DocxTestSupport {

  private DocxTestSupport() {}

  public static byte[] save(XWPFDocument document) {
    try (document) {
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      document.write(output);
      return output.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Ouvre le document avec POI : échoue si le fichier produit n'est plus un .docx valide. */
  public static XWPFDocument open(byte[] docx) {
    try {
      return new XWPFDocument(new ByteArrayInputStream(docx));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static Map<String, String> parts(byte[] docx) {
    Map<String, String> parts = new LinkedHashMap<>();
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(docx))) {
      for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
        parts.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return parts;
  }

  public static String part(byte[] docx, String name) {
    return parts(docx).get(name);
  }

  /** Remplace le contenu d'une partie (ou l'ajoute si elle n'existe pas). */
  public static byte[] withPart(byte[] docx, String name, UnaryOperator<String> edit) {
    Map<String, String> parts = parts(docx);
    parts.put(name, edit.apply(parts.getOrDefault(name, "")));
    return zip(parts);
  }

  /** Ajoute une partie binaire (un document incorporé, une image), sans toucher aux autres. */
  public static byte[] withBinaryPart(byte[] docx, String name, byte[] data) {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(output);
        ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(docx))) {
      for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
        zip.putNextEntry(new ZipEntry(entry.getName()));
        zip.write(in.readAllBytes());
        zip.closeEntry();
      }
      zip.putNextEntry(new ZipEntry(name));
      zip.write(data);
      zip.closeEntry();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return output.toByteArray();
  }

  public static byte[] zip(Map<String, String> parts) {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(output)) {
      for (Map.Entry<String, String> part : parts.entrySet()) {
        zip.putNextEntry(new ZipEntry(part.getKey()));
        zip.write(part.getValue().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return output.toByteArray();
  }
}
