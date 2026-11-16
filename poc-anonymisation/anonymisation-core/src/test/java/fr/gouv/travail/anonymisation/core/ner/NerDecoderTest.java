package fr.gouv.travail.anonymisation.core.ner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.within;

import fr.gouv.travail.anonymisation.core.model.DetectedEntity;
import fr.gouv.travail.anonymisation.core.model.EntityType;
import fr.gouv.travail.anonymisation.core.ner.NerDecoder.TokenPrediction;
import java.util.List;
import org.junit.jupiter.api.Test;

class NerDecoderTest {

  private static final String TEXT = "Jean Dupont habite à Paris.";

  @Test
  void regroupeLesSousMotsEnEntitesEtRognelEspaceInitial() {
    List<TokenPrediction> tokens =
        List.of(
            new TokenPrediction("I-PER", 0.99, 0, 4),
            new TokenPrediction("I-PER", 0.95, 4, 8), // « ▁Dup » : l'offset inclut l'espace
            new TokenPrediction("I-PER", 0.97, 8, 11),
            new TokenPrediction("O", 0.99, 11, 18),
            new TokenPrediction("O", 0.99, 18, 20),
            new TokenPrediction("I-LOC", 0.90, 20, 26), // « ▁Paris »
            new TokenPrediction("O", 0.99, 26, 27));

    List<DetectedEntity> entities = NerDecoder.decode(TEXT, tokens, "test");

    assertThat(entities)
        .extracting(DetectedEntity::type, DetectedEntity::text, DetectedEntity::start)
        .containsExactly(
            tuple(EntityType.PERSONNE, "Jean Dupont", 0), tuple(EntityType.LIEU, "Paris", 21));
    assertThat(entities.get(0).score()).isCloseTo((0.99 + 0.95 + 0.97) / 3, within(1e-9));
  }

  @Test
  void unPrefixeBOuvreUneNouvelleEntite() {
    String text = "JeanMarie";
    List<TokenPrediction> tokens =
        List.of(new TokenPrediction("B-PER", 0.9, 0, 4), new TokenPrediction("B-PER", 0.9, 4, 9));

    assertThat(NerDecoder.decode(text, tokens, "test"))
        .extracting(DetectedEntity::text)
        .containsExactly("Jean", "Marie");
  }

  @Test
  void ignoreLesLabelsInconnusEtLeTexteSansEntite() {
    List<TokenPrediction> tokens =
        List.of(new TokenPrediction("I-FOO", 0.9, 0, 4), new TokenPrediction("O", 0.99, 5, 11));

    assertThat(NerDecoder.decode(TEXT, tokens, "test")).isEmpty();
    assertThat(NerDecoder.decode(TEXT, List.of(), "test")).isEmpty();
  }

  @Test
  void ignoreLesTokensSansLongueur() {
    List<TokenPrediction> tokens =
        List.of(
            new TokenPrediction("I-PER", 0.9, 0, 4),
            new TokenPrediction("O", 0.9, 4, 4),
            new TokenPrediction("I-PER", 0.9, 5, 11));

    assertThat(NerDecoder.decode(TEXT, tokens, "test"))
        .extracting(DetectedEntity::text)
        .containsExactly("Jean Dupont");
  }

  @Test
  void lApostropheClasseeHorsEntiteNeCoupePasUneOrganisation() {
    String text = "Caisse d'Allocations Familiales";
    List<TokenPrediction> tokens =
        List.of(
            new TokenPrediction("I-ORG", 0.98, 0, 6),
            new TokenPrediction("I-ORG", 0.98, 6, 8), // « ▁d »
            new TokenPrediction("O", 0.80, 8, 9), // « ' » : classé hors entité par le modèle
            new TokenPrediction("I-ORG", 0.98, 9, 20),
            new TokenPrediction("I-ORG", 0.98, 20, 31));

    assertThat(NerDecoder.decode(text, tokens, "test"))
        .extracting(DetectedEntity::type, DetectedEntity::text)
        .containsExactly(tuple(EntityType.ORGANISATION, "Caisse d'Allocations Familiales"));
  }

  @Test
  void leTraitDUnionEntreDeuxPrenomsResteDansLEntite() {
    String text = "Jean-Pierre";
    List<TokenPrediction> tokens =
        List.of(
            new TokenPrediction("I-PER", 0.9, 0, 4),
            new TokenPrediction("O", 0.9, 4, 5),
            new TokenPrediction("I-PER", 0.9, 5, 11));

    assertThat(NerDecoder.decode(text, tokens, "test"))
        .extracting(DetectedEntity::text)
        .containsExactly("Jean-Pierre");
  }

  @Test
  void nePontePasQuandLesTypesDifferentOuQuIlYADesEspaces() {
    String text = "Paris - Jean";
    List<TokenPrediction> differentTypes =
        List.of(
            new TokenPrediction("I-LOC", 0.9, 0, 5),
            new TokenPrediction("O", 0.9, 5, 7), // « ▁- » : l'offset inclut l'espace
            new TokenPrediction("I-PER", 0.9, 7, 12));
    String compact = "Paris-Jean";
    List<TokenPrediction> otherTypes =
        List.of(
            new TokenPrediction("I-LOC", 0.9, 0, 5),
            new TokenPrediction("O", 0.9, 5, 6),
            new TokenPrediction("I-PER", 0.9, 6, 10));

    assertThat(NerDecoder.decode(text, differentTypes, "test")).hasSize(2);
    assertThat(NerDecoder.decode(compact, otherTypes, "test"))
        .extracting(DetectedEntity::text)
        .containsExactly("Paris", "Jean");
  }
}
