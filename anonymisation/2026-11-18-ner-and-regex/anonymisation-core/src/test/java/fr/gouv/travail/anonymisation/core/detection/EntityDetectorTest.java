package fr.gouv.travail.anonymisation.core.detection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import fr.gouv.travail.anonymisation.core.model.DetectedEntity;
import fr.gouv.travail.anonymisation.core.model.EntityType;
import fr.gouv.travail.anonymisation.core.testsupport.DictionaryRecognizer;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class EntityDetectorTest {

  private static final EntityRecognizer EMAILS = FrenchRecognizers.defaults().get(0);

  @Test
  void leMoteurListeEnPremierGagneEnCasDeChevauchement() {
    String text = "Écrire à jean.dupont@example.fr";
    var personne = new DictionaryRecognizer(EntityType.PERSONNE, "jean.dupont");
    var detector =
        new EntityDetector(List.of(EMAILS, personne), EnumSet.allOf(EntityType.class), 0.5);

    assertThat(detector.detect(text))
        .extracting(DetectedEntity::type, DetectedEntity::text)
        .containsExactly(tuple(EntityType.EMAIL, "jean.dupont@example.fr"));
  }

  @Test
  void ignoreLesTypesNonDemandes() {
    var lieu = new DictionaryRecognizer(EntityType.LIEU, "Paris");
    var detector = new EntityDetector(List.of(lieu), EnumSet.of(EntityType.PERSONNE), 0.5);

    assertThat(detector.detect("Il habite à Paris")).isEmpty();
  }

  @Test
  void ecarteLesScoresSousLeSeuil() {
    var personne = new DictionaryRecognizer(EntityType.PERSONNE, "Jean");
    var detector = new EntityDetector(List.of(personne), EnumSet.allOf(EntityType.class), 0.95);

    assertThat(detector.detect("Jean")).isEmpty();
  }

  @Test
  void rendLesEntitesParPositionCroissante() {
    var personne = new DictionaryRecognizer(EntityType.PERSONNE, "Marie");
    var detector =
        new EntityDetector(List.of(personne, EMAILS), EnumSet.allOf(EntityType.class), 0.5);

    List<DetectedEntity> entities = detector.detect("a@b.fr puis Marie puis c@d.fr");

    assertThat(entities).extracting(DetectedEntity::start).isSorted().hasSize(3);
  }

  @Test
  void ditSiUnMoteurContextuelEstPresent() {
    var personne = new DictionaryRecognizer(EntityType.PERSONNE, "Jean");

    assertThat(
            new EntityDetector(List.of(EMAILS), EnumSet.allOf(EntityType.class), 0.5)
                .hasContextualRecognizer())
        .isFalse();
    assertThat(
            new EntityDetector(List.of(EMAILS, personne), EnumSet.allOf(EntityType.class), 0.5)
                .hasContextualRecognizer())
        .isTrue();
  }
}
