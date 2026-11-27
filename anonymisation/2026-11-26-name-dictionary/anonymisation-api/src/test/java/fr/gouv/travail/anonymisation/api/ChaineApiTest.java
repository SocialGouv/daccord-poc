package fr.gouv.travail.anonymisation.api;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** NER désactivé : on teste le contrat HTTP des services « chaîne », pas le modèle. */
@SpringBootTest(
    properties = {"anonymisation.ner.enabled=false", "anonymisation.dictionnaire.enabled=false"})
@AutoConfigureMockMvc
class ChaineApiTest {

  @Autowired private MockMvc mockMvc;

  private static MockHttpServletRequestBuilder json(String path, String body) {
    return post(path)
        .contentType(MediaType.APPLICATION_JSON)
        .content(body.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void anonymiseUneChaine() throws Exception {
    mockMvc
        .perform(
            json(
                "/api/anonymisations/chaine",
                """
                {"text": "Écrire à jean.dupont@example.fr ou au 06 12 34 56 78.\\nÀ bientôt."}"""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.text").value("Écrire à [EMAIL_1] ou au [TELEPHONE_1].\nÀ bientôt."))
        .andExpect(jsonPath("$.replacements.EMAIL").value(1))
        .andExpect(jsonPath("$.replacements.TELEPHONE").value(1))
        .andExpect(jsonPath("$.engines[0]").value("regex-email"));
  }

  @Test
  void uneChaineVideResteVide() throws Exception {
    mockMvc
        .perform(json("/api/anonymisations/chaine", "{\"text\": \"\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.text").value(""))
        .andExpect(jsonPath("$.replacements").isEmpty());
  }

  @Test
  void exigeLeChampText() throws Exception {
    mockMvc
        .perform(json("/api/anonymisations/chaine", "{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value("Le champ « text » est obligatoire."));
    mockMvc
        .perform(json("/api/verifications/chaine", "{\"original\": \"abc\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value("Le champ « text » est obligatoire."));
  }

  @Test
  void refuseUnCorpsAbsentOuQuiNEstPasDuJson() throws Exception {
    mockMvc
        .perform(post("/api/anonymisations/chaine").contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(json("/api/anonymisations/chaine", "pas du json"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void declareNonConformeUneChaineQuiContientEncoreUnEmail() throws Exception {
    mockMvc
        .perform(json("/api/verifications/chaine", "{\"text\": \"Contact : jean@example.fr\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.verdict").value("NON_CONFORME"))
        .andExpect(jsonPath("$.findings[0].type").value("EMAIL"))
        .andExpect(jsonPath("$.findings[0].kind").value("RESIDUAL_ENTITY"))
        .andExpect(jsonPath("$.warnings[0]").value(containsString("NER")));
  }

  @Test
  void declareConformeUneChaineAnonymisee() throws Exception {
    mockMvc
        .perform(json("/api/verifications/chaine", "{\"text\": \"Contact : [EMAIL_1]\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.verdict").value("CONFORME"))
        .andExpect(jsonPath("$.findings").isEmpty());
  }

  @Test
  void compareAvecLOriginalQuandIlEstFourni() throws Exception {
    mockMvc
        .perform(
            json(
                "/api/verifications/chaine",
                """
                {"text": "Contact : [EMAIL_1] (Jean.Dupont@Example.fr)",
                 "original": "Contact : jean.dupont@example.fr"}"""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.verdict").value("NON_CONFORME"))
        .andExpect(jsonPath("$.findings[0].kind").value("ORIGINAL_VALUE_LEAK"))
        .andExpect(jsonPath("$.findings.length()").value(1));
  }
}
