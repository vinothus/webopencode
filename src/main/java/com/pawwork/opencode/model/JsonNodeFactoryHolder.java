package com.pawwork.opencode.model;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/**
 * Single shared {@link JsonNodeFactory}. Keeps string-content nodes identical across the library
 * and avoids allocating a factory per message.
 */
final class JsonNodeFactoryHolder {

    static final JsonNodeFactory FACTORY = JsonNodeFactory.instance;

    private JsonNodeFactoryHolder() {
    }
}
