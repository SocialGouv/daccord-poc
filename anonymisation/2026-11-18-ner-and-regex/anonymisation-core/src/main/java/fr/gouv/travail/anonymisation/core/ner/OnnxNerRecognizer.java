package fr.gouv.travail.anonymisation.core.ner;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.huggingface.tokenizers.jni.CharSpan;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.gouv.travail.anonymisation.core.detection.EntityRecognizer;
import fr.gouv.travail.anonymisation.core.model.DetectedEntity;
import fr.gouv.travail.anonymisation.core.ner.NerDecoder.TokenPrediction;
import fr.gouv.travail.anonymisation.core.ner.TextChunker.Chunk;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * NER en processus : un modèle HuggingFace de classification de tokens exporté en ONNX, exécuté par
 * ONNX Runtime, avec le tokenizer HuggingFace (bibliothèque native livrée dans le jar DJL).
 *
 * <p>Le répertoire du modèle contient {@code tokenizer.json}, {@code config.json} (pour {@code
 * id2label}) et le fichier {@code .onnx}. Testé avec {@code Xenova/camembert-ner} (CamemBERT, PER /
 * LOC / ORG / MISC) ; tout modèle de classification de tokens au même format convient.
 */
public final class OnnxNerRecognizer implements EntityRecognizer, AutoCloseable {

  public static final String NAME = "onnx-ner";

  /** Limite de CamemBERT, jetons spéciaux inclus. */
  private static final int MAX_TOKENS = 512;

  private final OrtEnvironment environment = OrtEnvironment.getEnvironment();
  private final OrtSession session;
  private final HuggingFaceTokenizer tokenizer;
  private final String[] labels;
  private final boolean needsTokenTypeIds;
  private final int maxChunkChars;

  public OnnxNerRecognizer(Path modelDir, String modelFile, int maxChunkChars) {
    Path model = requireReadable(modelDir.resolve(modelFile), modelDir);
    Path tokenizerFile = requireReadable(modelDir.resolve("tokenizer.json"), modelDir);
    Path config = requireReadable(modelDir.resolve("config.json"), modelDir);
    try {
      this.tokenizer =
          HuggingFaceTokenizer.builder()
              .optTokenizerPath(tokenizerFile)
              .optAddSpecialTokens(true)
              .optTruncation(false)
              .optPadding(false)
              .build();
      this.labels = readLabels(config);
      this.session = environment.createSession(model.toString(), new OrtSession.SessionOptions());
      this.needsTokenTypeIds = session.getInputNames().contains("token_type_ids");
    } catch (IOException | OrtException e) {
      throw new IllegalStateException("Chargement du modèle NER impossible : " + modelDir, e);
    }
    this.maxChunkChars = maxChunkChars;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public boolean contextual() {
    return true;
  }

  @Override
  public List<DetectedEntity> recognize(String text) {
    List<DetectedEntity> entities = new ArrayList<>();
    for (Chunk chunk : TextChunker.chunk(text, maxChunkChars)) {
      entities.addAll(recognizeChunk(chunk.text(), chunk.offset()));
    }
    return entities;
  }

  private List<DetectedEntity> recognizeChunk(String chunk, int offset) {
    if (chunk.isBlank()) {
      return List.of();
    }
    Encoding encoding = encode(chunk);
    long[] ids = encoding.getIds();
    if (ids.length > MAX_TOKENS && chunk.length() > 1) {
      // Texte très dense en tokens (chiffres, codes…) : on coupe en deux et on recommence.
      List<DetectedEntity> entities = new ArrayList<>();
      for (Chunk half : TextChunker.chunk(chunk, Math.max(1, chunk.length() / 2))) {
        entities.addAll(recognizeChunk(half.text(), offset + half.offset()));
      }
      return entities;
    }

    float[][] logits = infer(ids, encoding.getAttentionMask());
    CharSpan[] spans = encoding.getCharTokenSpans();
    long[] special = encoding.getSpecialTokenMask();
    List<TokenPrediction> tokens = new ArrayList<>();
    for (int i = 0; i < ids.length; i++) {
      if (special[i] == 1) {
        continue;
      }
      float[] probabilities = softmax(logits[i]);
      int best = argmax(probabilities);
      tokens.add(
          new TokenPrediction(
              labels[best], probabilities[best], spans[i].getStart(), spans[i].getEnd()));
    }
    return NerDecoder.decode(chunk, tokens, NAME).stream().map(e -> e.shift(offset)).toList();
  }

  private Encoding encode(String text) {
    // Le tokenizer natif n'est pas documenté comme thread-safe : on sérialise l'encodage
    // (négligeable devant l'inférence).
    synchronized (tokenizer) {
      return tokenizer.encode(text);
    }
  }

  private float[][] infer(long[] ids, long[] attentionMask) {
    Map<String, OnnxTensor> inputs = new HashMap<>();
    try {
      inputs.put("input_ids", OnnxTensor.createTensor(environment, new long[][] {ids}));
      inputs.put(
          "attention_mask", OnnxTensor.createTensor(environment, new long[][] {attentionMask}));
      if (needsTokenTypeIds) {
        inputs.put(
            "token_type_ids",
            OnnxTensor.createTensor(environment, new long[][] {new long[ids.length]}));
      }
      try (OrtSession.Result result = session.run(inputs)) {
        return ((float[][][]) result.get(0).getValue())[0];
      }
    } catch (OrtException e) {
      throw new IllegalStateException("Échec de l'inférence NER", e);
    } finally {
      inputs.values().forEach(OnnxTensor::close);
    }
  }

  private static String[] readLabels(Path config) throws IOException {
    JsonNode id2label = new ObjectMapper().readTree(config.toFile()).get("id2label");
    if (id2label == null || id2label.isEmpty()) {
      throw new IOException("« id2label » absent de " + config);
    }
    String[] labels = new String[id2label.size()];
    id2label
        .fields()
        .forEachRemaining(e -> labels[Integer.parseInt(e.getKey())] = e.getValue().asText());
    return labels;
  }

  private static Path requireReadable(Path file, Path modelDir) {
    if (!Files.isReadable(file)) {
      throw new IllegalStateException(
          "Fichier du modèle NER introuvable : %s (répertoire du modèle : %s). "
                  .formatted(
                      file.toAbsolutePath().normalize(), modelDir.toAbsolutePath().normalize())
              + "Exécuter scripts/download-model.sh ou désactiver le NER avec anonymisation.ner.enabled=false.");
    }
    return file;
  }

  private static float[] softmax(float[] logits) {
    float max = Float.NEGATIVE_INFINITY;
    for (float logit : logits) {
      max = Math.max(max, logit);
    }
    float[] probabilities = new float[logits.length];
    float sum = 0;
    for (int i = 0; i < logits.length; i++) {
      probabilities[i] = (float) Math.exp(logits[i] - max);
      sum += probabilities[i];
    }
    for (int i = 0; i < probabilities.length; i++) {
      probabilities[i] /= sum;
    }
    return probabilities;
  }

  private static int argmax(float[] values) {
    int best = 0;
    for (int i = 1; i < values.length; i++) {
      if (values[i] > values[best]) {
        best = i;
      }
    }
    return best;
  }

  @Override
  public void close() {
    try {
      session.close();
    } catch (OrtException e) {
      throw new IllegalStateException(e);
    } finally {
      tokenizer.close();
    }
  }
}
