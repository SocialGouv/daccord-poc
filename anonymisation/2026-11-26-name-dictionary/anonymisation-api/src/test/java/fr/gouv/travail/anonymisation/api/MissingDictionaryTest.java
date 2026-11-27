package fr.gouv.travail.anonymisation.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** Garde-fou : sans dictionnaire, l'application refuse de démarrer, comme sans modèle. */
class MissingDictionaryTest {

  @Test
  void refuseDeDemarrerSansDictionnaireQuandIlEstActif() {
    var application =
        new SpringApplicationBuilder(AnonymisationApplication.class).web(WebApplicationType.NONE);

    Throwable failure =
        assertThrows(
            Exception.class,
            () ->
                application.run(
                    "--anonymisation.ner.enabled=false",
                    "--anonymisation.dictionnaire.dir=/inexistant"));

    assertThat(rootMessages(failure)).contains("download-dictionary.py");
  }

  private static String rootMessages(Throwable throwable) {
    StringBuilder messages = new StringBuilder();
    for (Throwable t = throwable; t != null; t = t.getCause()) {
      messages.append(t.getMessage()).append('\n');
    }
    return messages.toString();
  }
}
