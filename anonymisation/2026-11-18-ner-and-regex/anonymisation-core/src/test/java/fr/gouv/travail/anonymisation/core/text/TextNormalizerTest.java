package fr.gouv.travail.anonymisation.core.text;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TextNormalizerTest {

  @Test
  void foldRetireAccentsEtCasseEnGardantLaLongueur() {
    String text = "Élève Ça Müller Œuvre";

    assertThat(TextNormalizer.fold(text)).hasSize(text.length()).startsWith("eleve ca muller");
  }

  @Test
  void keyIgnoreSeparateursCasseEtAccents() {
    assertThat(TextNormalizer.key("Jean-Pierre")).isEqualTo(TextNormalizer.key("jean pierre"));
    assertThat(TextNormalizer.key("06 12 34 56 78")).isEqualTo(TextNormalizer.key("0612345678"));
    assertThat(TextNormalizer.key("Hélène")).isEqualTo("helene");
  }
}
