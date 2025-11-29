package io.github.devsha256.boomiprofilemigration.xml;

import io.github.devsha256.boomiprofilemigration.internal.BoomiTypeMapper;
import io.github.devsha256.boomiprofilemigration.internal.XmlDomUtils;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;

import java.io.File;
import java.io.StringWriter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * XmlToXsdConverter — emits schema with a tns prefix bound to the targetNamespace
 * and prefixes user-defined type references with tns:. Keeps previous fixes:
 *  - locates XMLElement under <DataElements>
 *  - merges siblings by name combining min/max occurs
 *  - reuses named complexTypes via fingerprinting
 *  - correctly maps Boomi dataType/type to XSD primitives
 */
@Component
public class XmlToXsdConverter {

    // fingerprint -> generated complexType XML (string)
    private final LinkedHashMap<String, String> typeRegistry = new LinkedHashMap<>();
    private final Map<String, String> fingerprintToTypeName = new HashMap<>();
    private final Set<String> usedTypeNames = new HashSet<>();

    public String convert(File file) throws Exception {
        var doc = XmlDomUtils.loadDocument(file);
        var xmlProfile = (Element) doc.getElementsByTagName("XMLProfile").item(0);
        if (xmlProfile == null) throw new IllegalArgumentException("<XMLProfile> not found in input file");

        var dataElementsNode = (Element) Optional.ofNullable(xmlProfile.getElementsByTagName("DataElements").item(0))
                .orElseThrow(() -> new IllegalArgumentException("<DataElements> not found in XMLProfile"));

        var targetNamespace = Optional.ofNullable(xmlProfile.getAttribute("namespace"))
                .filter(s -> !s.isBlank())
                .orElse("http://example.com/profile");

        StringWriter out = new StringWriter();
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        // declare tns prefix bound to targetNamespace
        out.append("<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\" ")
                .append("xmlns:tns=\"").append(targetNamespace).append("\" ")
                .append("targetNamespace=\"").append(targetNamespace).append("\" ")
                .append("elementFormDefault=\"qualified\">\n\n");

        // Process top-level XMLElement children under DataElements — merged by name
        XmlDomUtils.directChildrenByTag(dataElementsNode, "XMLElement")
                .stream()
                .collect(Collectors.groupingBy(e -> e.getAttribute("name"), LinkedHashMap::new, Collectors.toList()))
                .forEach((rootName, elems) -> {
                    // combine occurrence ranges across duplicates
                    var combined = combineElementsByName(elems);
                    String typeName = buildOrGetTypeForElement(combined, capitalize(rootName) + "Type");
                    out.append(String.format("  <xs:element name=\"%s\" type=\"%s\"/>%n", rootName, qualifyType(typeName)));
                });

        out.append("\n");
        // emit named complex types (definitions live in targetNamespace)
        typeRegistry.values().forEach(s -> out.append(s).append("\n"));
        out.append("</xs:schema>");
        return out.toString();
    }

    // Merge siblings by name and set combined min/max on representative element
    private Element combineElementsByName(List<Element> elems) {
        Element rep = elems.get(0);

        int combinedMin = elems.stream()
                .map(e -> Optional.ofNullable(e.getAttribute("minOccurs")).filter(s -> !s.isBlank()).orElse("1"))
                .mapToInt(XmlToXsdConverter::parseMin)
                .min().orElse(1);

        String combinedMax = elems.stream()
                .map(e -> Optional.ofNullable(e.getAttribute("maxOccurs")).filter(s -> !s.isBlank()).orElse("1"))
                .map(String::trim)
                .reduce((a, b) -> combineMaxValues(a, b))
                .orElse("1");

        rep.setAttribute("minOccurs", String.valueOf(combinedMin));
        rep.setAttribute("maxOccurs", combinedMax);

        return rep;
    }

    private static int parseMin(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Exception ex) {
            return 1;
        }
    }

    private static String combineMaxValues(String a, String b) {
        if ("unbounded".equalsIgnoreCase(a) || "unbounded".equalsIgnoreCase(b)) return "unbounded";
        try {
            int ia = Integer.parseInt(a);
            int ib = Integer.parseInt(b);
            return String.valueOf(Math.max(ia, ib));
        } catch (Exception ex) {
            if ("unbounded".equalsIgnoreCase(a)) return "unbounded";
            if ("unbounded".equalsIgnoreCase(b)) return "unbounded";
            return a;
        }
    }

    private String buildOrGetTypeForElement(Element elem, String preferredTypeBase) {
        String fingerprint = fingerprint(elem);
        if (fingerprintToTypeName.containsKey(fingerprint)) {
            return fingerprintToTypeName.get(fingerprint);
        }
        String typeName = uniqueTypeName(preferredTypeBase);
        StringWriter w = new StringWriter();
        w.append(String.format("  <xs:complexType name=\"%s\">%n", typeName));

        // Gather children and merge siblings by name
        var rawChildren = XmlDomUtils.directChildrenByTag(elem, "XMLElement");
        var childrenByName = rawChildren.stream()
                .collect(Collectors.groupingBy(c -> c.getAttribute("name"), LinkedHashMap::new, Collectors.toList()));

        if (!childrenByName.isEmpty()) {
            w.append("    <xs:sequence>\n");
            for (var entry : childrenByName.entrySet()) {
                String childName = entry.getKey();
                List<Element> sameNamed = entry.getValue();
                Element combinedChild = combineElementsByName(sameNamed);

                String min = Optional.ofNullable(combinedChild.getAttribute("minOccurs")).filter(s -> !s.isBlank()).orElse("1");
                String max = Optional.ofNullable(combinedChild.getAttribute("maxOccurs")).filter(s -> !s.isBlank()).orElse("1");

                var nested = XmlDomUtils.directChildrenByTag(combinedChild, "XMLElement");
                if (!nested.isEmpty()) {
                    var childType = buildOrGetTypeForElement(combinedChild, capitalize(childName) + "Type");
                    w.append(String.format("      <xs:element name=\"%s\" type=\"%s\" minOccurs=\"%s\" maxOccurs=\"%s\"/>%n",
                            childName, qualifyType(childType), min, max));
                } else {
                    var attrs = XmlDomUtils.directChildrenByTag(combinedChild, "XMLAttribute");
                    if (!attrs.isEmpty()) {
                        var boomiType = chooseBoomiType(combinedChild);
                        var base = BoomiTypeMapper.toXsd(boomiType);
                        if (base == null || base.isBlank()) base = "xs:string";

                        w.append(String.format("      <xs:element name=\"%s\">%n", childName));
                        w.append("        <xs:complexType>\n");
                        w.append("          <xs:simpleContent>\n");
                        w.append(String.format("            <xs:extension base=\"%s\">%n", base));

                        attrs.forEach(a -> {
                            var attrType = chooseBoomiType(a);
                            var xsdAttrType = BoomiTypeMapper.toXsd(attrType);
                            if (xsdAttrType == null || xsdAttrType.isBlank()) xsdAttrType = "xs:string";
                            w.append(String.format("              <xs:attribute name=\"%s\" type=\"%s\"/>%n",
                                    a.getAttribute("name"), xsdAttrType));
                        });

                        w.append("            </xs:extension>\n");
                        w.append("          </xs:simpleContent>\n");
                        w.append("        </xs:complexType>\n");
                        w.append(String.format("      </xs:element>%n"));
                    } else {
                        var boomiType = chooseBoomiType(combinedChild);
                        var type = BoomiTypeMapper.toXsd(boomiType);
                        if (type == null || type.isBlank()) type = "xs:string";
                        w.append(String.format("      <xs:element name=\"%s\" type=\"%s\" minOccurs=\"%s\" maxOccurs=\"%s\"/>%n",
                                childName, type, min, max));
                    }
                }
            }
            w.append("    </xs:sequence>\n");
        } else {
            // leaf element: attributes or simpleContent
            var attrs = XmlDomUtils.directChildrenByTag(elem, "XMLAttribute");
            if (!attrs.isEmpty()) {
                var boomiType = chooseBoomiType(elem);
                var base = BoomiTypeMapper.toXsd(boomiType);
                if (base == null || base.isBlank()) base = "xs:string";
                w.append("    <xs:simpleContent>\n");
                w.append(String.format("      <xs:extension base=\"%s\">%n", base));
                attrs.forEach(a -> {
                    var attrType = chooseBoomiType(a);
                    var xsdAttrType = BoomiTypeMapper.toXsd(attrType);
                    if (xsdAttrType == null || xsdAttrType.isBlank()) xsdAttrType = "xs:string";
                    w.append(String.format("        <xs:attribute name=\"%s\" type=\"%s\"/>%n",
                            a.getAttribute("name"), xsdAttrType));
                });
                w.append("      </xs:extension>\n");
                w.append("    </xs:simpleContent>\n");
            } else {
                var boomiType = chooseBoomiType(elem);
                var base = BoomiTypeMapper.toXsd(boomiType);
                if (base == null || base.isBlank()) base = "xs:string";
                w.append(String.format("    <xs:simpleContent>%n      <xs:extension base=\"%s\"/>%n    </xs:simpleContent>%n", base));
            }
        }

        // element-level attributes
        XmlDomUtils.directChildrenByTag(elem, "XMLAttribute").forEach(a -> {
            var attrType = chooseBoomiType(a);
            var xsdAttrType = BoomiTypeMapper.toXsd(attrType);
            if (xsdAttrType == null || xsdAttrType.isBlank()) xsdAttrType = "xs:string";
            w.append(String.format("    <xs:attribute name=\"%s\" type=\"%s\"/>%n", a.getAttribute("name"), xsdAttrType));
        });

        w.append("  </xs:complexType>");
        typeRegistry.put(fingerprint, w.toString());
        fingerprintToTypeName.put(fingerprint, typeName);
        return typeName;
    }

    /**
     * Choose effective Boomi type: prefer dataType -> type -> string
     */
    private static String chooseBoomiType(Element node) {
        if (node == null) return "string";
        if (node.hasAttribute("dataType") && !node.getAttribute("dataType").isBlank()) return node.getAttribute("dataType");
        if (node.hasAttribute("type") && !node.getAttribute("type").isBlank()) return node.getAttribute("type");
        return "string";
    }

    private String uniqueTypeName(String preferred) {
        var name = preferred;
        var idx = 1;
        while (usedTypeNames.contains(name)) {
            name = preferred + idx++;
        }
        usedTypeNames.add(name);
        return name;
    }

    // fingerprint uses merged child groups to be stable across duplicate siblings
    private String fingerprint(Element e) {
        var sb = new StringBuilder();
        sb.append(Optional.ofNullable(e.getAttribute("name")).orElse("")).append("|");
        XmlDomUtils.directChildrenByTag(e, "XMLElement")
                .stream()
                .collect(Collectors.groupingBy(c -> c.getAttribute("name"), TreeMap::new, Collectors.toList()))
                .forEach((childName, list) -> {
                    int combinedMin = list.stream()
                            .map(c -> Optional.ofNullable(c.getAttribute("minOccurs")).filter(s -> !s.isBlank()).orElse("1"))
                            .mapToInt(XmlToXsdConverter::parseMin).min().orElse(1);
                    String combinedMax = list.stream()
                            .map(c -> Optional.ofNullable(c.getAttribute("maxOccurs")).filter(s -> !s.isBlank()).orElse("1"))
                            .map(String::trim)
                            .reduce((a, b) -> combineMaxValues(a, b))
                            .orElse("1");

                    sb.append(childName).append("[").append(combinedMin).append(",").append(combinedMax).append("]").append("{");

                    var nested = XmlDomUtils.directChildrenByTag(list.get(0), "XMLElement");
                    if (nested.isEmpty()) {
                        var t = list.get(0).hasAttribute("dataType") && !list.get(0).getAttribute("dataType").isBlank()
                                ? list.get(0).getAttribute("dataType")
                                : list.get(0).getAttribute("type");
                        sb.append(Optional.ofNullable(t).orElse("string"));
                    } else {
                        sb.append(fingerprint(list.get(0)));
                    }
                    sb.append("}|");
                });
        XmlDomUtils.directChildrenByTag(e, "XMLAttribute").forEach(a ->
                sb.append("@").append(a.getAttribute("name")).append(":")
                        .append(Optional.ofNullable(
                                a.hasAttribute("dataType") && !a.getAttribute("dataType").isBlank()
                                        ? a.getAttribute("dataType")
                                        : a.getAttribute("type"))
                                .orElse("string"))
                        .append("|")
        );
        return sb.toString();
    }

    private String qualifyType(String typeName) {
        if (typeName == null) return null;
        // primitive XSDs start with xs:, do not prefix them
        if (typeName.startsWith("xs:")) return typeName;
        // already qualified
        if (typeName.startsWith("tns:")) return typeName;
        return "tns:" + typeName;
    }

    private String capitalize(String s) {
        if (s == null || s.isBlank()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
