package fr.gouv.travail.anonymisation.documents.word;

import fr.gouv.travail.anonymisation.core.anonymization.AnonymizationPlan;
import fr.gouv.travail.anonymisation.core.anonymization.AnonymizationPlan.Replacement;
import fr.gouv.travail.anonymisation.core.anonymization.Anonymizer;
import fr.gouv.travail.anonymisation.documents.InvalidDocumentException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Anonymise un .docx en réécrivant son XML, sans passer par une bibliothèque de mise en page : tout
 * ce qui n'est pas du texte est recopié octet pour octet, et seules les parties modifiées sont
 * réécrites.
 *
 * <p>Sont traités : les paragraphes du corps, des tableaux, des zones de texte, des en-têtes et
 * pieds de page, des notes et des commentaires ; le texte DrawingML (formes, graphiques) ; les
 * codes de champ (un lien {@code mailto:}) ; les textes alternatifs des images ; les données
 * personnalisées liées aux contrôles de contenu ; les propriétés du document ; les liens externes.
 * Les auteurs de commentaires et de révisions, le créateur, le dernier auteur, la société et le
 * responsable sont effacés. La vignette et les signatures numériques (invalidées par toute
 * modification, et qui nomment le signataire) sont retirées.
 *
 * <p>Un nom coupé en plusieurs « runs » (« Jean » en gras puis « Dupont ») est retrouvé : on
 * reconstitue le texte du paragraphe, on y repère l'entité, puis on la remplace dans le premier run
 * concerné en retirant ce qu'elle occupait dans les suivants.
 *
 * <p>Ne sont pas traités : le texte des images, des objets incorporés et des classeurs liés à un
 * graphique.
 */
public final class DocxAnonymizer {

  public record Result(byte[] content, AnonymizationPlan plan) {}

  private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
  private static final String A = "http://schemas.openxmlformats.org/drawingml/2006/main";
  private static final String CHART = "http://schemas.openxmlformats.org/drawingml/2006/chart";
  private static final String XML = "http://www.w3.org/XML/1998/namespace";
  private static final String DOCUMENT = "word/document.xml";
  private static final String ABOUT_BLANK = "about:blank";

  /** Éléments de {@code docProps} qui désignent une personne : toujours vidés. */
  private static final Set<String> PERSON_PROPERTIES =
      Set.of("creator", "lastModifiedBy", "Company", "Manager");

  /** Éléments de {@code docProps} qui contiennent du texte libre. */
  private static final Set<String> FREE_TEXT_PROPERTIES =
      Set.of(
          "title",
          "subject",
          "description",
          "keywords",
          "category",
          "contentStatus",
          "lpstr",
          "lpwstr");

  /** Attributs qui contiennent du texte libre (texte alternatif, info-bulle, code de champ). */
  private static final Set<String> FREE_TEXT_ATTRIBUTES =
      Set.of("descr", "title", "tooltip", "instr");

  /** Un texte à analyser et, le cas échéant, à réécrire. */
  private sealed interface Slot permits ParagraphSlot, LeafSlot {
    String part();

    String text();

    void apply(List<Replacement> replacements);
  }

  public Result anonymize(byte[] content, Anonymizer anonymizer) {
    OoxmlPackage pack = OoxmlPackage.read(content);
    if (!pack.entries().containsKey(DOCUMENT)) {
      throw new InvalidDocumentException("Document Word illisible : " + DOCUMENT + " absent.");
    }
    dropThumbnailAndSignatures(pack);

    // Le corps d'abord : la numérotation des marqueurs suit alors l'ordre de lecture.
    List<String> names = new ArrayList<>(pack.entries().keySet());
    names.sort((a, b) -> Boolean.compare(!a.equals(DOCUMENT), !b.equals(DOCUMENT)));

    Map<String, Document> documents = new LinkedHashMap<>();
    List<Slot> slots = new ArrayList<>();
    Set<String> changed = new HashSet<>();
    for (String name : names) {
      if (name.endsWith(".xml") || name.endsWith(".rels")) {
        Document document = parse(pack.entries().get(name));
        documents.put(name, document);
        PartScanner scanner = new PartScanner(name, slots);
        scanner.visit(document.getDocumentElement());
        if (scanner.modified) {
          changed.add(name);
        }
      }
    }

    AnonymizationPlan plan = anonymizer.plan(slots.stream().map(Slot::text).toList());
    for (int i = 0; i < slots.size(); i++) {
      List<Replacement> replacements = plan.replacements().get(i);
      if (!replacements.isEmpty()) {
        slots.get(i).apply(replacements);
        changed.add(slots.get(i).part());
      }
    }
    for (String name : changed) {
      pack.entries().put(name, serialize(documents.get(name)));
    }
    return new Result(pack.write(), plan);
  }

  private static void dropThumbnailAndSignatures(OoxmlPackage pack) {
    Set<String> removed = new HashSet<>();
    pack.entries()
        .keySet()
        .removeIf(
            name -> {
              boolean drop =
                  name.startsWith("docProps/thumbnail") || name.startsWith("_xmlsignatures/");
              if (drop) {
                removed.add("/" + name);
              }
              return drop;
            });
    byte[] relationships = pack.entries().get("_rels/.rels");
    if (relationships != null) {
      Document document = parse(relationships);
      removeElements(
          document,
          "Relationship",
          relationship -> {
            String type = relationship.getAttribute("Type");
            return type.endsWith("/thumbnail") || type.contains("/digital-signature/");
          });
      pack.entries().put("_rels/.rels", serialize(document));
    }
    byte[] contentTypes = pack.entries().get("[Content_Types].xml");
    if (contentTypes != null && !removed.isEmpty()) {
      Document document = parse(contentTypes);
      removeElements(
          document, "Override", override -> removed.contains(override.getAttribute("PartName")));
      pack.entries().put("[Content_Types].xml", serialize(document));
    }
  }

  private static void removeElements(
      Document document, String localName, java.util.function.Predicate<Element> condition) {
    NodeList elements = document.getElementsByTagNameNS("*", localName);
    for (int i = elements.getLength() - 1; i >= 0; i--) {
      Element element = (Element) elements.item(i);
      if (condition.test(element)) {
        element.getParentNode().removeChild(element);
      }
    }
  }

  private static boolean isParagraph(Node node) {
    return "p".equals(node.getLocalName())
        && (W.equals(node.getNamespaceURI()) || A.equals(node.getNamespaceURI()));
  }

  /** Repère, dans une partie XML, les textes à analyser et applique les effacements d'office. */
  private static final class PartScanner {

    private final String part;
    private final List<Slot> slots;
    private final boolean wordPart;
    private final boolean docProps;
    private final boolean customXml;
    private final boolean relationships;
    boolean modified;

    PartScanner(String part, List<Slot> slots) {
      this.part = part;
      this.slots = slots;
      this.wordPart = part.startsWith("word/") && !part.endsWith(".rels");
      this.docProps = part.startsWith("docProps/");
      this.customXml = part.startsWith("customXml/") && !part.contains("itemProps");
      this.relationships = part.endsWith(".rels");
    }

    void visit(Element element) {
      String local = element.getLocalName();
      if (wordPart) {
        if (isParagraph(element)) {
          slots.add(new ParagraphSlot(part, element));
        } else if (W.equals(element.getNamespaceURI())
            && ("instrText".equals(local) || "delInstrText".equals(local))) {
          leaf(element, false);
        } else if (CHART.equals(element.getNamespaceURI()) && "v".equals(local)) {
          leaf(element, false);
        }
        visitAttributes(element);
      } else if (docProps) {
        if (PERSON_PROPERTIES.contains(local) && !element.getTextContent().isBlank()) {
          element.setTextContent("");
          modified = true;
        } else if (FREE_TEXT_PROPERTIES.contains(local)) {
          leaf(element, false);
        }
      } else if (customXml && !hasChildElements(element)) {
        leaf(element, false);
      } else if (relationships
          && "Relationship".equals(local)
          && "External".equals(element.getAttribute("TargetMode"))) {
        // Un lien (mailto:, adresse personnelle…) : on ne peut y glisser un marqueur sans le
        // casser.
        leaf(element.getAttributeNode("Target"), true);
      }
      for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
        if (child instanceof Element childElement) {
          visit(childElement);
        }
      }
    }

    private void visitAttributes(Element element) {
      NamedNodeMap attributes = element.getAttributes();
      for (int i = 0; i < attributes.getLength(); i++) {
        Attr attribute = (Attr) attributes.item(i);
        String name =
            attribute.getLocalName() == null ? attribute.getName() : attribute.getLocalName();
        if (("author".equals(name) || "initials".equals(name)) && !attribute.getValue().isEmpty()) {
          attribute.setValue("author".equals(name) ? "Anonyme" : "");
          modified = true;
        } else if (FREE_TEXT_ATTRIBUTES.contains(name) && !attribute.getValue().isBlank()) {
          leaf(attribute, false);
        }
      }
    }

    private void leaf(Node node, boolean link) {
      if (node != null && !node.getTextContent().isBlank()) {
        slots.add(new LeafSlot(part, node, link));
      }
    }

    private static boolean hasChildElements(Element element) {
      for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
        if (child instanceof Element) {
          return true;
        }
      }
      return false;
    }
  }

  /** Un élément ou un attribut dont le texte tient d'un seul tenant. */
  private static final class LeafSlot implements Slot {

    private final String part;
    private final Node node;
    private final boolean link;

    LeafSlot(String part, Node node, boolean link) {
      this.part = part;
      this.node = node;
      this.link = link;
    }

    @Override
    public String part() {
      return part;
    }

    @Override
    public String text() {
      return node.getTextContent();
    }

    @Override
    public void apply(List<Replacement> replacements) {
      if (link) {
        node.setTextContent(ABOUT_BLANK);
        return;
      }
      StringBuilder result = new StringBuilder();
      String text = text();
      int cursor = 0;
      for (Replacement replacement : replacements) {
        result.append(text, cursor, replacement.start()).append(replacement.placeholder());
        cursor = replacement.end();
      }
      node.setTextContent(result.append(text, cursor, text.length()).toString());
    }
  }

  /**
   * Un paragraphe : le texte de ses runs mis bout à bout. Un caractère sait de quel élément de
   * texte il vient ; les tabulations et sauts de ligne, qui ne sont pas du texte, comptent comme
   * des séparateurs sans élément (un nom ne peut pas les enjamber sans qu'on le voie).
   */
  private static final class ParagraphSlot implements Slot {

    private final String part;
    private final List<Element> textNodes = new ArrayList<>();
    private final StringBuilder text = new StringBuilder();

    /** Pour chaque caractère, l'indice de son élément de texte, ou -1 pour un séparateur. */
    private final List<Integer> owner = new ArrayList<>();

    private boolean lastWasDeleted;

    ParagraphSlot(String part, Element paragraph) {
      this.part = part;
      collect(paragraph);
    }

    private void collect(Element parent) {
      for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
        if (!(child instanceof Element element) || isParagraph(element)) {
          continue; // un paragraphe imbriqué (zone de texte) est traité pour lui-même
        }
        String ns = element.getNamespaceURI();
        String local = element.getLocalName();
        boolean word = W.equals(ns);
        if ((word && ("t".equals(local) || "delText".equals(local)))
            || (A.equals(ns) && "t".equals(local))) {
          addText(element, "delText".equals(local));
        } else if (word && "tab".equals(local) && "r".equals(parent.getLocalName())) {
          separator('\t');
        } else if ((word || A.equals(ns)) && ("br".equals(local) || "cr".equals(local))) {
          separator('\n');
        } else {
          collect(element);
        }
      }
    }

    private void addText(Element element, boolean deleted) {
      // Le texte supprimé d'une révision et le texte qui l'entoure sont deux couches : sans
      // séparateur, « Dupont » supprimé puis « Ajouté » inséré se liraient « DupontAjouté ».
      if (deleted != lastWasDeleted
          && !text.isEmpty()
          && !Character.isWhitespace(text.charAt(text.length() - 1))) {
        separator(' ');
      }
      lastWasDeleted = deleted;
      String value = element.getTextContent();
      for (int i = 0; i < value.length(); i++) {
        owner.add(textNodes.size());
      }
      text.append(value);
      textNodes.add(element);
    }

    private void separator(char c) {
      text.append(c);
      owner.add(-1);
    }

    @Override
    public String part() {
      return part;
    }

    @Override
    public String text() {
      return text.toString();
    }

    @Override
    public void apply(List<Replacement> replacements) {
      List<StringBuilder> rewritten = new ArrayList<>();
      textNodes.forEach(node -> rewritten.add(new StringBuilder()));
      int cursor = 0;
      for (Replacement replacement : replacements) {
        copy(rewritten, cursor, replacement.start());
        // Le marqueur va dans le premier run concerné ; les suivants perdent ce que l'entité
        // couvrait.
        for (int c = replacement.start(); c < replacement.end(); c++) {
          if (owner.get(c) >= 0) {
            rewritten.get(owner.get(c)).append(replacement.placeholder());
            break;
          }
        }
        cursor = replacement.end();
      }
      copy(rewritten, cursor, text.length());

      for (int i = 0; i < textNodes.size(); i++) {
        Element node = textNodes.get(i);
        String value = rewritten.get(i).toString();
        if (!value.equals(node.getTextContent())) {
          node.setTextContent(value);
          node.setAttributeNS(XML, "xml:space", "preserve");
        }
      }
    }

    private void copy(List<StringBuilder> rewritten, int from, int to) {
      for (int c = from; c < to; c++) {
        if (owner.get(c) >= 0) {
          rewritten.get(owner.get(c)).append(text.charAt(c));
        }
      }
    }
  }

  private static Document parse(byte[] xml) {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setXIncludeAware(false);
      factory.setExpandEntityReferences(false);
      return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    } catch (ParserConfigurationException | SAXException | IOException e) {
      throw new InvalidDocumentException("Document illisible : XML invalide.", e);
    }
  }

  private static byte[] serialize(Document document) {
    try {
      TransformerFactory factory = TransformerFactory.newInstance();
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
      document.setXmlStandalone(true);
      Transformer transformer = factory.newTransformer();
      transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
      transformer.setOutputProperty(OutputKeys.STANDALONE, "yes");
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      transformer.transform(new DOMSource(document), new StreamResult(output));
      return output.toByteArray();
    } catch (TransformerException e) {
      throw new InvalidDocumentException("Écriture du document impossible.", e);
    }
  }
}
