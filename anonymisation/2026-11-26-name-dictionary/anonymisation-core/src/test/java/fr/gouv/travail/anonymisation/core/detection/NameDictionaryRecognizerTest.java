package fr.gouv.travail.anonymisation.core.detection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.gouv.travail.anonymisation.core.model.DetectedEntity;
import fr.gouv.travail.anonymisation.core.model.EntityType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NameDictionaryRecognizerTest {

  /** Formes telles que les écrit le fichier des décès : capitales, sans accents. */
  private final NameDictionaryRecognizer recognizer =
      new NameDictionaryRecognizer(
          Map.of(
              "YANN", false,
              "JEAN-PIERRE", false,
              "HELENE", false,
              "PIERRE", true,
              "JEAN", true),
          Map.of(
              "DUPONT", false,
              "LE GALL", false,
              "ROY", false,
              "CSE", false,
              "PETIT", true,
              "BLANC", true,
              "POUR", true,
              "MONSIEUR", true));

  private List<String> names(String text) {
    List<DetectedEntity> entities = recognizer.recognize(text);
    assertThat(entities).allMatch(entity -> entity.type() == EntityType.PERSONNE);
    return entities.stream().map(DetectedEntity::text).toList();
  }

  @Test
  void trouveUnNomNonAmbiguSansCasseNiAccents() {
    assertThat(names("Le contrat de Dupont est signé par Hélène."))
        .containsExactly("Dupont", "Hélène");
  }

  @Test
  void ignoreUneFormeEnMinusculesOuDansUnMot() {
    assertThat(names("Un dupont, des Dupontel.")).isEmpty();
  }

  @Test
  void ignoreUneFormeAmbigueSansContexte() {
    assertThat(names("Petit à petit, le Blanc de l'œil. Pierre qui roule.")).isEmpty();
  }

  @Test
  void retientUneFormeAmbigueApresUneCivilite() {
    assertThat(names("M. Petit et Madame Blanc signent ; Me Pierre aussi."))
        .containsExactly("Petit", "Blanc", "Pierre");
  }

  @Test
  void retientUneFormeAmbigueColleeAUnNomRetenu() {
    // De proche en proche : « Jean » tient à « Pierre », qui tient à « Dupont ».
    assertThat(names("Signé : Pierre Dupont, puis Jean Pierre Dupont, puis Pierre Jean Dupont."))
        .containsExactly("Pierre", "Dupont", "Jean Pierre", "Dupont", "Pierre", "Jean", "Dupont");
  }

  @Test
  void retientUnNomAmbiguApresUnPrenomRetenu() {
    assertThat(names("Signé : Hélène Petit.")).containsExactly("Hélène", "Petit");
  }

  @Test
  void neRetientUnePositionQueSelonLeRoleDeLaForme() {
    // « Pour » est un nom de famille, pas un prénom : il ne s'accroche pas à « Dupont ».
    assertThat(names("Pour Dupont, c'est signé.")).containsExactly("Dupont");
  }

  @Test
  void neTraiteJamaisUneCiviliteCommeUnNom() {
    assertThat(names("Monsieur Dupont et Monsieur Petit.")).containsExactly("Dupont", "Petit");
  }

  @Test
  void neRelieDeuxFormesAmbiguesQuAvecUnAncrage() {
    assertThat(names("Jean Petit est venu.")).isEmpty();
  }

  @Test
  void trouveLesFormesComposees() {
    assertThat(names("Yann Le Gall et Jean-Pierre, puis LE GALL."))
        .containsExactly("Yann", "Le Gall", "Jean-Pierre", "LE GALL");
  }

  @Test
  void neRelieDesMotsQuSurUneMemeLigne() {
    assertThat(names("Yann Le\nGall")).containsExactly("Yann");
  }

  @Test
  void traiteUnSigleEnCapitalesCommeAmbigu() {
    assertThat(names("Le CSE est consulté. Signé : M. ROY.")).containsExactly("ROY");
  }

  @Test
  void chargeLesFichiersDuDictionnaire(@TempDir Path directory) throws IOException {
    Files.writeString(
        directory.resolve(NameDictionaryRecognizer.PRENOMS_FILE),
        "forme\tfrequence\tambigu\nHÉLÈNE\t500\tnon\nPIERRE\t900\toui\n");
    Files.writeString(
        directory.resolve(NameDictionaryRecognizer.NOMS_FILE),
        "forme\tfrequence\tambigu\nDUPONT\t300\tnon\n");

    NameDictionaryRecognizer loaded = NameDictionaryRecognizer.load(directory);

    assertThat(loaded.size()).isEqualTo(3);
    assertThat(loaded.recognize("Pierre Dupont et Helene").stream().map(DetectedEntity::text))
        .containsExactly("Pierre", "Dupont", "Helene");
    assertThat(loaded.toString()).doesNotContain("DUPONT");
  }

  @Test
  void expliqueCommentConstruireLeDictionnaireQuandIlEstAbsent(@TempDir Path directory) {
    assertThatThrownBy(() -> NameDictionaryRecognizer.load(directory))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("download-dictionary.py");
  }
}
