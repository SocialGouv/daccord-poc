package fr.gouv.travail.anonymisation.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.poifs.crypt.EncryptionInfo;
import org.apache.poi.poifs.crypt.EncryptionMode;
import org.apache.poi.poifs.crypt.Encryptor;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * NER désactivé : on teste l'extraction par Tika et le contrat HTTP, pas le modèle. Seuls les
 * identifiants structurés (e-mail, téléphone, IBAN) sont donc détectés dans les jeux d'essai.
 */
@SpringBootTest(properties = "anonymisation.ner.enabled=false")
@AutoConfigureMockMvc
class WordApiTest {

  private static final String DOCX =
      "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

  @Autowired private MockMvc mockMvc;

  private static byte[] fixture(String extension) throws IOException {
    return Files.readAllBytes(Path.of("src/test/resources/fixtures/accord." + extension));
  }

  /**
   * Le nom et le type annoncés par le client ne comptent pas : on les choisit volontairement faux.
   */
  private static MockMultipartFile upload(String part, byte[] content) {
    return new MockMultipartFile(part, "document.bin", "application/octet-stream", content);
  }

  private static byte[] docxWithText(String text) throws IOException {
    try (XWPFDocument document = new XWPFDocument()) {
      document.createParagraph().createRun().setText(text);
      return save(document);
    }
  }

  private static byte[] save(XWPFDocument document) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    document.write(output);
    return output.toByteArray();
  }

  @ParameterizedTest
  @CsvSource({"docx, " + DOCX, "doc, application/msword"})
  void anonymiseUnDocumentWordEtLeRenvoieDansLeMemeFormat(String extension, String mediaType)
      throws Exception {
    byte[] original = fixture(extension);

    MvcResult result =
        mockMvc
            .perform(multipart("/api/anonymisations/word").file(upload("file", original)))
            .andExpect(status().isOk())
            .andExpect(content().contentType(mediaType))
            .andExpect(
                header().string("Content-Disposition", containsString("anonymise." + extension)))
            .andExpect(header().string("X-Anonymisation-Replacements", containsString("EMAIL=2")))
            .andExpect(header().string("X-Anonymisation-Replacements", containsString("IBAN=1")))
            .andExpect(header().string("X-Anonymisation-Warnings", "NER_INACTIF"))
            .andReturn();
    byte[] anonymized = result.getResponse().getContentAsByteArray();

    // Le fichier produit reste un document Word que POI sait rouvrir…
    if (extension.equals("docx")) {
      new XWPFDocument(new ByteArrayInputStream(anonymized)).close();
    } else {
      new HWPFDocument(new ByteArrayInputStream(anonymized)).close();
    }
    // … et le service de vérification le déclare conforme, valeurs de l'original comprises.
    mockMvc
        .perform(
            multipart("/api/verifications/word")
                .file(upload("file", anonymized))
                .file(upload("original", original)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.verdict").value("CONFORME"))
        .andExpect(jsonPath("$.findings").isEmpty());
  }

  @Test
  void laReecritureDUnDocxGardeLeReste() throws Exception {
    byte[] anonymized =
        mockMvc
            .perform(multipart("/api/anonymisations/word").file(upload("file", fixture("docx"))))
            .andReturn()
            .getResponse()
            .getContentAsByteArray();

    mockMvc
        .perform(multipart("/api/verifications/word").file(upload("file", anonymized)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.verdict").value("CONFORME"));
    try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(anonymized))) {
      String text = new XWPFWordExtractor(document).getText();
      assertThat(text)
          .contains("Accord sur le temps de travail", "[IBAN_1]", "[EMAIL_1]", "[TELEPHONE_1]")
          .doesNotContain("marie.martin@example.fr", "FR76");
    }
  }

  @ParameterizedTest
  @CsvSource({"docx", "doc"})
  void declareNonConformeUnDocumentQuiContientEncoreDesIdentifiants(String extension)
      throws Exception {
    mockMvc
        .perform(multipart("/api/verifications/word").file(upload("file", fixture(extension))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.verdict").value("NON_CONFORME"))
        .andExpect(jsonPath("$.findings[?(@.type == 'EMAIL')]").isNotEmpty())
        .andExpect(jsonPath("$.findings[?(@.type == 'IBAN')]").isNotEmpty())
        .andExpect(jsonPath("$.warnings[0]").value(containsString("NER")));
  }

  @Test
  void declareConformeUnDocumentAnonymise() throws Exception {
    mockMvc
        .perform(
            multipart("/api/verifications/word")
                .file(upload("file", docxWithText("Contact : [EMAIL_1]"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.verdict").value("CONFORME"))
        .andExpect(jsonPath("$.findings").isEmpty());
  }

  @Test
  void compareAvecLOriginalQuandIlEstFourni() throws Exception {
    mockMvc
        .perform(
            multipart("/api/verifications/word")
                .file(upload("file", docxWithText("Voir Marie.Martin@EXAMPLE.fr")))
                .file(upload("original", fixture("doc"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.verdict").value("NON_CONFORME"))
        .andExpect(jsonPath("$.findings[?(@.kind == 'ORIGINAL_VALUE_LEAK')]").isNotEmpty());
  }

  @Test
  void refuseUnFichierQuiNEstPasUnDocumentWord() throws Exception {
    mockMvc
        .perform(
            multipart("/api/anonymisations/word")
                .file(upload("file", "Jean Dupont".getBytes(StandardCharsets.UTF_8))))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(jsonPath("$.detail").value(containsString("text/plain")));
    mockMvc
        .perform(
            multipart("/api/verifications/word")
                .file(
                    upload(
                        "file",
                        "%PDF-1.7\n%âãÏÓ\n1 0 obj\n<< >>\nendobj\n"
                            .getBytes(StandardCharsets.ISO_8859_1))))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(jsonPath("$.detail").value(containsString("application/pdf")));
  }

  @Test
  void refuseUnFichierVide() throws Exception {
    mockMvc
        .perform(multipart("/api/anonymisations/word").file(upload("file", new byte[0])))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value("Fichier vide."));
  }

  @Test
  void refuseUnDocumentSansTexte() throws Exception {
    try (XWPFDocument empty = new XWPFDocument()) {
      byte[] content = save(empty);
      for (String route : new String[] {"/api/verifications/word", "/api/anonymisations/word"}) {
        mockMvc
            .perform(multipart(route).file(upload("file", content)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.detail").value(containsString("Aucun texte")));
      }
    }
  }

  @Test
  void refuseUnDocumentDontLeXmlEstCasse() throws Exception {
    byte[] broken = replaceEntry(fixture("docx"), "word/document.xml", "<w:document><w:body>");

    mockMvc
        .perform(multipart("/api/anonymisations/word").file(upload("file", broken)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.detail").value(containsString("illisible")));
  }

  @Test
  void refuseDeLivrerUnDocumentOuUneValeurRemplaceeSubsisteDansUneZoneNonReecrite()
      throws Exception {
    // Un .docx incorporé dans le document : Tika le lit, la réécriture ne le touche pas. Il
    // contient la même adresse que le corps, que l'on a pourtant remplacée.
    byte[] withEmbedded =
        rewriteZip(
            docxWithText("Contact : marie.martin@example.fr"),
            parts -> {
              parts.computeIfPresent(
                  "word/_rels/document.xml.rels",
                  (name, xml) ->
                      replace(
                          xml,
                          "</Relationships>",
                          "<Relationship Id=\"rIdEmb\" Type=\"http://schemas.openxmlformats.org/"
                              + "officeDocument/2006/relationships/package\""
                              + " Target=\"embeddings/Document1.docx\"/></Relationships>"));
              parts.computeIfPresent(
                  "[Content_Types].xml",
                  (name, xml) ->
                      replace(
                          xml,
                          "<Default ",
                          "<Default Extension=\"docx\" ContentType=\"" + DOCX + "\"/><Default "));
              try {
                parts.put("word/embeddings/Document1.docx", fixture("docx"));
              } catch (IOException e) {
                throw new UncheckedIOException(e);
              }
            });

    mockMvc
        .perform(multipart("/api/anonymisations/word").file(upload("file", withEmbedded)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.detail").value(containsString("incomplète")));
  }

  @Test
  void refuseUnDocumentProtegeParMotDePasse() throws Exception {
    mockMvc
        .perform(
            multipart("/api/anonymisations/word")
                .file(upload("file", encrypted(docxWithText("Secret")))))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.detail").value(containsString("mot de passe")));
  }

  private static byte[] replace(byte[] xml, String target, String replacement) {
    return new String(xml, StandardCharsets.UTF_8)
        .replace(target, replacement)
        .getBytes(StandardCharsets.UTF_8);
  }

  /** Relit un .docx comme une table « nom de partie → octets », la retouche, et le réécrit. */
  private static byte[] rewriteZip(byte[] docx, Consumer<Map<String, byte[]>> edit)
      throws IOException {
    Map<String, byte[]> parts = new LinkedHashMap<>();
    try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(docx))) {
      for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
        parts.put(entry.getName(), in.readAllBytes());
      }
    }
    edit.accept(parts);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(output)) {
      for (Map.Entry<String, byte[]> part : parts.entrySet()) {
        zip.putNextEntry(new ZipEntry(part.getKey()));
        zip.write(part.getValue());
        zip.closeEntry();
      }
    }
    return output.toByteArray();
  }

  private static byte[] replaceEntry(byte[] docx, String name, String content) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(docx));
        ZipOutputStream zip = new ZipOutputStream(output)) {
      for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
        zip.putNextEntry(new ZipEntry(entry.getName()));
        zip.write(
            entry.getName().equals(name)
                ? content.getBytes(StandardCharsets.UTF_8)
                : in.readAllBytes());
        zip.closeEntry();
      }
    }
    return output.toByteArray();
  }

  private static byte[] encrypted(byte[] docx) throws Exception {
    POIFSFileSystem fileSystem = new POIFSFileSystem();
    Encryptor encryptor = new EncryptionInfo(EncryptionMode.agile).getEncryptor();
    encryptor.confirmPassword("secret");
    try (OPCPackage pack = OPCPackage.open(new ByteArrayInputStream(docx));
        OutputStream out = encryptor.getDataStream(fileSystem)) {
      pack.save(out);
    }
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    fileSystem.writeFilesystem(output);
    return output.toByteArray();
  }
}
