package fr.gouv.travail.anonymisation.documents.word;

import fr.gouv.travail.anonymisation.core.anonymization.AnonymizationPlan;
import fr.gouv.travail.anonymisation.core.anonymization.AnonymizationPlan.Replacement;
import fr.gouv.travail.anonymisation.core.anonymization.Anonymizer;
import fr.gouv.travail.anonymisation.documents.IncompleteAnonymisationException;
import fr.gouv.travail.anonymisation.documents.InvalidDocumentException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.hpsf.DocumentSummaryInformation;
import org.apache.poi.hpsf.SummaryInformation;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.model.TextPiece;
import org.apache.poi.hwpf.usermodel.Range;
import org.apache.poi.poifs.filesystem.NotOLE2FileException;

/**
 * Anonymise un .doc (Word 97-2003) avec HWPF, en remplaçant chaque caractère d'une entité par une
 * astérisque : <strong>la longueur du texte ne change jamais</strong>.
 *
 * <p>C'est volontaire. Le format binaire garde, en plus du texte, des tables de positions (limites
 * entre en-tête et pied de page, notes, commentaires, champs, signets) que HWPF ne met pas à jour
 * quand on insère un texte de longueur différente : essayé avec des marqueurs {@code [TYPE_n]}, le
 * « ] » final d'un marqueur d'en-tête glissait dans le pied de page. Remplacer caractère pour
 * caractère, dans le texte lui-même, ne touche à aucune de ces tables. En contrepartie, les .doc
 * n'ont pas de marqueurs numérotés : on ne distingue pas deux personnes entre elles.
 *
 * <p>Chaque zone (corps, notes, en-têtes et pieds de page, commentaires, zones de texte) est
 * analysée. Le texte des zones de texte d'en-tête, que HWPF n'atteint pas, est analysé lui aussi :
 * s'il contient une entité, on refuse le document plutôt que de la laisser en clair. Le créateur,
 * le dernier auteur, la société, le responsable et la vignette sont effacés ; titre, sujet,
 * mots-clés et commentaires sont anonymisés avec un marqueur (les propriétés ne sont pas dans le
 * texte).
 *
 * <p>Ni les objets incorporés ni le code VBA ne sont analysés. HWPF peut échouer sur des documents
 * complexes : on le dit alors, on ne livre rien.
 */
public final class DocAnonymizer {

  public record Result(byte[] content, AnonymizationPlan plan) {}

  /** Zones du document, dans l'ordre où elles se suivent dans le fichier. */
  private enum Story {
    MAIN,
    FOOTNOTES,
    HEADERS,
    COMMENTS,
    ENDNOTES,
    TEXTBOXES;

    Range of(HWPFDocument document) {
      return switch (this) {
        case MAIN -> document.getRange();
        case FOOTNOTES -> document.getFootnoteRange();
        case HEADERS -> document.getHeaderStoryRange();
        case COMMENTS -> document.getCommentsRange();
        case ENDNOTES -> document.getEndnoteRange();
        case TEXTBOXES -> document.getMainTextboxRange();
      };
    }
  }

  public Result anonymize(byte[] content, Anonymizer anonymizer) {
    try (HWPFDocument document = new HWPFDocument(new ByteArrayInputStream(content))) {
      Story[] stories = Story.values();
      String[] raw = new String[stories.length];
      List<String> fragments = new ArrayList<>();
      int covered = 0;
      for (int i = 0; i < stories.length; i++) {
        raw[i] = stories[i].of(document).text();
        covered += raw[i].length();
        fragments.add(readable(raw[i]));
      }
      // Ce qui suit les zones connues : texte des zones de texte d'en-tête, que HWPF ne sait pas
      // atteindre. On l'analyse pour savoir s'il contient quelque chose, pas pour le réécrire.
      String overall = document.getOverallRange().text();
      int headerTextboxes = fragments.size();
      fragments.add(readable(overall.substring(Math.min(covered, overall.length()))));
      SummaryInformation summary = document.getSummaryInformation();
      int firstProperty = fragments.size();
      if (summary != null) {
        fragments.add(nullToEmpty(summary.getTitle()));
        fragments.add(nullToEmpty(summary.getSubject()));
        fragments.add(nullToEmpty(summary.getKeywords()));
        fragments.add(nullToEmpty(summary.getComments()));
      }
      AnonymizationPlan plan = anonymizer.plan(fragments);
      if (!plan.replacements().get(headerTextboxes).isEmpty()) {
        throw new IncompleteAnonymisationException(
            "Des données personnelles figurent dans une zone de texte d'en-tête ou de pied de page "
                + "d'un .doc, que l'on ne sait pas réécrire : convertir le document en .docx.");
      }

      List<TextPiece> pieces = document.getTextTable().getTextPieces();
      for (int story = 0; story < stories.length; story++) {
        int base = stories[story].of(document).getStartOffset();
        for (Replacement replacement : plan.replacements().get(story)) {
          mask(pieces, base + replacement.start(), base + replacement.end());
        }
      }
      rewriteProperties(document, summary, plan, fragments, firstProperty);

      ByteArrayOutputStream output = new ByteArrayOutputStream();
      document.write(output);
      return new Result(output.toByteArray(), plan);
    } catch (IncompleteAnonymisationException | InvalidDocumentException e) {
      throw e;
    } catch (NotOLE2FileException e) {
      throw new InvalidDocumentException("Document Word illisible.", e);
    } catch (IOException | RuntimeException e) {
      throw new InvalidDocumentException(
          "Ce document .doc ne peut pas être réécrit de façon sûre (format non pris en charge).",
          e);
    }
  }

  /**
   * Remplace par « * » les caractères {@code [from, to[} du texte, directement dans les morceaux de
   * texte du fichier. Les codes de contrôle (fin de paragraphe, de cellule, champs) sont conservés.
   */
  private static void mask(List<TextPiece> pieces, int from, int to) {
    int offset = 0;
    for (TextPiece piece : pieces) {
      StringBuilder text = piece.getStringBuilder();
      for (int i = Math.max(from - offset, 0); i < text.length() && offset + i < to; i++) {
        if (text.charAt(i) >= ' ') {
          text.setCharAt(i, '*');
        }
      }
      offset += text.length();
      if (offset >= to) {
        return;
      }
    }
  }

  private static void rewriteProperties(
      HWPFDocument document,
      SummaryInformation summary,
      AnonymizationPlan plan,
      List<String> fragments,
      int first) {
    if (summary != null) {
      summary.removeAuthor();
      summary.removeLastAuthor();
      summary.removeThumbnail();
      if (!fragments.get(first).isEmpty()) {
        summary.setTitle(plan.apply(first, fragments.get(first)));
      }
      if (!fragments.get(first + 1).isEmpty()) {
        summary.setSubject(plan.apply(first + 1, fragments.get(first + 1)));
      }
      if (!fragments.get(first + 2).isEmpty()) {
        summary.setKeywords(plan.apply(first + 2, fragments.get(first + 2)));
      }
      if (!fragments.get(first + 3).isEmpty()) {
        summary.setComments(plan.apply(first + 3, fragments.get(first + 3)));
      }
    }
    DocumentSummaryInformation extended = document.getDocumentSummaryInformation();
    if (extended != null) {
      extended.removeCompany();
      extended.removeManager();
    }
  }

  /**
   * Le texte HWPF contient des codes de contrôle (fin de paragraphe, de cellule, marques de champ,
   * image). On les remplace par une espace ou un saut de ligne, sans changer la longueur : les
   * positions trouvées dans ce texte valent aussi dans {@link Range#text()}.
   */
  private static String readable(String text) {
    StringBuilder readable = new StringBuilder(text.length());
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '\r' || c == '\u0007') {
        readable.append('\n');
      } else if (c < ' ' && c != '\t' && c != '\n') {
        readable.append(' ');
      } else {
        readable.append(c);
      }
    }
    return readable.toString();
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }
}
