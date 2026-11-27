package fr.gouv.travail.anonymisation.core.anonymization;

import static org.assertj.core.api.Assertions.assertThat;

import fr.gouv.travail.anonymisation.core.detection.EntityDetector;
import fr.gouv.travail.anonymisation.core.detection.EntityRecognizer;
import fr.gouv.travail.anonymisation.core.detection.FrenchRecognizers;
import fr.gouv.travail.anonymisation.core.model.EntityType;
import fr.gouv.travail.anonymisation.core.testsupport.DictionaryRecognizer;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnonymizerTest {

  private final Anonymizer anonymizer = new Anonymizer(detector());

  private static EntityDetector detector() {
    List<EntityRecognizer> recognizers = new ArrayList<>(FrenchRecognizers.defaults());
    recognizers.add(new DictionaryRecognizer(EntityType.PERSONNE, "Jean Dupont", "Marie Martin"));
    return new EntityDetector(recognizers, EnumSet.allOf(EntityType.class), 0.5);
  }

  @Test
  void remplaceChaqueEntiteParUnMarqueurNumerote() {
    String text =
        "Jean Dupont (jean.dupont@example.fr, 06 12 34 56 78) écrit à Marie Martin. "
            + "jean dupont rappellera le 0612345678.";

    AnonymizationResult result = anonymizer.anonymize(text);

    assertThat(result.text())
        .isEqualTo(
            "[PERSONNE_1] ([EMAIL_1], [TELEPHONE_1]) écrit à [PERSONNE_2]. "
                + "[PERSONNE_1] rappellera le [TELEPHONE_1].");
    assertThat(result.replacements())
        .containsEntry(EntityType.PERSONNE, 3)
        .containsEntry(EntityType.EMAIL, 1)
        .containsEntry(EntityType.TELEPHONE, 2);
    assertThat(result.engines()).contains("regex-email", "dictionary");
  }

  @Test
  void neTouchePasUnTexteSansEntite() {
    AnonymizationResult result = anonymizer.anonymize("Rien à signaler ici.");

    assertThat(result.text()).isEqualTo("Rien à signaler ici.");
    assertThat(result.replacements()).isEmpty();
  }

  @Test
  void leResultatNeContientAucuneValeurDOrigine() {
    AnonymizationResult result = anonymizer.anonymize("Marie Martin : marie.martin@example.fr");

    assertThat(result.text()).doesNotContain("Marie", "Martin", "example.fr");
    assertThat(result.replacements().toString()).doesNotContain("Marie");
  }

  @Test
  void reanonymiserUnTexteDejaAnonymiseNeChangeRien() {
    String once = anonymizer.anonymize("Marie Martin : 06 12 34 56 78").text();

    assertThat(anonymizer.anonymize(once).text()).isEqualTo(once);
  }
}
