package fr.gouv.travail.anonymisation.api;

import fr.gouv.travail.anonymisation.core.anonymization.Anonymizer;
import fr.gouv.travail.anonymisation.core.detection.EntityDetector;
import fr.gouv.travail.anonymisation.core.detection.EntityRecognizer;
import fr.gouv.travail.anonymisation.core.detection.FrenchRecognizers;
import fr.gouv.travail.anonymisation.core.detection.NameDictionaryRecognizer;
import fr.gouv.travail.anonymisation.core.ner.OnnxNerRecognizer;
import fr.gouv.travail.anonymisation.core.verification.AnonymizationVerifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.catalina.connector.Connector;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

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

  /**
   * Absent si {@code anonymisation.dictionnaire.enabled=false}. Actif, l'application refuse de
   * démarrer sans dictionnaire, comme sans modèle.
   */
  @Bean
  @ConditionalOnProperty(
      prefix = "anonymisation.dictionnaire",
      name = "enabled",
      havingValue = "true")
  NameDictionaryRecognizer nameDictionaryRecognizer(AnonymisationProperties properties) {
    return NameDictionaryRecognizer.load(Path.of(properties.dictionnaire().dir()));
  }

  /**
   * Motifs structurés d'abord : un IBAN validé l'emporte sur une entité NER qui le recouvre. Le
   * dictionnaire vient après le NER : il complète ce que le modèle a raté, sans découper un nom
   * complet que le modèle a trouvé.
   */
  @Bean
  EntityDetector entityDetector(
      ObjectProvider<OnnxNerRecognizer> nerRecognizer,
      ObjectProvider<NameDictionaryRecognizer> nameDictionary,
      AnonymisationProperties properties) {
    List<EntityRecognizer> recognizers = new ArrayList<>(FrenchRecognizers.defaults());
    nerRecognizer.ifAvailable(recognizers::add);
    nameDictionary.ifAvailable(recognizers::add);
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

  /**
   * Absent par défaut : l'application n'écoute alors que sur {@code server.port}. Avec {@code
   * anonymisation.http.port}, Tomcat écoute aussi en HTTP sur ce port : Spring Boot n'ouvre qu'un
   * port, que {@code server.ssl.*} fait passer en HTTPS (voir {@code run.sh}).
   */
  @Bean
  @ConditionalOnProperty(prefix = "anonymisation.http", name = "port")
  WebServerFactoryCustomizer<TomcatServletWebServerFactory> httpConnectorCustomizer(
      @Value("${anonymisation.http.port}") int port) {
    return factory -> {
      Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
      connector.setPort(port);
      factory.addAdditionalTomcatConnectors(connector);
    };
  }

  /**
   * Absent par défaut : l'API n'accepte alors que son propre domaine. Pour la page {@code
   * index.html} ouverte hors du serveur : {@code --anonymisation.cors.allowed-origins=*}.
   */
  @Bean
  @ConditionalOnProperty(prefix = "anonymisation.cors", name = "allowed-origins")
  WebMvcConfigurer corsConfigurer(
      @Value("${anonymisation.cors.allowed-origins}") String[] allowedOrigins) {
    return new WebMvcConfigurer() {
      @Override
      public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**").allowedOrigins(allowedOrigins).allowedMethods("POST");
      }
    };
  }
}
