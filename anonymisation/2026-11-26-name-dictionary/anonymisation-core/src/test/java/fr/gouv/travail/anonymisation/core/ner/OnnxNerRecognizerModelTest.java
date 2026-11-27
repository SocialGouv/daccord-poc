package fr.gouv.travail.anonymisation.core.ner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import fr.gouv.travail.anonymisation.core.model.DetectedEntity;
import fr.gouv.travail.anonymisation.core.model.EntityType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Tests sur le vrai modèle. Ignorés tant que {@code scripts/download-model.sh} n'a pas été exécuté
 * (répertoire {@code models/camembert-ner} à la racine du projet).
 *
 * <p>Ils visent le modèle complet. La version quantifiée int8 les ferait échouer : voir {@link
 * #reperePrenomsCourantsEtNomsCompletsRatesParLeModeleQuantifie()}.
 */
class OnnxNerRecognizerModelTest {

  private static final Path MODEL_DIR =
      Path.of(System.getProperty("anonymisation.model-dir", "../models/camembert-ner"));
  private static final String MODEL_FILE =
      System.getProperty("anonymisation.model-file", "onnx/model.onnx");

  static boolean modelPresent() {
    return Files.isReadable(MODEL_DIR.resolve(MODEL_FILE))
        && Files.isReadable(MODEL_DIR.resolve("tokenizer.json"))
        && Files.isReadable(MODEL_DIR.resolve("config.json"));
  }

  @Test
  void expliqueCommentObtenirLeModeleQuandIlEstAbsent() {
    assertThatThrownBy(() -> new OnnxNerRecognizer(Path.of("/inexistant"), MODEL_FILE, 1200))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("download-model.sh");
  }

  /**
   * Garde-fou contre un retour au modèle quantifié : mesuré avec {@code model_quantized.onnx}, «
   * Jean », « Marie » et « Pierre » en tête de texte sont classés hors entité (« Jean [PERSONNE_1]
   * ») et « Nicolas Martin » passe en entier.
   */
  @Test
  @EnabledIf("modelPresent")
  void reperePrenomsCourantsEtNomsCompletsRatesParLeModeleQuantifie() {
    try (var recognizer = new OnnxNerRecognizer(MODEL_DIR, MODEL_FILE, 1200)) {
      assertThat(personnes(recognizer, "Jean Dupont habite à Lyon.")).contains("Jean Dupont");
      assertThat(personnes(recognizer, "Marie Martin a écrit au directeur."))
          .contains("Marie Martin");
      assertThat(personnes(recognizer, "Pierre Moreau, 45 ans, habite à Nantes."))
          .contains("Pierre Moreau");
      assertThat(
              personnes(
                  recognizer,
                  "Nicolas Martin, domicilié 3 avenue Victor Hugo à Rennes, conteste la décision."))
          .contains("Nicolas Martin");
    }
  }

  private static List<String> personnes(OnnxNerRecognizer recognizer, String text) {
    return recognizer.recognize(text).stream()
        .filter(entity -> entity.type() == EntityType.PERSONNE)
        .map(DetectedEntity::text)
        .toList();
  }

  @Test
  @EnabledIf("modelPresent")
  void reperePersonnesLieuxEtOrganisations() {
    String text =
        "Le dossier de Jean Dupont a été transmis. "
            + "Il habite à Lyon et travaille pour la Caisse d'Allocations Familiales.";

    try (var recognizer = new OnnxNerRecognizer(MODEL_DIR, MODEL_FILE, 1200)) {
      List<DetectedEntity> entities = recognizer.recognize(text);

      assertThat(entities)
          .extracting(DetectedEntity::type, DetectedEntity::text)
          .contains(
              tuple(EntityType.PERSONNE, "Jean Dupont"),
              tuple(EntityType.LIEU, "Lyon"),
              tuple(EntityType.ORGANISATION, "Caisse d'Allocations Familiales"));
      assertThat(entities)
          .allSatisfy(e -> assertThat(text.substring(e.start(), e.end())).isEqualTo(e.text()));
    }
  }

  @Test
  @EnabledIf("modelPresent")
  void gardeLesOffsetsExactsAvecAccentsEtMajuscules() {
    String text = "Monsieur MÜLLER Hélène, née à Besançon, a écrit à Fatima Benali.";

    try (var recognizer = new OnnxNerRecognizer(MODEL_DIR, MODEL_FILE, 1200)) {
      List<DetectedEntity> entities = recognizer.recognize(text);

      assertThat(entities).isNotEmpty();
      assertThat(entities)
          .allSatisfy(e -> assertThat(text.substring(e.start(), e.end())).isEqualTo(e.text()));
      assertThat(entities).extracting(DetectedEntity::text).contains("Fatima Benali", "Besançon");
    }
  }

  @Test
  @EnabledIf("modelPresent")
  void gardeDesOffsetsExactsSurUnTexteDecoupeEnPlusieursMorceaux() {
    String paragraph = "Madame Marie Martin habite à Toulouse. Elle a écrit à Paul Durand.\n";
    String text = paragraph.repeat(60);

    try (var recognizer = new OnnxNerRecognizer(MODEL_DIR, MODEL_FILE, 500)) {
      List<DetectedEntity> entities = recognizer.recognize(text);

      assertThat(entities).hasSizeGreaterThan(60);
      assertThat(entities)
          .allSatisfy(e -> assertThat(text.substring(e.start(), e.end())).isEqualTo(e.text()));
    }
  }
}
