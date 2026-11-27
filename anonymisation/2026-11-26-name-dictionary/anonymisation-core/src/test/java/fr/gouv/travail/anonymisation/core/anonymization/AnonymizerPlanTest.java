package fr.gouv.travail.anonymisation.core.anonymization;

import static org.assertj.core.api.Assertions.assertThat;

import fr.gouv.travail.anonymisation.core.detection.EntityDetector;
import fr.gouv.travail.anonymisation.core.detection.EntityRecognizer;
import fr.gouv.travail.anonymisation.core.detection.FrenchRecognizers;
import fr.gouv.travail.anonymisation.core.model.DetectedEntity;
import fr.gouv.travail.anonymisation.core.model.EntityType;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnonymizerPlanTest {

  /** Ne voit que la première occurrence : simule un NER qui rate certaines mentions. */
  private record FirstOccurrenceOnly(EntityType type, String term) implements EntityRecognizer {
    @Override
    public String name() {
      return "first-occurrence";
    }

    @Override
    public boolean contextual() {
      return true;
    }

    @Override
    public List<DetectedEntity> recognize(String text) {
      int index = text.indexOf(term);
      return index < 0
          ? List.of()
          : List.of(new DetectedEntity(type, index, index + term.length(), term, 0.9, name()));
    }
  }

  private static Anonymizer anonymizer(EntityRecognizer... extra) {
    List<EntityRecognizer> recognizers = new ArrayList<>(FrenchRecognizers.defaults());
    recognizers.addAll(List.of(extra));
    return new Anonymizer(new EntityDetector(recognizers, EnumSet.allOf(EntityType.class), 0.5));
  }

  private static List<String> apply(AnonymizationPlan plan, List<String> fragments) {
    List<String> result = new ArrayList<>();
    for (int i = 0; i < fragments.size(); i++) {
      result.add(plan.apply(i, fragments.get(i)));
    }
    return result;
  }

  @Test
  void numeroteEnContinuSurToutLeDocument() {
    List<String> fragments =
        List.of("Écrire à a@example.fr.", "Puis à b@example.fr.", "Rappel : a@example.fr.");

    AnonymizationPlan plan = anonymizer().plan(fragments);

    assertThat(apply(plan, fragments))
        .containsExactly("Écrire à [EMAIL_1].", "Puis à [EMAIL_2].", "Rappel : [EMAIL_1].");
    assertThat(plan.counts()).containsEntry(EntityType.EMAIL, 3);
    assertThat(plan.total()).isEqualTo(3);
  }

  @Test
  void remplacePartoutUneValeurRepereeSeulementUneFois() {
    List<String> fragments =
        List.of("Le dossier de Jean Dupont.", "JEAN DUPONT a signé.", "Contact : Jean  Dupont.");

    AnonymizationPlan plan =
        anonymizer(new FirstOccurrenceOnly(EntityType.PERSONNE, "Jean Dupont")).plan(fragments);

    assertThat(apply(plan, fragments))
        .containsExactly(
            "Le dossier de [PERSONNE_1].", "[PERSONNE_1] a signé.", "Contact : [PERSONNE_1].");
    assertThat(plan.counts()).containsEntry(EntityType.PERSONNE, 3);
  }

  @Test
  void retrouveUnEmailSansTenirCompteDeLaCasse() {
    List<String> fragments = List.of("Écrire à jean@example.fr.", "Ou à JEAN@EXAMPLE.FR.");

    AnonymizationPlan plan = anonymizer().plan(fragments);

    assertThat(apply(plan, fragments)).containsExactly("Écrire à [EMAIL_1].", "Ou à [EMAIL_1].");
  }

  @Test
  void neRemplacePasEnMinusculesUnNomDetecteAilleurs() {
    List<String> fragments = List.of("Pour Jean, c'est oui.", "Pour moi, pour toi.");

    AnonymizationPlan plan =
        anonymizer(new FirstOccurrenceOnly(EntityType.ORGANISATION, "Pour")).plan(fragments);

    assertThat(apply(plan, fragments))
        .containsExactly("[ORGANISATION_1] Jean, c'est oui.", "[ORGANISATION_1] moi, pour toi.");
  }

  @Test
  void unMarqueurNEstPasUneValeurConnue() {
    List<String> fragments =
        List.of("Le mot Personne est un faux positif.", "Déjà anonymisé : [PERSONNE_1].");

    AnonymizationPlan plan =
        anonymizer(new FirstOccurrenceOnly(EntityType.PERSONNE, "Personne")).plan(fragments);

    assertThat(apply(plan, fragments))
        .containsExactly(
            "Le mot [PERSONNE_1] est un faux positif.", "Déjà anonymisé : [PERSONNE_1].");
    assertThat(plan.knownValues().find("Texte [PERSONNE_2] seul.")).isEmpty();
  }

  @Test
  void ignoreLesValeursTropCourtes() {
    List<String> fragments = List.of("Dr Al est là.", "Al repart.");

    AnonymizationPlan plan =
        anonymizer(new FirstOccurrenceOnly(EntityType.PERSONNE, "Al")).plan(fragments);

    assertThat(apply(plan, fragments)).containsExactly("Dr [PERSONNE_1] est là.", "Al repart.");
  }

  @Test
  void coupeUneEntiteAChevalSurDeuxFragments() {
    List<String> fragments = List.of("Monsieur Jean", "Dupont habite ici.");

    AnonymizationPlan plan =
        anonymizer(new FirstOccurrenceOnly(EntityType.PERSONNE, "Jean\nDupont")).plan(fragments);

    assertThat(apply(plan, fragments))
        .containsExactly("Monsieur [PERSONNE_1]", "[PERSONNE_2] habite ici.");
  }

  @Test
  void unPrenomSeulNEstPasRemplaceParLaValeurConnueDuNomComplet() {
    List<String> fragments = List.of("Jean Dupont, Jean Dupont.", "Jean");

    AnonymizationPlan plan =
        anonymizer(new FirstOccurrenceOnly(EntityType.PERSONNE, "Jean Dupont")).plan(fragments);

    assertThat(apply(plan, fragments)).containsExactly("[PERSONNE_1], [PERSONNE_1].", "Jean");
  }

  @Test
  void leDocumentVideNEstPasModifie() {
    AnonymizationPlan plan = anonymizer().plan(List.of());

    assertThat(plan.replacements()).isEmpty();
    assertThat(plan.total()).isZero();
  }

  @Test
  void leControleDesValeursConnuesRetrouveCeQuiSubsiste() {
    List<String> fragments = List.of("Écrire à jean@example.fr.");
    AnonymizationPlan plan = anonymizer().plan(fragments);

    assertThat(plan.knownValues().find("Il reste Jean@Example.fr ici.")).hasSize(1);
    assertThat(plan.knownValues().find(plan.apply(0, fragments.get(0)))).isEmpty();
    assertThat(plan.knownValues().toString()).doesNotContain("jean");
  }
}
