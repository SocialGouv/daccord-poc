package fr.gouv.travail.anonymisation.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * Garde-fou : sans modèle, l'application doit refuser de démarrer plutôt que de vérifier à moitié.
 */
class MissingModelTest {

  @Test
  void refuseDeDemarrerSansModeleQuandLeNerEstActif() {
    var application =
        new SpringApplicationBuilder(AnonymisationApplication.class).web(WebApplicationType.NONE);

    // En argument de ligne de commande : « .properties(...) » serait écrasé par application.yml.
    Throwable failure =
        assertThrows(
            Exception.class,
            () ->
                application.run(
                    "--anonymisation.ner.model-dir=/inexistant",
                    "--anonymisation.dictionnaire.enabled=false"));

    assertThat(rootMessages(failure)).contains("download-model.sh");
  }

  private static String rootMessages(Throwable throwable) {
    StringBuilder messages = new StringBuilder();
    for (Throwable t = throwable; t != null; t = t.getCause()) {
      messages.append(t.getMessage()).append('\n');
    }
    return messages.toString();
  }
}
