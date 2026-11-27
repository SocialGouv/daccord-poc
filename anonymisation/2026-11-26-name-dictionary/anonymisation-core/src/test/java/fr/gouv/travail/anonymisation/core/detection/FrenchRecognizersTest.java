package fr.gouv.travail.anonymisation.core.detection;

import static org.assertj.core.api.Assertions.assertThat;

import fr.gouv.travail.anonymisation.core.model.DetectedEntity;
import fr.gouv.travail.anonymisation.core.model.EntityType;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class FrenchRecognizersTest {

  private final EntityDetector detector =
      new EntityDetector(FrenchRecognizers.defaults(), EnumSet.allOf(EntityType.class), 0.5);

  private List<String> texts(String text, EntityType type) {
    return detector.detect(text).stream()
        .filter(entity -> entity.type() == type)
        .map(DetectedEntity::text)
        .toList();
  }

  @Test
  void detecteLesIdentifiantsStructures() {
    String text =
        "Écrire à jean.dupont@example.fr ou appeler le 06 12 34 56 78 (+33 6 12 34 56 79). "
            + "IBAN FR76 3000 6000 0112 3456 7890 189, NIR 1 85 05 78 006 084 91, "
            + "SIRET 356 000 000 00048.";

    assertThat(texts(text, EntityType.EMAIL)).containsExactly("jean.dupont@example.fr");
    assertThat(texts(text, EntityType.TELEPHONE))
        .containsExactly("06 12 34 56 78", "+33 6 12 34 56 79");
    assertThat(texts(text, EntityType.IBAN)).containsExactly("FR76 3000 6000 0112 3456 7890 189");
    assertThat(texts(text, EntityType.NIR)).containsExactly("1 85 05 78 006 084 91");
    assertThat(texts(text, EntityType.SIRET)).containsExactly("356 000 000 00048");
  }

  @Test
  void detecteUnNumeroCoupeEnFinDeLigne() {
    // Texte à lignes courtes : espace de fin de ligne puis retour à la ligne.
    String text =
        "Appeler le 06 12 34 56 \n78 ou verser sur FR76 3000 6000 0112 \n3456 7890 189 "
            + "(SIRET 356 000\n000 00048).";

    assertThat(texts(text, EntityType.TELEPHONE)).containsExactly("06 12 34 56 \n78");
    assertThat(texts(text, EntityType.IBAN)).containsExactly("FR76 3000 6000 0112 \n3456 7890 189");
    assertThat(texts(text, EntityType.SIRET)).containsExactly("356 000\n000 00048");
  }

  @Test
  void uneAdresseAvecDesLettresAccentueesEstDetecteeEnEntier() {
    assertThat(texts("Écrire à hugo.lefèvre@example.fr ou à zoé@café.fr.", EntityType.EMAIL))
        .containsExactly("hugo.lefèvre@example.fr", "zoé@café.fr");
  }

  @Test
  void lePointFinalDeLaPhraseNeFaitPasPartieDeLEmail() {
    assertThat(texts("Contact : jean@example.fr.", EntityType.EMAIL))
        .containsExactly("jean@example.fr");
  }

  @Test
  void unIbanNeSeDecoupePasEnTelephone() {
    List<DetectedEntity> entities = detector.detect("FR76 3000 6000 0112 3456 7890 189");

    assertThat(entities).extracting(DetectedEntity::type).containsExactly(EntityType.IBAN);
  }

  @Test
  void unNirMalSaisiRestePrudemmentSignale() {
    String text = "NIR 1 85 05 78 006 084 36";

    assertThat(texts(text, EntityType.NIR)).containsExactly("1 85 05 78 006 084 36");
    assertThat(
            new EntityDetector(FrenchRecognizers.defaults(), EnumSet.allOf(EntityType.class), 0.8)
                .detect(text))
        .isEmpty();
  }

  @Test
  void ignoreLesNombresQuiNeSontPasDesIdentifiants() {
    String text =
        "Dossier 0123456789012, montant 1 234,56 €, né le 12/03/1985, SIRET 356 000 000 00049.";

    assertThat(detector.detect(text)).isEmpty();
  }
}
