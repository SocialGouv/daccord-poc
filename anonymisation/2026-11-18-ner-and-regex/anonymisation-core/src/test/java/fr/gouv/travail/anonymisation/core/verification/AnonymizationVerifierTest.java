package fr.gouv.travail.anonymisation.core.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import fr.gouv.travail.anonymisation.core.detection.EntityDetector;
import fr.gouv.travail.anonymisation.core.detection.EntityRecognizer;
import fr.gouv.travail.anonymisation.core.detection.FrenchRecognizers;
import fr.gouv.travail.anonymisation.core.model.DetectedEntity;
import fr.gouv.travail.anonymisation.core.model.EntityType;
import fr.gouv.travail.anonymisation.core.testsupport.DictionaryRecognizer;
import fr.gouv.travail.anonymisation.core.verification.VerificationReport.Finding;
import fr.gouv.travail.anonymisation.core.verification.VerificationReport.Kind;
import fr.gouv.travail.anonymisation.core.verification.VerificationReport.Verdict;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnonymizationVerifierTest {

  private final AnonymizationVerifier verifier =
      new AnonymizationVerifier(
          detector(new DictionaryRecognizer(EntityType.PERSONNE, "Jean Dupont", "Hélène Müller")));

  private static EntityDetector detector(EntityRecognizer... extra) {
    List<EntityRecognizer> recognizers = new ArrayList<>(FrenchRecognizers.defaults());
    recognizers.addAll(List.of(extra));
    return new EntityDetector(recognizers, EnumSet.allOf(EntityType.class), 0.5);
  }

  @Test
  void unTexteAvecSeulementDesMarqueursEstConforme() {
    VerificationReport report =
        verifier.verify("[PERSONNE_1] joignable au [TELEPHONE_1] ou à [EMAIL_1].");

    assertThat(report.verdict()).isEqualTo(Verdict.CONFORME);
    assertThat(report.findings()).isEmpty();
    assertThat(report.warnings()).isEmpty();
  }

  @Test
  void signaleUneEntiteResiduelleAvecSaPosition() {
    String text = "[PERSONNE_1] joignable à jean@example.fr";

    VerificationReport report = verifier.verify(text);

    assertThat(report.verdict()).isEqualTo(Verdict.NON_CONFORME);
    assertThat(report.findings())
        .extracting(Finding::kind, Finding::type, Finding::start, Finding::text)
        .containsExactly(
            tuple(
                Kind.RESIDUAL_ENTITY, EntityType.EMAIL, text.indexOf("jean@"), "jean@example.fr"));
  }

  @Test
  void retrouveUneValeurDeLOriginalSansTenirCompteDeLaCasse() {
    String original = "Jean Dupont habite Lyon.";
    String anonymized = "[PERSONNE_1] habite Lyon. Contact : JEAN DUPONT";

    VerificationReport report = verifier.verify(original, anonymized);

    assertThat(report.verdict()).isEqualTo(Verdict.NON_CONFORME);
    assertThat(report.findings())
        .extracting(Finding::kind, Finding::text)
        .containsExactly(tuple(Kind.ORIGINAL_VALUE_LEAK, "JEAN DUPONT"));
  }

  @Test
  void retrouveUneValeurDeLOriginalSansTenirCompteDesAccents() {
    VerificationReport report =
        verifier.verify("Hélène Müller est venue.", "Dossier de helene muller.");

    assertThat(report.findings())
        .extracting(Finding::kind, Finding::text)
        .containsExactly(tuple(Kind.ORIGINAL_VALUE_LEAK, "helene muller"));
  }

  @Test
  void unMotQuiNEstQueLePrefixeDUnAutreNEstPasUneFuite() {
    var verifier =
        new AnonymizationVerifier(detector(new DictionaryRecognizer(EntityType.PERSONNE, "Jean")));

    VerificationReport report = verifier.verify("Jean est là.", "[PERSONNE_1] parle à Jeanne.");

    assertThat(report.verdict()).isEqualTo(Verdict.CONFORME);
  }

  @Test
  void unFichierOriginalPropreValideUnTexteAnonymise() {
    VerificationReport report =
        verifier.verify("Jean Dupont : 06 12 34 56 78", "[PERSONNE_1] : [TELEPHONE_1]");

    assertThat(report.verdict()).isEqualTo(Verdict.CONFORME);
  }

  @Test
  void previentQuandAucunMoteurContextuelNEstActif() {
    var regexOnly = new AnonymizationVerifier(detector());

    VerificationReport report = regexOnly.verify("Aucun problème visible");

    assertThat(report.verdict()).isEqualTo(Verdict.CONFORME);
    assertThat(report.warnings()).singleElement().asString().contains("Aucun moteur NER actif");
  }

  /** Un NER qui, comme CamemBERT, traite un marqueur ou ses voisins comme une entité. */
  private record Tagging(EntityType type, String span) implements EntityRecognizer {
    @Override
    public String name() {
      return "tagging";
    }

    @Override
    public boolean contextual() {
      return true;
    }

    @Override
    public List<DetectedEntity> recognize(String text) {
      int index = text.indexOf(span);
      return index < 0
          ? List.of()
          : List.of(new DetectedEntity(type, index, index + span.length(), span, 0.9, name()));
    }
  }

  private static AnonymizationVerifier verifierTagging(EntityType type, String span) {
    return new AnonymizationVerifier(detector(new Tagging(type, span)));
  }

  @Test
  void ignoreUneEntiteQuiNEstQueUnMarqueur() {
    assertThat(
            verifierTagging(EntityType.PERSONNE, "[PERSONNE_1]")
                .verify("Dossier [PERSONNE_1].")
                .verdict())
        .isEqualTo(Verdict.CONFORME);
    // Les .doc anonymisés portent des astérisques de même longueur que la valeur remplacée.
    assertThat(
            verifierTagging(EntityType.PERSONNE, "***********")
                .verify("Dossier ***********.")
                .verdict())
        .isEqualTo(Verdict.CONFORME);
  }

  @Test
  void gardeCeQuiDepasseUnMarqueurDansLaMemeEntite() {
    String text = "Signé [PERSONNE_1] Dupont, le 3 mars.";

    VerificationReport report =
        verifierTagging(EntityType.PERSONNE, "[PERSONNE_1] Dupont").verify(text);

    assertThat(report.findings())
        .extracting(Finding::type, Finding::start, Finding::end, Finding::text)
        .containsExactly(
            tuple(
                EntityType.PERSONNE, text.indexOf("Dupont"), text.indexOf("Dupont") + 6, "Dupont"));
  }

  @Test
  void neTouchePasUneEntiteQuiNeRencontreAucunMarqueur() {
    String text = "Dupont habite [LIEU_1].";

    VerificationReport report = verifierTagging(EntityType.PERSONNE, "Dupont").verify(text);

    assertThat(report.findings()).extracting(Finding::text).containsExactly("Dupont");
  }
}
