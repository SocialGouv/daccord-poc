package fr.gouv.travail.anonymisation.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AnonymisationApplication {

  public static void main(String[] args) {
    SpringApplication.run(AnonymisationApplication.class, args);
  }
}
