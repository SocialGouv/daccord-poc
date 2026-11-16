package fr.gouv.travail.anonymisation.documents.word;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.gouv.travail.anonymisation.documents.InvalidDocumentException;
import fr.gouv.travail.anonymisation.documents.testsupport.Detectors;
import fr.gouv.travail.anonymisation.documents.testsupport.TikaText;
import fr.gouv.travail.anonymisation.documents.word.DocAnonymizer.Result;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.poi.hwpf.HWPFDocument;
import org.junit.jupiter.api.Test;

class DocAnonymizerTest {

  private final DocAnonymizer docAnonymizer = new DocAnonymizer();

  @Test
  void anonymiseUnDocProduitParUnTraitementDeTexte() throws IOException {
    byte[] content = Files.readAllBytes(Path.of("src/test/resources/fixtures/accord.doc"));

    Result result = docAnonymizer.anonymize(content, Detectors.accordAnonymizer());

    // Les valeurs sont remplacées caractère pour caractère : « Dupont Frères » (13) donne 13 « * ».
    TikaText.Extracted extracted = TikaText.extract(result.content());
    assertThat(extracted.text())
        .doesNotContain("Dupont", "Martin", "marie.martin", "06 12", "FR76", "Lyon")
        .contains("Accord sur le temps de travail", "Fait à ****, le 12 mars.")
        .contains("Accord confidentiel – Société *************\n")
        .contains("Contact : ************, **********************")
        .contains("Voir la lettre de ***********");
    assertThat(extracted.metadata())
        .doesNotContainKeys(TikaText.AUTHOR, TikaText.LAST_AUTHOR)
        .containsEntry(TikaText.TITLE, "Accord d'entreprise [ORGANISATION_1]");
    assertThat(result.plan().total()).isGreaterThanOrEqualTo(12);

    // La structure du fichier n'a pas bougé : mêmes longueurs, même séparation en-tête / pied de
    // page.
    TikaText.Extracted original = TikaText.extract(content);
    assertThat(extracted.text()).hasSameSizeAs(original.text());
    assertThat(lengths(result.content())).containsExactlyElementsOf(lengths(content));
    new HWPFDocument(new ByteArrayInputStream(result.content())).close();
  }

  /** Longueur de chaque zone du document : elles ne doivent pas changer. */
  private static List<Integer> lengths(byte[] content) throws IOException {
    try (HWPFDocument document = new HWPFDocument(new ByteArrayInputStream(content))) {
      return List.of(
          document.getRange().text().length(),
          document.getFootnoteRange().text().length(),
          document.getHeaderStoryRange().text().length(),
          document.getOverallRange().text().length());
    }
  }

  @Test
  void refuseUnFichierQuiNEstPasUnDoc() {
    assertThatThrownBy(
            () -> docAnonymizer.anonymize("pas un doc".getBytes(), Detectors.accordAnonymizer()))
        .isInstanceOf(InvalidDocumentException.class);
  }
}
