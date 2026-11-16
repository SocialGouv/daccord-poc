package fr.gouv.travail.anonymisation.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Démarre sur un vrai contexte servlet avec la configuration par défaut (chemin relatif du modèle
 * compris) : c'est ce que fait le jar, et ce que MockMvc ne reproduit pas.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "anonymisation.ner.enabled=false")
class ServerStartupTest {

  @Autowired private AnonymisationProperties properties;

  @Test
  void lesProprietesParDefautSeLient() {
    assertThat(properties.ner().modelDir()).endsWith("models/camembert-ner");
    assertThat(properties.types()).isNotEmpty();
  }
}
