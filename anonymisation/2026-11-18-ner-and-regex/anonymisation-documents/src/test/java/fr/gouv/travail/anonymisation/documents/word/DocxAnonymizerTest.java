package fr.gouv.travail.anonymisation.documents.word;

import static fr.gouv.travail.anonymisation.documents.word.DocxTestSupport.open;
import static fr.gouv.travail.anonymisation.documents.word.DocxTestSupport.part;
import static fr.gouv.travail.anonymisation.documents.word.DocxTestSupport.parts;
import static fr.gouv.travail.anonymisation.documents.word.DocxTestSupport.save;
import static fr.gouv.travail.anonymisation.documents.word.DocxTestSupport.withPart;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.gouv.travail.anonymisation.documents.InvalidDocumentException;
import fr.gouv.travail.anonymisation.documents.testsupport.Detectors;
import fr.gouv.travail.anonymisation.documents.testsupport.TikaText;
import fr.gouv.travail.anonymisation.documents.word.DocxAnonymizer.Result;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.apache.poi.wp.usermodel.HeaderFooterType;
import org.apache.poi.xwpf.usermodel.XWPFComment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFFootnote;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.Test;

class DocxAnonymizerTest {

  private final DocxAnonymizer docxAnonymizer = new DocxAnonymizer();

  private Result anonymize(byte[] docx) {
    return docxAnonymizer.anonymize(docx, Detectors.accordAnonymizer());
  }

  @Test
  void anonymiseUnDocxProduitParUnTraitementDeTexte() throws IOException {
    byte[] content = Files.readAllBytes(Path.of("src/test/resources/fixtures/accord.docx"));

    Result result = anonymize(content);

    TikaText.Extracted extracted = TikaText.extract(result.content());
    assertThat(extracted.text())
        .doesNotContain("Dupont", "Martin", "marie.martin", "06 12", "FR76", "Lyon")
        .contains("[PERSONNE_1]", "[PERSONNE_2]", "[EMAIL_1]", "[TELEPHONE_1]", "[IBAN_1]")
        .contains("Accord sur le temps de travail", "Fait à", "[LIEU_1]");
    // En-tête, pied de page, tableau et note de bas de page sont passés par le même chemin.
    assertThat(extracted.text()).contains("Accord confidentiel – Société [ORGANISATION_1]");
    assertThat(extracted.metadata())
        .doesNotContainKeys(TikaText.AUTHOR, TikaText.LAST_AUTHOR)
        .containsEntry(TikaText.TITLE, "Accord d'entreprise [ORGANISATION_1]");
    open(result.content()).close();
  }

  @Test
  void retrouveUnNomCoupeEnPlusieursRunsEtGardeLaMiseEnForme() throws IOException {
    XWPFDocument document = new XWPFDocument();
    XWPFParagraph paragraph = document.createParagraph();
    XWPFRun first = paragraph.createRun();
    first.setBold(true);
    first.setText("Jean");
    paragraph.createRun().setText(" Dupont habite à Lyon.");

    Result result = anonymize(save(document));

    String xml = part(result.content(), "word/document.xml");
    assertThat(xml).contains("<w:b w:val=\"on\"/>").doesNotContain("Dupont");
    assertThat(TikaText.extract(result.content()).text())
        .contains("[PERSONNE_1] habite à [LIEU_1].");
    try (XWPFDocument reopened = open(result.content())) {
      XWPFParagraph out = reopened.getParagraphs().get(0);
      assertThat(out.getRuns().get(0).isBold()).isTrue();
      assertThat(out.getRuns().get(0).text()).isEqualTo("[PERSONNE_1]");
      assertThat(out.getText()).isEqualTo("[PERSONNE_1] habite à [LIEU_1].");
    }
  }

  @Test
  void traiteTableauxEnTetesPiedsDePageNotesEtCommentaires() throws IOException {
    XWPFDocument document = new XWPFDocument();
    XWPFRun body = document.createParagraph().createRun();
    body.setText("Corps du texte.");
    document.createTable(1, 2).getRow(0).getCell(1).setText("Délégué : Marie Martin");
    document
        .createHeader(HeaderFooterType.DEFAULT)
        .createParagraph()
        .createRun()
        .setText("Jean Dupont");
    document
        .createFooter(HeaderFooterType.DEFAULT)
        .createParagraph()
        .createRun()
        .setText("marie.martin@example.fr");
    XWPFFootnote footnote = document.createFootnote();
    footnote.createParagraph().createRun().setText("Voir Jean Dupont.");
    body.getCTR().addNewFootnoteReference().setId(footnote.getId());
    XWPFComment comment = document.createComments().createComment(BigInteger.ZERO);
    comment.setAuthor("Marie Martin");
    comment.createParagraph().createRun().setText("À relire par Jean Dupont");

    Result result = anonymize(save(document));

    Map<String, String> parts = parts(result.content());
    assertThat(parts.values()).noneMatch(xml -> xml.contains("Dupont") || xml.contains("Martin"));
    assertThat(parts.values()).noneMatch(xml -> xml.contains("example.fr"));
    assertThat(parts.get("word/comments.xml")).contains("w:author=\"Anonyme\"");
    assertThat(result.plan().total()).isEqualTo(5);
    open(result.content()).close();
  }

  @Test
  void effaceCreateurDernierAuteurSocieteEtAnonymiseLeTitre() throws IOException {
    XWPFDocument document = new XWPFDocument();
    document.createParagraph().createRun().setText("Texte.");
    document.getProperties().getCoreProperties().setCreator("Jean Dupont");
    document.getProperties().getCoreProperties().setLastModifiedByUser("Marie Martin");
    document.getProperties().getCoreProperties().setTitle("Dossier Jean Dupont");
    document
        .getProperties()
        .getExtendedProperties()
        .getUnderlyingProperties()
        .setCompany("Dupont Frères");

    Result result = anonymize(save(document));

    TikaText.Extracted extracted = TikaText.extract(result.content());
    assertThat(extracted.metadata())
        .doesNotContainKeys(TikaText.AUTHOR, TikaText.LAST_AUTHOR, TikaText.COMPANY);
    assertThat(extracted.metadata().get(TikaText.TITLE)).isEqualTo("Dossier [PERSONNE_1]");
  }

  @Test
  void traiteZonesDeTexteChampsLiensTexteSupprimeEtRevisions() throws IOException {
    XWPFDocument document = new XWPFDocument();
    document.createParagraph().createRun().setText("Début.");
    byte[] docx = save(document);
    String injected =
        """
        <w:p><w:r><w:t>Zone : </w:t></w:r><w:r><mc:AlternateContent \
        xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006"><mc:Choice Requires="wps"><w:txbxContent>\
        <w:p><w:r><w:t>Chez Jean Dupont</w:t></w:r></w:p></w:txbxContent></mc:Choice><mc:Fallback><w:txbxContent>\
        <w:p><w:r><w:t>Chez Jean Dupont</w:t></w:r></w:p></w:txbxContent></mc:Fallback></mc:AlternateContent></w:r></w:p>\
        <w:p><w:r><w:fldChar w:fldCharType="begin"/></w:r><w:r><w:instrText xml:space="preserve"> HYPERLINK "mailto:jean@example.fr" </w:instrText></w:r>\
        <w:r><w:fldChar w:fldCharType="separate"/></w:r><w:r><w:t>jean@example.fr</w:t></w:r><w:r><w:fldChar w:fldCharType="end"/></w:r></w:p>\
        <w:p><w:r><w:t>Gardé. </w:t></w:r><w:del w:id="1" w:author="Marie Martin" w:date="2024-01-01T00:00:00Z"><w:r><w:delText>Supprimé : Jean Dupont</w:delText></w:r></w:del>\
        <w:ins w:id="2" w:author="Jean Dupont" w:date="2024-01-01T00:00:00Z"><w:r><w:t>Ajouté</w:t></w:r></w:ins></w:p>\
        <w:p><w:r><w:t>Jean</w:t></w:r><w:r><w:tab/></w:r><w:r><w:t>Dupont</w:t></w:r></w:p>\
        """;
    byte[] edited =
        withPart(docx, "word/document.xml", xml -> xml.replace("<w:body>", "<w:body>" + injected));

    Result result = anonymize(edited);

    String xml = part(result.content(), "word/document.xml");
    assertThat(xml)
        .doesNotContain("Dupont", "Martin", "example.fr")
        .contains("Chez [PERSONNE_1]")
        .contains("HYPERLINK \"mailto:[EMAIL_1]\"")
        .contains("Supprimé : [PERSONNE_1]")
        .contains("w:author=\"Anonyme\"")
        .contains("Gardé. ");
    // « Jean <tab> Dupont » : nom déjà connu ailleurs dans le document, retrouvé malgré la
    // tabulation
    // (conservée) et les deux runs.
    assertThat(xml).contains("[PERSONNE_1]</w:t></w:r><w:r><w:tab/></w:r>");
    assertThat(xml).contains("standalone=\"yes\"");
    open(result.content()).close();
  }

  @Test
  void neutraliseLesLiensExternesQuiContiennentUneDonneePersonnelle() throws IOException {
    XWPFDocument document = new XWPFDocument();
    XWPFParagraph paragraph = document.createParagraph();
    paragraph.createHyperlinkRun("mailto:jean.dupont@example.fr").setText("écrire");
    document.createParagraph().createHyperlinkRun("https://www.example.org/").setText("site");

    Result result = anonymize(save(document));

    String rels = part(result.content(), "word/_rels/document.xml.rels");
    assertThat(rels)
        .doesNotContain("jean.dupont")
        .contains("about:blank")
        .contains("https://www.example.org/");
    open(result.content()).close();
  }

  @Test
  void retireLaVignetteEtLesSignaturesNumeriques() throws IOException {
    XWPFDocument document = new XWPFDocument();
    document.createParagraph().createRun().setText("Texte.");
    byte[] docx = save(document);
    docx = withPart(docx, "docProps/thumbnail.jpeg", x -> "binaire");
    docx = withPart(docx, "_xmlsignatures/sig1.xml", x -> "<Signature>Jean Dupont</Signature>");
    docx =
        withPart(
            docx,
            "_rels/.rels",
            xml ->
                xml.replace(
                    "</Relationships>",
                    "<Relationship Id=\"rIdT\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/thumbnail\" Target=\"docProps/thumbnail.jpeg\"/></Relationships>"));

    Result result = anonymize(docx);

    assertThat(parts(result.content()).keySet())
        .noneMatch(name -> name.contains("thumbnail") || name.contains("_xmlsignatures"));
    assertThat(part(result.content(), "_rels/.rels")).doesNotContain("thumbnail");
    open(result.content()).close();
  }

  @Test
  void leDocumentSansEntiteResteLisible() throws IOException {
    XWPFDocument document = new XWPFDocument();
    document.createParagraph().createRun().setText("Rien à signaler.");

    Result result = anonymize(save(document));

    assertThat(result.plan().total()).isZero();
    assertThat(TikaText.extract(result.content()).text()).contains("Rien à signaler.");
  }

  @Test
  void refuseUnXmlQuiDeclareUneDtd() throws IOException {
    XWPFDocument document = new XWPFDocument();
    document.createParagraph().createRun().setText("Texte.");
    byte[] hostile =
        withPart(
            save(document),
            "word/document.xml",
            xml ->
                "<?xml version=\"1.0\"?><!DOCTYPE d [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                    + xml.substring(xml.indexOf("<w:document")));

    assertThatThrownBy(() -> anonymize(hostile)).isInstanceOf(InvalidDocumentException.class);
  }

  @Test
  void refuseUnFichierQuiNEstPasUnDocx() {
    assertThatThrownBy(() -> anonymize("pas un docx".getBytes()))
        .isInstanceOf(InvalidDocumentException.class);
    byte[] zipWithoutBody = DocxTestSupport.zip(Map.of("autre.xml", "<a/>"));
    assertThatThrownBy(() -> anonymize(zipWithoutBody))
        .isInstanceOf(InvalidDocumentException.class)
        .hasMessageContaining("word/document.xml");
  }
}
