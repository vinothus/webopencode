package com.pawwork.opencode.demo.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Small helpers for the controllers that answer with hand-built JSON. */
final class JsonResponses {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonResponses() {
    }

    /** Serialise a payload as a JSON response. */
    static ResponseEntity<String> ok(Object payload) {
        try {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                    .body(MAPPER.writeValueAsString(payload));
        } catch (IOException e) {                                  // jackson cannot fail here
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                    .body("{\"ok\":true}");
        }
    }

    /** The standard failure envelope: {@code {"ok":false,"error":"…"}}. */
    static ResponseEntity<String> error(int status, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", false);
        payload.put("error", message);
        try {
            return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                    .body(MAPPER.writeValueAsString(payload));
        } catch (IOException e) {                                  // jackson cannot fail here
            return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                    .body("{\"ok\":false,\"error\":\"" + message + "\"}");
        }
    }
}
