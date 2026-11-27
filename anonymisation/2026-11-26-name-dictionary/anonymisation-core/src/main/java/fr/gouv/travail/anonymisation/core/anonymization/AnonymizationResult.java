package fr.gouv.travail.anonymisation.core.anonymization;

import fr.gouv.travail.anonymisation.core.model.EntityType;
import java.util.List;
import java.util.Map;

/**
 * @param text texte anonymisé
 * @param replacements nombre de remplacements par type (jamais les valeurs d'origine)
 * @param engines moteurs de détection utilisés
 */
public record AnonymizationResult(
    String text, Map<EntityType, Integer> replacements, List<String> engines) {}
