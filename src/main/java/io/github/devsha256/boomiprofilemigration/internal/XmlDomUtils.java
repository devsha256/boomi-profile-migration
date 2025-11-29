package io.github.devsha256.boomiprofilemigration.internal;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.IntStream;

/**
 * Small DOM helpers that emphasize functional style and Optional usage.
 */
public final class XmlDomUtils {

    public static org.w3c.dom.Document loadDocument(File file) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        DocumentBuilder db = dbf.newDocumentBuilder();
        return db.parse(file);
    }

    public static List<Element> directChildrenByTag(Element parent, String tagName) {
        NodeList list = parent.getElementsByTagName(tagName);
        List<Element> out = new ArrayList<>();
        IntStream.range(0, list.getLength())
                .mapToObj(list::item)
                .filter(Node.class::isInstance)
                .map(Node.class::cast)
                .filter(n -> n.getParentNode() == parent)
                .map(n -> (Element) n)
                .forEach(out::add);
        return out;
    }

    public static Optional<Element> firstDirectChildByTag(Element parent, String tagName) {
        return directChildrenByTag(parent, tagName).stream().findFirst();
    }

    public static <T> Optional<T> findFirst(List<T> list, Predicate<T> predicate) {
        return list.stream().filter(predicate).findFirst();
    }
}
