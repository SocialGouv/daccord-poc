package fr.gouv.travail.anonymisation.core.detection;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ChecksumsTest {

  @Test
  void iban() {
    assertThat(Checksums.isValidIban("FR76 3000 6000 0112 3456 7890 189")).isTrue();
    assertThat(Checksums.isValidIban("FR7630006000011234567890189")).isTrue();
    assertThat(Checksums.isValidIban("FR76 3000 6000 0112 3456 7890 188")).isFalse();
  }

  @Test
  void nirAvecCleValide() {
    assertThat(Checksums.isValidNirKey("1 85 05 78 006 084 91")).isTrue();
    assertThat(Checksums.isValidNirKey("269051123456764")).isTrue();
  }

  @Test
  void nirCorse() {
    assertThat(Checksums.isValidNirKey("1 85 05 2A 006 084 35")).isTrue();
  }

  @Test
  void nirAvecCleInvalide() {
    assertThat(Checksums.isValidNirKey("1 85 05 78 006 084 36")).isFalse();
  }

  @Test
  void luhn() {
    assertThat(Checksums.isValidLuhn("356 000 000 00048")).isTrue();
    assertThat(Checksums.isValidLuhn("732.829.320.00074")).isTrue();
    assertThat(Checksums.isValidLuhn("356 000 000 00049")).isFalse();
    assertThat(Checksums.isValidLuhn("")).isFalse();
  }
}
