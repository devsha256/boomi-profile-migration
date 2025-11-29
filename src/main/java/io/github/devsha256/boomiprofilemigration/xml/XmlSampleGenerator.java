package io.github.devsha256.boomiprofilemigration.xml;

import io.github.devsha256.boomiprofilemigration.internal.XmlDomUtils;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;

import java.io.File;
import java.io.StringWriter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Generates simple sample XML for a given <XMLProfile>.
 * Respects minOccurs (skip elements with minOccurs=0).
 */
@Component
public class XmlSampleGenerator {

    public String generateSample(File file) throws Exception {
        var doc = XmlDomUtils.loadDocument(file);
        var xmlProfile = (Element) doc.getElementsByTagName("XMLProfile").item(0);
        if (xmlProfile == null) throw new IllegalArgumentException("<XMLProfile> not found in input file");

        StringWriter out = new StringWriter();
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");

        XmlDomUtils.directChildrenByTag(xmlProfile, "XMLElement")
                .forEach(e -> writeElement(e, out, 0));

        return out.toString();
    }

    private void writeElement(Element elem, StringWriter out, int depth) {
        indent(out, depth);
        var name = elem.getAttribute("name");
        out.append('<').append(name);

        // attributes
        XmlDomUtils.directChildrenByTag(elem, "XMLAttribute").forEach(a ->
                out.append(' ').append(a.getAttribute("name")).append("=\"")
                        .append(sampleForType(a.getAttribute("type"))).append('"'));

        out.append('>');

        var children = XmlDomUtils.directChildrenByTag(elem, "XMLElement");
        if (!children.isEmpty()) {
            out.append('\n');
            for (var child : children) {
                var min = Optional.ofNullable(child.getAttribute("minOccurs")).filter(s -> !s.isBlank()).orElse("1");
                if (!"0".equals(min)) writeElement(child, out, depth + 1);
            }
            indent(out, depth);
            out.append("</").append(name).append(">\n");
        } else {
            out.append(sampleForType(elem.getAttribute("type")));
            out.append("</").append(name).append(">\n");
        }
    }

    private String sampleForType(String boomiType) {
        if (boomiType == null) return "sample";
        switch (Optional.of(boomiType.toLowerCase(Locale.ROOT)).orElse("string")) {
            case "character", "string" -> { return "sampleText"; }
            case "number", "decimal" -> { return "123.45"; }
            case "integer" -> { return "123"; }
            case "date" -> { return "2025-01-01"; }
            case "datetime" -> { return "2025-01-01T12:00:00"; }
            case "boolean" -> { return "true"; }
            default -> { return "sample"; }
        }
    }

    private void indent(StringWriter out, int depth) {
        out.append("  ".repeat(Math.max(0, depth)));
    }
}
