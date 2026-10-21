package com.pawwork.opencode.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The truststore has to be found and applied without anybody remembering a command line flag,
 * because a VPN that re-signs HTTPS breaks every outbound call.
 */
class TrustStoreSupportTest {

    private final String previousTrustStore = System.getProperty("javax.net.ssl.trustStore");
    private final String previousPassword = System.getProperty("javax.net.ssl.trustStorePassword");

    @AfterEach
    void restore() {
        if (previousTrustStore == null) {
            System.clearProperty("javax.net.ssl.trustStore");
        } else {
            System.setProperty("javax.net.ssl.trustStore", previousTrustStore);
        }
        if (previousPassword == null) {
            System.clearProperty("javax.net.ssl.trustStorePassword");
        } else {
            System.setProperty("javax.net.ssl.trustStorePassword", previousPassword);
        }
    }

    @Test
    void findsATruststoreRelativeToTheWorkingDirectory() throws Exception {
        Path dir = Files.createTempDirectory("truststore");
        File file = dir.resolve("cacerts").toFile();
        Files.write(file.toPath(), new byte[]{1, 2, 3});

        assertEquals(file.getCanonicalFile(),
                TrustStoreSupport.locate(dir.resolve("cacerts").toString()).getCanonicalFile());
    }

    @Test
    void ignoresAPathThatDoesNotExist() {
        assertNull(TrustStoreSupport.locate("definitely/not/here/cacerts"));
        assertNull(TrustStoreSupport.locate(null));
        assertNull(TrustStoreSupport.locate("   "));
    }

    @Test
    void theDefaultPathIsTheProjectLocalTruststore() {
        assertEquals("lib/cacerts", TrustStoreSupport.DEFAULT_TRUSTSTORE);
        assertEquals("changeit", TrustStoreSupport.DEFAULT_PASSWORD);
    }

    @Test
    void configuringWithoutATruststoreChangesNothing() {
        String before = System.getProperty("javax.net.ssl.trustStore");
        String trustStore = TrustStoreSupport.configure();
        if (trustStore == null) {
            // No truststore on this machine - the JDK default (the public CAs) stays in charge.
            assertEquals(before, System.getProperty("javax.net.ssl.trustStore"));
        } else {
            assertNotNull(System.getProperty("javax.net.ssl.trustStore"));
            assertEquals(trustStore, System.getProperty("javax.net.ssl.trustStore"));
        }
    }
}
