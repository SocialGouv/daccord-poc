package fr.gouv.travail.anonymisation.core.detection;

import fr.gouv.travail.anonymisation.core.model.DetectedEntity;
import fr.gouv.travail.anonymisation.core.model.EntityType;
import fr.gouv.travail.anonymisation.core.text.TextNormalizer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Prénoms et noms de famille d'un dictionnaire (sources INSEE, voir {@code
 * scripts/download-dictionary.py}), en complément du NER : un nom connu est trouvé même quand le
 * modèle le rate.
 *
 * <p>Une forme est cherchée sans casse ni accents, en mots entiers, et doit commencer par une
 * majuscule. Une forme <em>ambiguë</em>, qui est aussi un mot courant (« Petit », « Blanc », «
 * Pierre »), n'est retenue qu'avec un contexte :
 *
 * <ul>
 *   <li>une civilité juste avant (« M. Petit », « Madame Blanc ») ;
 *   <li>un prénom juste avant un nom retenu (« Pierre Dupont ») ;
 *   <li>un nom juste après un nom retenu (« Sophie Petit »).
 * </ul>
 *
 * <p>Le rôle compte : « Pour » est un nom de famille, pas un prénom, donc « Pour Dupont » ne masque
 * pas « Pour ». Un sigle en capitales de 4 lettres au plus (« CSE », « UNSA ») est traité comme
 * ambigu ; une civilité n'est jamais un nom.
 */
public final class NameDictionaryRecognizer implements EntityRecognizer {

  public static final String PRENOMS_FILE = "prenoms.tsv";
  public static final String NOMS_FILE = "noms.tsv";

  /** En dessous, une forme (« Li », « Ba ») est trop courte pour être cherchée sans bruit. */
  static final int MIN_LETTERS = 3;

  private static final int MAX_ACRONYM_LETTERS = 4;
  private static final double SCORE = 0.9;

  private static final Set<String> CIVILITES =
      Set.of(
          "m",
          "mr",
          "mme",
          "mlle",
          "monsieur",
          "madame",
          "mademoiselle",
          "me",
          "maitre",
          "dr",
          "docteur",
          "pr",
          "professeur");

  /** Ce que le dictionnaire sait d'une forme. */
  private record Entry(boolean prenom, boolean nom, boolean ambiguous) {

    Entry merge(Entry other) {
      return new Entry(prenom || other.prenom, nom || other.nom, ambiguous || other.ambiguous);
    }
  }

  /** Clé (mots sans casse ni accents, séparés par une espace) → rôles et ambiguïté. */
  private final Map<String, Entry> entries;

  private final int maxWords;

  /**
   * @param prenoms forme telle qu'écrite dans la source (« JEAN-PIERRE ») → vrai si c'est aussi un
   *     mot courant
   * @param noms idem pour les noms de famille (« LE GALL »)
   */
  public NameDictionaryRecognizer(Map<String, Boolean> prenoms, Map<String, Boolean> noms) {
    Map<String, Entry> byKey = new HashMap<>();
    int longest = 1;
    for (boolean isPrenom : new boolean[] {true, false}) {
      for (Map.Entry<String, Boolean> form : (isPrenom ? prenoms : noms).entrySet()) {
        List<String> words = words(form.getKey());
        if (!words.isEmpty()) {
          Entry entry = new Entry(isPrenom, !isPrenom, form.getValue());
          byKey.merge(String.join(" ", words), entry, Entry::merge);
          longest = Math.max(longest, words.size());
        }
      }
    }
    CIVILITES.forEach(byKey::remove);
    this.entries = Map.copyOf(byKey);
    this.maxWords = longest;
  }

  /** Charge {@code prenoms.tsv} et {@code noms.tsv} (colonnes forme, frequence, ambigu). */
  public static NameDictionaryRecognizer load(Path directory) {
    return new NameDictionaryRecognizer(
        read(directory.resolve(PRENOMS_FILE)), read(directory.resolve(NOMS_FILE)));
  }

  private static Map<String, Boolean> read(Path path) {
    if (!Files.isReadable(path)) {
      throw new IllegalStateException(
          "Dictionnaire introuvable : "
              + path.toAbsolutePath()
              + ". Le construire avec ./scripts/download-dictionary.py, ou désactiver le moteur"
              + " avec --anonymisation.dictionnaire.enabled=false.");
    }
    Map<String, Boolean> ambiguousByForm = new HashMap<>();
    try (Stream<String> lines = Files.lines(path, StandardCharsets.UTF_8)) {
      lines
          .skip(1)
          .map(line -> line.split("\t"))
          .filter(columns -> columns.length >= 3)
          .forEach(
              columns ->
                  ambiguousByForm.merge(columns[0], "oui".equals(columns[2]), Boolean::logicalOr));
    } catch (IOException e) {
      throw new UncheckedIOException("Lecture impossible : " + path, e);
    }
    return ambiguousByForm;
  }

  public int size() {
    return entries.size();
  }

  @Override
  public String name() {
    return "dictionnaire-noms";
  }

  @Override
  public List<DetectedEntity> recognize(String text) {
    String folded = TextNormalizer.fold(text);
    List<int[]> tokens = tokens(text);
    List<Candidate> candidates = candidates(text, folded, tokens);
    accept(text, folded, tokens, candidates);

    List<DetectedEntity> entities = new ArrayList<>();
    for (Candidate candidate : candidates) {
      if (candidate.accepted) {
        entities.add(
            new DetectedEntity(
                EntityType.PERSONNE,
                candidate.start,
                candidate.end,
                text.substring(candidate.start, candidate.end),
                SCORE,
                name()));
      }
    }
    return entities;
  }

  /** Plus longue forme connue à chaque position, les suivantes repartant après elle. */
  private List<Candidate> candidates(String text, String folded, List<int[]> tokens) {
    List<Candidate> candidates = new ArrayList<>();
    int i = 0;
    while (i < tokens.size()) {
      int matched = 0;
      if (Character.isUpperCase(text.charAt(tokens.get(i)[0]))) {
        for (int n = Math.min(maxWords, tokens.size() - i); n >= 1 && matched == 0; n--) {
          if (!joined(text, tokens, i, n)) {
            continue;
          }
          StringBuilder key = new StringBuilder();
          int letters = 0;
          for (int k = i; k < i + n; k++) {
            int[] token = tokens.get(k);
            key.append(k > i ? " " : "").append(folded, token[0], token[1]);
            letters += token[1] - token[0];
          }
          Entry entry = entries.get(key.toString());
          if (entry != null && letters >= MIN_LETTERS) {
            int start = tokens.get(i)[0];
            int end = tokens.get(i + n - 1)[1];
            boolean acronym = letters <= MAX_ACRONYM_LETTERS && isUpperCase(text, start, end);
            candidates.add(new Candidate(i, i + n - 1, start, end, entry, acronym));
            matched = n;
          }
        }
      }
      i += Math.max(matched, 1);
    }
    return candidates;
  }

  /**
   * Retient les formes non ambiguës et celles qui suivent une civilité, puis, jusqu'à stabilité, un
   * prénom ambigu collé avant une forme retenue et un nom ambigu collé après.
   */
  private static void accept(
      String text, String folded, List<int[]> tokens, List<Candidate> candidates) {
    for (Candidate candidate : candidates) {
      candidate.accepted = !candidate.ambiguous || afterCivilite(text, folded, tokens, candidate);
    }
    boolean changed = true;
    while (changed) {
      changed = false;
      for (int c = 0; c < candidates.size(); c++) {
        Candidate candidate = candidates.get(c);
        if (candidate.accepted) {
          continue;
        }
        Candidate previous = c > 0 ? candidates.get(c - 1) : null;
        Candidate next = c + 1 < candidates.size() ? candidates.get(c + 1) : null;
        boolean prenomBefore =
            candidate.entry.prenom
                && next != null
                && next.accepted
                && adjacent(text, candidate, next);
        boolean nomAfter =
            candidate.entry.nom
                && previous != null
                && previous.accepted
                && adjacent(text, previous, candidate);
        if (prenomBefore || nomAfter) {
          candidate.accepted = true;
          changed = true;
        }
      }
    }
  }

  private static boolean afterCivilite(
      String text, String folded, List<int[]> tokens, Candidate candidate) {
    if (candidate.firstToken == 0) {
      return false;
    }
    int[] previous = tokens.get(candidate.firstToken - 1);
    String gap = text.substring(previous[1], candidate.start);
    return gap.length() <= 3
        && gap.chars().allMatch(c -> c == '.' || isSpace(c))
        && gap.chars().anyMatch(NameDictionaryRecognizer::isSpace)
        && CIVILITES.contains(folded.substring(previous[0], previous[1]));
  }

  private static boolean adjacent(String text, Candidate left, Candidate right) {
    return right.firstToken == left.lastToken + 1 && separator(text, left.end, right.start);
  }

  /** Vrai si les mots {@code i} à {@code i + n - 1} ne sont séparés que par un joint de nom. */
  private static boolean joined(String text, List<int[]> tokens, int i, int n) {
    for (int k = i + 1; k < i + n; k++) {
      if (!separator(text, tokens.get(k - 1)[1], tokens.get(k)[0])) {
        return false;
      }
    }
    return true;
  }

  /**
   * Espace, trait d'union ou apostrophe (« Le Gall », « Jean-Pierre », « N'Diaye »), sur la même
   * ligne.
   */
  private static boolean separator(String text, int from, int to) {
    if (to - from < 1 || to - from > 2) {
      return false;
    }
    for (int i = from; i < to; i++) {
      char c = text.charAt(i);
      if (!(isSpace(c) || c == '-' || c == '‑' || c == '\'' || c == '’')) {
        return false;
      }
    }
    return true;
  }

  /** Espace, espace insécable, espace fine insécable ou tabulation. */
  private static boolean isSpace(int c) {
    return c == ' ' || c == ' ' || c == ' ' || c == '\t';
  }

  private static boolean isUpperCase(String text, int start, int end) {
    for (int i = start; i < end; i++) {
      if (Character.isLowerCase(text.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  /** Positions {@code [début, fin[} des suites de lettres. */
  private static List<int[]> tokens(String text) {
    List<int[]> tokens = new ArrayList<>();
    int i = 0;
    while (i < text.length()) {
      if (Character.isLetter(text.charAt(i))) {
        int start = i;
        while (i < text.length() && Character.isLetter(text.charAt(i))) {
          i++;
        }
        tokens.add(new int[] {start, i});
      } else {
        i++;
      }
    }
    return tokens;
  }

  /** Mots d'une forme, sans casse ni accents : « LE GALL » → [le, gall]. */
  private static List<String> words(String form) {
    String folded = TextNormalizer.fold(form);
    List<String> words = new ArrayList<>();
    for (int[] token : tokens(folded)) {
      words.add(folded.substring(token[0], token[1]));
    }
    return words;
  }

  /** Ne reproduit pas les formes : la liste vient de données personnelles. */
  @Override
  public String toString() {
    return "NameDictionaryRecognizer[size=%d]".formatted(entries.size());
  }

  private static final class Candidate {
    final int firstToken;
    final int lastToken;
    final int start;
    final int end;
    final Entry entry;
    final boolean ambiguous;
    boolean accepted;

    Candidate(int firstToken, int lastToken, int start, int end, Entry entry, boolean acronym) {
      this.firstToken = firstToken;
      this.lastToken = lastToken;
      this.start = start;
      this.end = end;
      this.entry = entry;
      this.ambiguous = entry.ambiguous || acronym;
    }
  }
}
