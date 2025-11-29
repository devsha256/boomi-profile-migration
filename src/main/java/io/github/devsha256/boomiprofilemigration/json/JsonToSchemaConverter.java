package io.github.devsha256.boomiprofilemigration.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.devsha256.boomiprofilemigration.internal.BoomiTypeMapper;
import io.github.devsha256.boomiprofilemigration.internal.XmlDomUtils;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;

import java.io.File;
import java.util.Optional;

/**
 * Converts a Boomi <JSONProfile> into JSON Schema (Draft-07).
 * Keeps logic functional using Jackson nodes.
 */
@Component
public class JsonToSchemaConverter {

    private final ObjectMapper mapper = new ObjectMapper();

    public String convert(File file) throws Exception {
        var doc = XmlDomUtils.loadDocument(file);
        var jsonProfile = (Element) doc.getElementsByTagName("JSONProfile").item(0);
        if (jsonProfile == null) throw new IllegalArgumentException("<JSONProfile> not found in input file");

        ObjectNode root = mapper.createObjectNode();
        root.put("$schema", "http://json-schema.org/draft-07/schema#");

        XmlDomUtils.directChildrenByTag(jsonProfile, "JSONObject")
                .stream()
                .findFirst()
                .ifPresent(obj -> {
                    var objSchema = processObject(obj);
                    var name = Optional.ofNullable(obj.getAttribute("name")).filter(s -> !s.isBlank());
                    name.ifPresentOrElse(n -> {
                        root.set("$ref", mapper.convertValue("#/definitions/" + n, JsonNode.class));
                        var defs = mapper.createObjectNode();
                        defs.set(n, objSchema);
                        root.set("definitions", defs);
                    }, () -> root.setAll(objSchema));
                });

        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
    }

    private ObjectNode processObject(Element objElem) {
        var node = mapper.createObjectNode();
        node.put("type", "object");
        var props = mapper.createObjectNode();
        var required = mapper.createArrayNode();

        XmlDomUtils.directChildrenByTag(objElem, "JSONObjectEntry").forEach(entry -> {
            var pname = entry.getAttribute("name");
            var ptype = entry.getAttribute("type");
            var isRequired = "true".equalsIgnoreCase(entry.getAttribute("required"));

            if (!XmlDomUtils.directChildrenByTag(entry, "JSONObject").isEmpty()) {
                var nested = XmlDomUtils.directChildrenByTag(entry, "JSONObject").get(0);
                props.set(pname, processObject(nested));
            } else if (!XmlDomUtils.directChildrenByTag(entry, "JSONArray").isEmpty()) {
                var arr = XmlDomUtils.directChildrenByTag(entry, "JSONArray").get(0);
                var arrNode = mapper.createObjectNode();
                arrNode.put("type", "array");

                if (!XmlDomUtils.directChildrenByTag(arr, "JSONObject").isEmpty()) {
                    arrNode.set("items", processObject(XmlDomUtils.directChildrenByTag(arr, "JSONObject").get(0)));
                } else {
                    var itemType = arr.getAttribute("itemType");
                    arrNode.set("items", mapper.createObjectNode().put("type", BoomiTypeMapper.toJsonType(itemType)));
                }
                props.set(pname, arrNode);
            } else {
                props.set(pname, mapper.createObjectNode().put("type", BoomiTypeMapper.toJsonType(ptype)));
            }

            if (isRequired) required.add(pname);
        });

        node.set("properties", props);
        if (required.size() > 0) node.set("required", required);
        return node;
    }
}
