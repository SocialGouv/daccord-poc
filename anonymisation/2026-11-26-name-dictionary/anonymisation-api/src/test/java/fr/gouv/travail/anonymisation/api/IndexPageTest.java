package fr.gouv.travail.anonymisation.api;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/** La page de test {@code index.html} (à la racine du projet) est servie par l'application. */
@SpringBootTest(
    properties = {"anonymisation.ner.enabled=false", "anonymisation.dictionnaire.enabled=false"})
@AutoConfigureMockMvc
class IndexPageTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void laRacineServLaPageDeTest() throws Exception {
    mockMvc.perform(get("/")).andExpect(status().isOk()).andExpect(forwardedUrl("index.html"));
  }

  @Test
  void laPageDeTestAppelleLesServicesChaine() throws Exception {
    mockMvc
        .perform(get("/index.html"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Valider")))
        .andExpect(content().string(containsString("/api/verifications/chaine")))
        .andExpect(content().string(containsString("/api/anonymisations/chaine")));
  }
}
