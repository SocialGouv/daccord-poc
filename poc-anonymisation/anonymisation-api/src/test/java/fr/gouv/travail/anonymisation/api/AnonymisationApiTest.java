package fr.gouv.travail.anonymisation.api;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

/** NER désactivé : on teste le câblage et le contrat HTTP, pas le modèle. */
@SpringBootTest(properties = "anonymisation.ner.enabled=false")
@AutoConfigureMockMvc
class AnonymisationApiTest {

  @Autowired private MockMvc mockMvc;

  private static MockMultipartFile text(String name, String content) {
    return new MockMultipartFile(
        name, name + ".txt", "text/plain", content.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void anonymiseUnFichier() throws Exception {
    mockMvc
        .perform(
            multipart("/api/anonymisations")
                .file(text("file", "Écrire à jean.dupont@example.fr ou au 06 12 34 56 78.")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.text").value("Écrire à [EMAIL_1] ou au [TELEPHONE_1]."))
        .andExpect(jsonPath("$.replacements.EMAIL").value(1))
        .andExpect(jsonPath("$.replacements.TELEPHONE").value(1))
        .andExpect(jsonPath("$.engines[0]").value("regex-email"));
  }

  @Test
  void declareNonConformeUnFichierQuiContientEncoreUnEmail() throws Exception {
    mockMvc
        .perform(multipart("/api/verifications").file(text("file", "Contact : jean@example.fr")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.verdict").value("NON_CONFORME"))
        .andExpect(jsonPath("$.findings[0].type").value("EMAIL"))
        .andExpect(jsonPath("$.findings[0].kind").value("RESIDUAL_ENTITY"))
        .andExpect(jsonPath("$.warnings[0]").value(containsString("NER")));
  }

  @Test
  void declareConformeUnFichierAnonymise() throws Exception {
    mockMvc
        .perform(multipart("/api/verifications").file(text("file", "Contact : [EMAIL_1]")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.verdict").value("CONFORME"))
        .andExpect(jsonPath("$.findings").isEmpty());
  }

  @Test
  void compareAvecLOriginalQuandIlEstFourni() throws Exception {
    mockMvc
        .perform(
            multipart("/api/verifications")
                .file(text("file", "Contact : [EMAIL_1] (Jean.Dupont@Example.fr)"))
                .file(text("original", "Contact : jean.dupont@example.fr")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.verdict").value("NON_CONFORME"))
        .andExpect(jsonPath("$.findings[0].kind").value("ORIGINAL_VALUE_LEAK"))
        .andExpect(jsonPath("$.findings.length()").value(1));
  }

  @Test
  void refuseUnFichierQuiNEstPasDuUtf8() throws Exception {
    var binary =
        new MockMultipartFile(
            "file",
            "x.bin",
            "application/octet-stream",
            new byte[] {(byte) 0xFF, (byte) 0xFE, 0x00});

    mockMvc
        .perform(multipart("/api/anonymisations").file(binary))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value("Le fichier doit être un texte encodé en UTF-8."));
  }
}
