package fr.gouv.travail.anonymisation.documents;

/** Document corrompu, protégé par mot de passe ou démesuré : on ne peut pas le traiter. */
public class InvalidDocumentException extends RuntimeException {

  public InvalidDocumentException(String message) {
    super(message);
  }

  public InvalidDocumentException(String message, Throwable cause) {
    super(message, cause);
  }
}
