package fr.gouv.travail.anonymisation.core.ner;

import static org.assertj.core.api.Assertions.assertThat;

import fr.gouv.travail.anonymisation.core.ner.TextChunker.Chunk;
import java.util.List;
import org.junit.jupiter.api.Test;

class TextChunkerTest {

  @Test
  void unTexteCourtResteUnSeulMorceau() {
    assertThat(TextChunker.chunk("Bonjour", 100)).containsExactly(new Chunk(0, "Bonjour"));
  }

  @Test
  void coupeDePreferenceSurUnSautDeLigne() {
    List<Chunk> chunks = TextChunker.chunk("aaaa\nbbbb\ncccc", 7);

    assertThat(chunks).extracting(Chunk::text).containsExactly("aaaa\n", "bbbb\n", "cccc");
    assertThat(chunks).extracting(Chunk::offset).containsExactly(0, 5, 10);
  }

  @Test
  void neCoupePasUnNomSurUnSautDeLigneEnMilieuDePhrase() {
    // Mise en page d'un PDF : le saut de ligne tombe entre le prénom et le nom.
    String text =
        "Voici la première phrase assez longue. Il a convenu avec Fatima \nBernard demain.";
    int softWrap = text.indexOf("\nBernard") + 1;
    int sentenceEnd = text.indexOf("longue. ") + "longue. ".length();

    List<Chunk> chunks = TextChunker.chunk(text, softWrap + 3);

    assertThat(chunks.get(0).text()).hasSize(sentenceEnd);
    assertThat(chunks.get(1).text()).startsWith("Il a convenu avec Fatima \nBernard");
  }

  @Test
  void coupeSurUnSautDeLigneQuiSuitUneFinDePhrase() {
    String text = "Premier paragraphe terminé.\nSecond paragraphe sans fin de phrase au milieu";

    List<Chunk> chunks = TextChunker.chunk(text, 45);

    assertThat(chunks.get(0).text()).isEqualTo("Premier paragraphe terminé.\n");
  }

  @Test
  void neCoupePasUnMotQuandIlYADesEspaces() {
    String text = "alpha beta gamma delta epsilon zeta";

    List<Chunk> chunks = TextChunker.chunk(text, 12);

    assertThat(chunks)
        .allSatisfy(chunk -> assertThat(chunk.text().length()).isLessThanOrEqualTo(12));
    assertThat(chunks.subList(0, chunks.size() - 1))
        .allSatisfy(chunk -> assertThat(chunk.text()).endsWith(" "));
    assertThat(rejoin(chunks)).isEqualTo(text);
  }

  @Test
  void coupeDurSiAucunEspaceNEstDisponible() {
    List<Chunk> chunks = TextChunker.chunk("x".repeat(25), 10);

    assertThat(chunks).extracting(c -> c.text().length()).containsExactly(10, 10, 5);
  }

  @Test
  void lesOffsetsPermettentDeRetrouverLeTexteOriginal() {
    String text =
        ("Phrase numéro un. Phrase numéro deux.\n\n" + "Autre paragraphe sans fin ").repeat(20);

    for (Chunk chunk : TextChunker.chunk(text, 90)) {
      assertThat(text.substring(chunk.offset(), chunk.offset() + chunk.text().length()))
          .isEqualTo(chunk.text());
    }
  }

  private static String rejoin(List<Chunk> chunks) {
    return chunks.stream().map(Chunk::text).reduce("", String::concat);
  }
}
