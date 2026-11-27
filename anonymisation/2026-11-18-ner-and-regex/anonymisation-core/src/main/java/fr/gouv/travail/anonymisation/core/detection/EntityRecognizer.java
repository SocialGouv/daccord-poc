package fr.gouv.travail.anonymisation.core.detection;

import fr.gouv.travail.anonymisation.core.model.DetectedEntity;
import java.util.List;

/** Moteur de détection : motifs structurés, modèle NER, dictionnaire… */
public interface EntityRecognizer {

  String name();

  List<DetectedEntity> recognize(String text);

  /** Vrai pour un moteur qui comprend le contexte (NER) ; faux pour de simples motifs. */
  default boolean contextual() {
    return false;
  }
}
