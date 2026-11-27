package fr.gouv.travail.anonymisation.documents;

/**
 * Le document produit contient encore une valeur que l'on s'était engagé à remplacer (texte dans
 * une zone que l'on ne sait pas réécrire, par exemple). On refuse de le livrer plutôt que de
 * laisser croire qu'il est anonymisé.
 */
public class IncompleteAnonymisationException extends RuntimeException {

  public IncompleteAnonymisationException(String message) {
    super(message);
  }
}
