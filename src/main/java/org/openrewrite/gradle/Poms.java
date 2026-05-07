/*
 * Copyright 2026 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.gradle;

import org.jspecify.annotations.Nullable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

final class Poms {

    private Poms() {
    }

    static @Nullable Document parse(File pomFile) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(pomFile);
        } catch (Exception e) {
            return null;
        }
    }

    static void readProperties(Document doc, Map<String, String> props) {
        Element root = doc.getDocumentElement();
        for (Element propsElem : directChildElements(root, "properties")) {
            NodeList children = propsElem.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                if (child instanceof Element propElem) {
                    props.put(propElem.getTagName(), propElem.getTextContent().trim());
                }
            }
        }
    }

    static Iterable<Element> directChildElements(Element parent, String tagName) {
        Set<Element> result = new LinkedHashSet<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element el && el.getTagName().equals(tagName)) {
                result.add(el);
            }
        }
        return result;
    }

    static @Nullable String directChildText(Element parent, String tagName) {
        for (Element el : directChildElements(parent, tagName)) {
            String text = el.getTextContent();
            return text == null ? null : text.trim();
        }
        return null;
    }

    static @Nullable String substitute(@Nullable String value, Map<String, String> props) {
        if (value == null) {
            return null;
        }
        String result = value;
        for (Map.Entry<String, String> e : props.entrySet()) {
            result = result.replace("${" + e.getKey() + "}", e.getValue());
        }
        return result;
    }
}
