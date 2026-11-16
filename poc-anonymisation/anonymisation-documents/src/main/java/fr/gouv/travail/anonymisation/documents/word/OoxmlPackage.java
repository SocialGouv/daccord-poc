package fr.gouv.travail.anonymisation.documents.word;

import fr.gouv.travail.anonymisation.documents.InvalidDocumentException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Contenu d'un fichier OOXML (un zip), en mémoire, dans l'ordre d'origine. La lecture est bornée :
 * un document de quelques kilo-octets peut se décompresser en gigaoctets.
 */
final class OoxmlPackage {

  private static final int MAX_ENTRIES = 10_000;
  private static final long MAX_ENTRY_BYTES = 200L << 20;
  private static final long MAX_TOTAL_BYTES = 500L << 20;

  private final Map<String, byte[]> entries = new LinkedHashMap<>();

  static OoxmlPackage read(byte[] content) {
    OoxmlPackage result = new OoxmlPackage();
    long total = 0;
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
      for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
        if (result.entries.size() >= MAX_ENTRIES) {
          throw new InvalidDocumentException("Document trop volumineux (trop de parties).");
        }
        byte[] data = readBounded(zip, Math.min(MAX_ENTRY_BYTES, MAX_TOTAL_BYTES - total));
        total += data.length;
        if (!entry.isDirectory()) {
          result.entries.put(entry.getName(), data);
        }
      }
    } catch (IOException e) {
      throw new InvalidDocumentException("Document illisible.", e);
    }
    return result;
  }

  private static byte[] readBounded(InputStream in, long limit) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    long read = 0;
    for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
      read += n;
      if (read > limit) {
        throw new InvalidDocumentException("Document trop volumineux une fois décompressé.");
      }
      out.write(buffer, 0, n);
    }
    return out.toByteArray();
  }

  Map<String, byte[]> entries() {
    return entries;
  }

  byte[] write() {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(output)) {
      for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
        zip.putNextEntry(new ZipEntry(entry.getKey()));
        zip.write(entry.getValue());
        zip.closeEntry();
      }
    } catch (IOException e) {
      throw new InvalidDocumentException("Écriture du document impossible.", e);
    }
    return output.toByteArray();
  }
}
