package fr.gouv.travail.anonymisation.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * La page {@code index.html}, ouverte hors du serveur, doit pouvoir appeler l'API quand on le
 * demande.
 */
@SpringBootTest(
    properties = {
      "anonymisation.ner.enabled=false",
      "anonymisation.dictionnaire.enabled=false",
      "anonymisation.cors.allowed-origins=*"
    })
@AutoConfigureMockMvc
class CorsApiTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void autoriseLesAppelsDeLaPageDeTest() throws Exception {
    mockMvc
        .perform(
            options("/api/anonymisations/chaine")
                .header("Origin", "null")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", "*"));
  }
}
