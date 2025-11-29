package io.github.devsha256.boomiprofilemigration.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import io.github.devsha256.boomiprofilemigration.internal.BoomiTypeMapper;
import io.github.devsha256.boomiprofilemigration.internal.XmlDomUtils;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;

import java.io.File;
import java.util.Optional;

/**
 * Generates sample JSON documents based on a <JSONProfile>.
 */
@Component
public class JsonSampleGenerator {

    private final ObjectMapper mapper = new ObjectMapper();

    public String generateSample(File file) throws Exception {
        var doc = XmlDomUtils.loadDocument(file);
        var jsonProfile = (Element) doc.getElementsByTagName("JSONProfile").item(0);
        if (jsonProfile == null) throw new IllegalArgumentException("<JSONProfile> not found in input file");

        ObjectNode root = mapper.createObjectNode();
        Optional.ofNullable(XmlDomUtils.directChildrenByTag(jsonProfile, "JSONObject"))
                .flatMap(list -> list.stream().findFirst())
                .ifPresent(obj -> {
                    var sample = buildObject(obj);
                    root.setAll(sample);
                });

        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
    }

    private ObjectNode buildObject(Element objElem) {
        var node = mapper.createObjectNode();
        XmlDomUtils.directChildrenByTag(objElem, "JSONObjectEntry").forEach(entry -> {
            var name = entry.getAttribute("name");
            var type = entry.getAttribute("type");

            if (!XmlDomUtils.directChildrenByTag(entry, "JSONObject").isEmpty()) {
                node.set(name, buildObject(XmlDomUtils.directChildrenByTag(entry, "JSONObject").get(0)));
            } else if (!XmlDomUtils.directChildrenByTag(entry, "JSONArray").isEmpty()) {
                var arr = XmlDomUtils.directChildrenByTag(entry, "JSONArray").get(0);
                var arrayNode = mapper.createArrayNode();
                if (!XmlDomUtils.directChildrenByTag(arr, "JSONObject").isEmpty()) {
                    arrayNode.add(buildObject(XmlDomUtils.directChildrenByTag(arr, "JSONObject").get(0)));
                } else {
                    var itemType = arr.getAttribute("itemType");
                    arrayNode.add(sampleForType(itemType));
                }
                node.set(name, arrayNode);
            } else {
                node.set(name, sampleForType(type));
            }
        });
        return node;
    }

    private JsonNode sampleForType(String boomiType) {
        if (boomiType == null) return mapper.getNodeFactory().textNode("sample");
        switch (Optional.of(boomiType.toLowerCase()).orElse("string")) {
            case "number", "decimal" -> { return mapper.getNodeFactory().numberNode(123.45); }
            case "integer" -> { return mapper.getNodeFactory().numberNode(123); }
            case "boolean" -> { return mapper.getNodeFactory().booleanNode(true); }
            default -> { return mapper.getNodeFactory().textNode("sampleText"); }
        }
    }
}
