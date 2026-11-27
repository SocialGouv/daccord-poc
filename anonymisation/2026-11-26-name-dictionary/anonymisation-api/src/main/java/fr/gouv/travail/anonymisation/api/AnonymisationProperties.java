package fr.gouv.travail.anonymisation.api;

import fr.gouv.travail.anonymisation.core.model.EntityType;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Valeurs par défaut dans {@code application.yml}.
 *
 * @param types types d'entités anonymisés, et donc contrôlés par la vérification
 * @param minScore score minimal (0 à 1) pour retenir une entité
 */
@ConfigurationProperties(prefix = "anonymisation")
public record AnonymisationProperties(
    Set<EntityType> types, double minScore, Ner ner, Dictionnaire dictionnaire) {

  /**
   * @param enabled à {@code false}, seuls les motifs structurés sont utilisés
   * @param modelDir répertoire contenant {@code tokenizer.json}, {@code config.json} et le modèle.
   *     Volontairement un {@code String} : Spring convertirait un {@code Path} comme une ressource
   *     de servlet, et un chemin relatif tel que {@code ../models} serait rejeté.
   * @param modelFile fichier ONNX, relatif à {@code modelDir}
   * @param maxChunkChars taille des morceaux de texte soumis au modèle
   */
  public record Ner(boolean enabled, String modelDir, String modelFile, int maxChunkChars) {}

  /**
   * @param enabled à {@code false}, les prénoms et noms ne sont cherchés que par le NER
   * @param dir répertoire contenant {@code prenoms.tsv} et {@code noms.tsv}, construits par {@code
   *     scripts/download-dictionary.py}. Un {@code String}, pour la même raison que {@code
   *     Ner.modelDir}.
   */
  public record Dictionnaire(boolean enabled, String dir) {}
}
