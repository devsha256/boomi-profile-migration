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
 * XmlToXsdConverter — corrected to normalize Boomi occurrence attributes:
 *  - treats -1 or any negative maxOccurs as "unbounded"
 *  - clamps minOccurs to >= 0
 *  - previous fixes retained (tns: qualification, merging siblings by name, facets)
 */
@Component
public class XmlToXsdConverter {

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
        out.append("<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\" ")
                .append("xmlns:tns=\"").append(targetNamespace).append("\" ")
                .append("targetNamespace=\"").append(targetNamespace).append("\" ")
                .append("elementFormDefault=\"qualified\">\n\n");

        // group top-level elements and preserve insertion order
        var grouped = XmlDomUtils.directChildrenByTag(dataElementsNode, "XMLElement")
                .stream()
                .collect(Collectors.groupingBy(e -> e.getAttribute("name"), LinkedHashMap::new, Collectors.toList()));

        for (var entry : grouped.entrySet()) {
            var rootName = entry.getKey();
            var elems = entry.getValue();
            var combined = combineElementsByName(elems); // sets normalized minOccurs/maxOccurs on representative
            String typeName = buildOrGetTypeForElement(combined, capitalize(rootName) + "Type");
            out.append(String.format("  <xs:element name=\"%s\" type=\"%s\"/>%n", rootName, qualifyType(typeName)));
        }

        out.append("\n");
        typeRegistry.values().forEach(s -> out.append(s).append("\n"));
        out.append("</xs:schema>");
        return out.toString();
    }

    /**
     * Combine siblings of same name into a representative Element.
     * Normalizes the occurrence attributes:
     *  - minOccurs -> numeric >= 0 (default 1)
     *  - maxOccurs -> numeric >=0 or "unbounded" (if any sibling implies unbounded or negative values like -1)
     */
    private Element combineElementsByName(List<Element> elems) {
        Element rep = elems.get(0);

        int combinedMin = elems.stream()
                .map(e -> Optional.ofNullable(e.getAttribute("minOccurs")).filter(s -> !s.isBlank()).orElse("1"))
                .mapToInt(XmlToXsdConverter::normalizeMin)
                .min().orElse(1);

        String combinedMax = elems.stream()
                .map(e -> Optional.ofNullable(e.getAttribute("maxOccurs")).filter(s -> !s.isBlank()).orElse("1"))
                .map(String::trim)
                .map(XmlToXsdConverter::normalizeMaxToString)
                .reduce((a, b) -> combineMaxValuesForStrings(a, b))
                .orElse("1");

        rep.setAttribute("minOccurs", String.valueOf(combinedMin));
        rep.setAttribute("maxOccurs", combinedMax);

        return rep;
    }

    // normalize min: parse integer, clamp to >=0, default 1
    private static int normalizeMin(String s) {
        try {
            int v = Integer.parseInt(s);
            return Math.max(0, v);
        } catch (Exception ex) {
            return 1;
        }
    }

    // normalize max: if "-1" or negative -> return "unbounded"; if numeric >=0 -> return numeric string
    private static String normalizeMaxToString(String s) {
        if (s == null) return "1";
        s = s.trim();
        // treat common Boomi negative indicator "-1" as unbounded
        if (s.equals("-1")) return "unbounded";
        try {
            int v = Integer.parseInt(s);
            if (v < 0) return "unbounded";
            return String.valueOf(v);
        } catch (Exception ex) {
            // if not a number and not "unbounded", try to accept literal "unbounded"
            if ("unbounded".equalsIgnoreCase(s)) return "unbounded";
            // otherwise fall back to "1"
            return "1";
        }
    }

    // combine two max strings (either numeric or "unbounded")
    private static String combineMaxValuesForStrings(String a, String b) {
        if ("unbounded".equalsIgnoreCase(a) || "unbounded".equalsIgnoreCase(b)) return "unbounded";
        try {
            int ia = Integer.parseInt(a);
            int ib = Integer.parseInt(b);
            return String.valueOf(Math.max(ia, ib));
        } catch (Exception ex) {
            return "unbounded";
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

        var rawChildren = XmlDomUtils.directChildrenByTag(elem, "XMLElement");
        var childrenByName = rawChildren.stream()
                .collect(Collectors.groupingBy(c -> c.getAttribute("name"), LinkedHashMap::new, Collectors.toList()));

        if (!childrenByName.isEmpty()) {
            w.append("    <xs:sequence>\n");
            for (var childEntry : childrenByName.entrySet()) {
                final String childName = childEntry.getKey();
                final List<Element> sameNamed = childEntry.getValue();
                final Element combinedChild = combineElementsByName(sameNamed);

                final String min = Optional.ofNullable(combinedChild.getAttribute("minOccurs")).filter(s -> !s.isBlank()).orElse("1");
                final String maxRaw = Optional.ofNullable(combinedChild.getAttribute("maxOccurs")).filter(s -> !s.isBlank()).orElse("1");
                final String max = normalizeMaxToString(maxRaw);

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

                        for (Element a : attrs) {
                            final String attrName = a.getAttribute("name");
                            final String attrTypeRaw = chooseBoomiType(a);
                            final String xsdAttrType = BoomiTypeMapper.toXsd(attrTypeRaw);
                            final boolean hasFacets = hasLengthOrPattern(a);
                            final boolean required = a.hasAttribute("required") && "true".equalsIgnoreCase(a.getAttribute("required"));

                            if (hasFacets) {
                                w.append(String.format("              <xs:attribute name=\"%s\">%n", attrName));
                                w.append("                <xs:simpleType>\n");
                                w.append(String.format("                  <xs:restriction base=\"%s\">%n", xsdAttrType == null ? "xs:string" : xsdAttrType));
                                maybeEmitLengthFacets(w, a);
                                maybeEmitPatternFacet(w, a);
                                w.append("                  </xs:restriction>\n");
                                w.append("                </xs:simpleType>\n");
                                w.append("              </xs:attribute>\n");
                            } else {
                                String use = required ? " use=\"required\"" : "";
                                w.append(String.format("              <xs:attribute name=\"%s\" type=\"%s\"%s/>%n",
                                        attrName, xsdAttrType == null ? "xs:string" : xsdAttrType, use));
                            }
                        }

                        w.append("            </xs:extension>\n");
                        w.append("          </xs:simpleContent>\n");
                        w.append("        </xs:complexType>\n");
                        w.append("      </xs:element>\n");
                    } else {
                        var boomiType = chooseBoomiType(combinedChild);
                        var xsdType = BoomiTypeMapper.toXsd(boomiType);
                        final boolean stringish = isStringish(boomiType);
                        final boolean hasFacets = hasLengthOrPattern(combinedChild);

                        if (stringish && hasFacets) {
                            w.append(String.format("      <xs:element name=\"%s\">%n", childName));
                            w.append("        <xs:simpleType>\n");
                            w.append(String.format("          <xs:restriction base=\"%s\">%n", xsdType == null ? "xs:string" : xsdType));
                            maybeEmitLengthFacets(w, combinedChild);
                            maybeEmitPatternFacet(w, combinedChild);
                            w.append("          </xs:restriction>\n");
                            w.append("        </xs:simpleType>\n");
                            w.append("      </xs:element>\n");
                        } else {
                            String typeOut = (xsdType == null || xsdType.isBlank()) ? "xs:string" : xsdType;
                            w.append(String.format("      <xs:element name=\"%s\" type=\"%s\" minOccurs=\"%s\" maxOccurs=\"%s\"/>%n",
                                    childName, typeOut, min, max));
                        }
                    }
                }
            }
            w.append("    </xs:sequence>\n");
        } else {
            var attrs = XmlDomUtils.directChildrenByTag(elem, "XMLAttribute");
            if (!attrs.isEmpty()) {
                var boomiType = chooseBoomiType(elem);
                var base = BoomiTypeMapper.toXsd(boomiType);
                if (base == null || base.isBlank()) base = "xs:string";

                w.append("    <xs:simpleContent>\n");
                w.append(String.format("      <xs:extension base=\"%s\">%n", base));

                for (Element a : attrs) {
                    final String attrName = a.getAttribute("name");
                    final String attrTypeRaw = chooseBoomiType(a);
                    final String xsdAttrType = BoomiTypeMapper.toXsd(attrTypeRaw);
                    final boolean hasFacets = hasLengthOrPattern(a);
                    final boolean required = a.hasAttribute("required") && "true".equalsIgnoreCase(a.getAttribute("required"));

                    if (hasFacets) {
                        w.append(String.format("        <xs:attribute name=\"%s\">%n", attrName));
                        w.append("          <xs:simpleType>\n");
                        w.append(String.format("            <xs:restriction base=\"%s\">%n", xsdAttrType == null ? "xs:string" : xsdAttrType));
                        maybeEmitLengthFacets(w, a);
                        maybeEmitPatternFacet(w, a);
                        w.append("            </xs:restriction>\n");
                        w.append("          </xs:simpleType>\n");
                        w.append("        </xs:attribute>\n");
                    } else {
                        String use = required ? " use=\"required\"" : "";
                        w.append(String.format("        <xs:attribute name=\"%s\" type=\"%s\"%s/>%n",
                                attrName, xsdAttrTypeOrDefault(xsdAttrType), use));
                    }
                }

                w.append("      </xs:extension>\n");
                w.append("    </xs:simpleContent>\n");
            } else {
                var boomiType = chooseBoomiType(elem);
                var xsdBase = BoomiTypeMapper.toXsd(boomiType);
                final boolean stringish = isStringish(boomiType);
                final boolean hasFacets = hasLengthOrPattern(elem);
                if (stringish && hasFacets) {
                    w.append("    <xs:simpleType>\n");
                    w.append(String.format("      <xs:restriction base=\"%s\">%n", xsdBase == null ? "xs:string" : xsdBase));
                    maybeEmitLengthFacets(w, elem);
                    maybeEmitPatternFacet(w, elem);
                    w.append("      </xs:restriction>\n");
                    w.append("    </xs:simpleType>\n");
                } else {
                    if (xsdBase == null || xsdBase.isBlank()) xsdBase = "xs:string";
                    w.append(String.format("    <xs:simpleContent>%n      <xs:extension base=\"%s\"/>%n    </xs:simpleContent>%n", xsdBase));
                }
            }
        }

        for (Element a : XmlDomUtils.directChildrenByTag(elem, "XMLAttribute")) {
            final String attrName = a.getAttribute("name");
            final String attrTypeRaw = chooseBoomiType(a);
            final String xsdAttrType = BoomiTypeMapper.toXsd(attrTypeRaw);
            final boolean hasFacets = hasLengthOrPattern(a);
            final boolean required = a.hasAttribute("required") && "true".equalsIgnoreCase(a.getAttribute("required"));

            if (hasFacets) {
                w.append(String.format("    <xs:attribute name=\"%s\">%n", attrName));
                w.append("      <xs:simpleType>\n");
                w.append(String.format("        <xs:restriction base=\"%s\">%n", xsdAttrType == null ? "xs:string" : xsdAttrType));
                maybeEmitLengthFacets(w, a);
                maybeEmitPatternFacet(w, a);
                w.append("        </xs:restriction>\n");
                w.append("      </xs:simpleType>\n");
                w.append("    </xs:attribute>\n");
            } else {
                String use = required ? " use=\"required\"" : "";
                w.append(String.format("    <xs:attribute name=\"%s\" type=\"%s\"%s/>%n",
                        attrName, xsdAttrTypeOrDefault(xsdAttrType), use));
            }
        }

        w.append("  </xs:complexType>");
        typeRegistry.put(fingerprint, w.toString());
        fingerprintToTypeName.put(fingerprint, typeName);
        return typeName;
    }

    // helpers (facets, fingerprinting, etc.)

    private static boolean isStringish(String boomiType) {
        if (boomiType == null) return true;
        String l = boomiType.toLowerCase();
        return l.equals("character") || l.equals("string") || l.equals("varchar") || l.equals("text");
    }

    private static boolean hasLengthOrPattern(Element node) {
        if (node == null) return false;
        return node.hasAttribute("maxLength") || node.hasAttribute("minLength") || node.hasAttribute("pattern");
    }

    private static void maybeEmitLengthFacets(StringWriter w, Element node) {
        if (node == null) return;
        if (node.hasAttribute("minLength")) {
            try {
                int min = Integer.parseInt(node.getAttribute("minLength"));
                w.append(String.format("            <xs:minLength value=\"%d\"/>%n", Math.max(0, min)));
            } catch (Exception ignored) { }
        }
        if (node.hasAttribute("maxLength")) {
            try {
                int max = Integer.parseInt(node.getAttribute("maxLength"));
                if (max >= 0) w.append(String.format("            <xs:maxLength value=\"%d\"/>%n", max));
            } catch (Exception ignored) { }
        }
    }

    private static void maybeEmitPatternFacet(StringWriter w, Element node) {
        if (node == null) return;
        if (node.hasAttribute("pattern")) {
            var p = node.getAttribute("pattern");
            if (!p.isBlank()) {
                w.append(String.format("            <xs:pattern value=\"%s\"/>%n", escapeForXmlAttr(p)));
            }
        }
    }

    private static String escapeForXmlAttr(String s) {
        return s.replace("\"", "&quot;").replace("<","&lt;").replace(">","&gt;");
    }

    private static String xsdAttrTypeOrDefault(String t) {
        return (t == null || t.isBlank()) ? "xs:string" : t;
    }

    private String fingerprint(Element e) {
        var sb = new StringBuilder();
        sb.append(Optional.ofNullable(e.getAttribute("name")).orElse("")).append("|");
        XmlDomUtils.directChildrenByTag(e, "XMLElement")
                .stream()
                .collect(Collectors.groupingBy(c -> c.getAttribute("name"), TreeMap::new, Collectors.toList()))
                .forEach((childName, list) -> {
                    int combinedMin = list.stream()
                            .map(c -> Optional.ofNullable(c.getAttribute("minOccurs")).filter(s -> !s.isBlank()).orElse("1"))
                            .mapToInt(XmlToXsdConverter::normalizeMin).min().orElse(1);
                    String combinedMax = list.stream()
                            .map(c -> Optional.ofNullable(c.getAttribute("maxOccurs")).filter(s -> !s.isBlank()).orElse("1"))
                            .map(String::trim)
                            .map(XmlToXsdConverter::normalizeMaxToString)
                            .reduce((a, b) -> combineMaxValuesForStrings(a, b))
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

        if (e.hasAttribute("minLength")) sb.append("minL=").append(e.getAttribute("minLength")).append("|");
        if (e.hasAttribute("maxLength")) sb.append("maxL=").append(e.getAttribute("maxLength")).append("|");
        if (e.hasAttribute("pattern")) sb.append("pat=").append(e.getAttribute("pattern")).append("|");

        return sb.toString();
    }

    private String qualifyType(String typeName) {
        if (typeName == null) return null;
        if (typeName.startsWith("xs:") || typeName.startsWith("tns:")) return typeName;
        return "tns:" + typeName;
    }

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

    private String capitalize(String s) {
        if (s == null || s.isBlank()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
