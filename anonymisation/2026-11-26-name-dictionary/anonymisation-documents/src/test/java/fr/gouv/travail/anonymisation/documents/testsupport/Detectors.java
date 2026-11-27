package fr.gouv.travail.anonymisation.documents.testsupport;

import fr.gouv.travail.anonymisation.core.anonymization.Anonymizer;
import fr.gouv.travail.anonymisation.core.detection.EntityDetector;
import fr.gouv.travail.anonymisation.core.detection.EntityRecognizer;
import fr.gouv.travail.anonymisation.core.detection.FrenchRecognizers;
import fr.gouv.travail.anonymisation.core.model.EntityType;
import fr.gouv.travail.anonymisation.core.testsupport.DictionaryRecognizer;
import fr.gouv.travail.anonymisation.core.verification.AnonymizationVerifier;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/** Détecteurs sans modèle : motifs structurés et quelques dictionnaires de valeurs connues. */
public final class Detectors {

  private Detectors() {}

  public static EntityDetector detector(EntityType type, String... terms) {
    List<EntityRecognizer> recognizers = new ArrayList<>(FrenchRecognizers.defaults());
    recognizers.add(new DictionaryRecognizer(type, terms));
    return new EntityDetector(recognizers, EnumSet.allOf(EntityType.class), 0.5);
  }

  /** Personnes du jeu d'essai (accord.docx, accord.doc) et leur société. */
  public static EntityDetector accordDetector() {
    List<EntityRecognizer> recognizers = new ArrayList<>(FrenchRecognizers.defaults());
    recognizers.add(new DictionaryRecognizer(EntityType.PERSONNE, "Jean Dupont", "Marie Martin"));
    recognizers.add(new DictionaryRecognizer(EntityType.ORGANISATION, "Dupont Frères"));
    recognizers.add(new DictionaryRecognizer(EntityType.LIEU, "Lyon"));
    return new EntityDetector(recognizers, EnumSet.allOf(EntityType.class), 0.5);
  }

  public static Anonymizer anonymizer(EntityType type, String... terms) {
    return new Anonymizer(detector(type, terms));
  }

  public static Anonymizer accordAnonymizer() {
    return new Anonymizer(accordDetector());
  }

  public static AnonymizationVerifier accordVerifier() {
    return new AnonymizationVerifier(accordDetector());
  }
}
