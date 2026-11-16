package fr.gouv.travail.anonymisation.api;

import fr.gouv.travail.anonymisation.core.anonymization.Anonymizer;
import fr.gouv.travail.anonymisation.core.detection.EntityDetector;
import fr.gouv.travail.anonymisation.core.detection.EntityRecognizer;
import fr.gouv.travail.anonymisation.core.detection.FrenchRecognizers;
import fr.gouv.travail.anonymisation.core.ner.OnnxNerRecognizer;
import fr.gouv.travail.anonymisation.core.verification.AnonymizationVerifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class AnonymisationConfiguration {

  /**
   * Absent si {@code anonymisation.ner.enabled=false}. Par défaut le NER est actif et l'application
   * refuse de démarrer sans modèle : un vérificateur qui tournerait sans NER donnerait de faux «
   * conforme ».
   */
  @Bean(destroyMethod = "close")
  @ConditionalOnProperty(prefix = "anonymisation.ner", name = "enabled", havingValue = "true")
  OnnxNerRecognizer onnxNerRecognizer(AnonymisationProperties properties) {
    AnonymisationProperties.Ner ner = properties.ner();
    return new OnnxNerRecognizer(Path.of(ner.modelDir()), ner.modelFile(), ner.maxChunkChars());
  }

  /** Motifs structurés d'abord : un IBAN validé l'emporte sur une entité NER qui le recouvre. */
  @Bean
  EntityDetector entityDetector(
      ObjectProvider<OnnxNerRecognizer> nerRecognizer, AnonymisationProperties properties) {
    List<EntityRecognizer> recognizers = new ArrayList<>(FrenchRecognizers.defaults());
    nerRecognizer.ifAvailable(recognizers::add);
    return new EntityDetector(recognizers, properties.types(), properties.minScore());
  }

  @Bean
  Anonymizer anonymizer(EntityDetector detector) {
    return new Anonymizer(detector);
  }

  @Bean
  AnonymizationVerifier anonymizationVerifier(EntityDetector detector) {
    return new AnonymizationVerifier(detector);
  }

  @Bean
  WordTextExtractor wordTextExtractor() {
    return new WordTextExtractor();
  }
}
