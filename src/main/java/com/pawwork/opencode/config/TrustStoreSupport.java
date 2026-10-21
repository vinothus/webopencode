package com.pawwork.opencode.config;

import java.io.File;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;

/**
 * Points the JVM's HTTPS trust at the project-local truststore, if there is one.
 *
 * <p>Some VPNs (corporate, Cloudflare One / WARP) intercept HTTPS and re-sign traffic with
 * their own root CA. Chrome and Edge trust it, because the VPN client installs it in the
 * Windows certificate store. The JVM does not - it has its own truststore - so every HTTPS
 * call fails with {@code PKIX path building failed ... unable to find valid certification path
 * to requested target}.</p>
 *
 * <p>The fix is to add the VPN root to a copy of the JDK truststore and hand that copy to the
 * JVM. Normally that is done with {@code -Djavax.net.ssl.trustStore=...} on the command line,
 * which is easy to forget - so this class does it from inside the application instead. It runs
 * before any HTTP client is built, which is what makes the setting stick: the JDK reads
 * {@code javax.net.ssl.trustStore} when the trust manager is initialised, not when the JVM
 * starts.</p>
 *
 * <p>Nothing here disables certificate verification. The JDK truststore is copied first, so all
 * the ordinary public CAs keep working; the VPN root is only ever added to the copy.</p>
 *
 * <p>Configure it with:</p>
 * <ul>
 *   <li>{@code -Dopencode.ssl.truststore=lib/cacerts} - path to the truststore</li>
 *   <li>{@code -Dopencode.ssl.truststore-password=changeit} - its password</li>
 *   <li>{@code OPENCODE_SSL_TRUSTSTORE} / {@code OPENCODE_SSL_TRUSTSTORE_PASSWORD} - the same,
 *       as environment variables</li>
 * </ul>
 */
public final class TrustStoreSupport {

    /** Where the truststore lives unless told otherwise. */
    public static final String DEFAULT_TRUSTSTORE = "lib/cacerts";

    /** The default password of a JDK truststore - and of the copy {@code setup-vpn-trust.bat} makes. */
    public static final String DEFAULT_PASSWORD = "changeit";

    private static final String TRUSTSTORE_PROPERTY = "javax.net.ssl.trustStore";
    private static final String PASSWORD_PROPERTY = "javax.net.ssl.trustStorePassword";

    private static String resolved;
    private static boolean configured;

    private TrustStoreSupport() {
    }

    /**
     * Sets {@code javax.net.ssl.trustStore} to the project-local truststore, when one exists.
     *
     * <p>Safe to call more than once; only the first call has an effect. Does nothing at all
     * when there is no truststore, so the JDK default (the public CAs) stays in charge.</p>
     *
     * @return the truststore that was configured, or {@code null} when there is none
     */
    public static synchronized String configure() {
        if (configured) {
            return resolved;
        }
        configured = true;

        String configuredPath = property("opencode.ssl.truststore", "OPENCODE_SSL_TRUSTSTORE");
        if (configuredPath == null) {
            configuredPath = DEFAULT_TRUSTSTORE;
        }
        String password = property("opencode.ssl.truststore-password", "OPENCODE_SSL_TRUSTSTORE_PASSWORD");
        if (password == null) {
            password = DEFAULT_PASSWORD;
        }

        File truststore = locate(configuredPath);
        if (truststore == null) {
            return null;
        }

        String absolute = truststore.getAbsolutePath();
        System.setProperty(TRUSTSTORE_PROPERTY, absolute);
        System.setProperty(PASSWORD_PROPERTY, password);
        resolved = absolute;
        return resolved;
    }

    /** The truststore in use, or {@code null} when the JDK default is in charge. */
    public static synchronized String trustStore() {
        return resolved;
    }

    /**
     * Finds the truststore.
     *
     * <p>An absolute path is taken as it is. A relative one is looked for next to the working
     * directory first (that is where {@code run.bat} starts the app) and then next to the jar
     * and one level above it, so the app finds it no matter where it was started from.</p>
     */
    static File locate(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        File candidate = new File(path);
        if (candidate.isAbsolute()) {
            return candidate.isFile() ? candidate : null;
        }

        List<File> candidates = new ArrayList<>();
        candidates.add(candidate);
        File home = jarDirectory();
        if (home != null) {
            candidates.add(new File(home, path));
            File parent = home.getParentFile();
            if (parent != null) {
                candidates.add(new File(parent, path));
            }
        }
        for (File file : candidates) {
            if (file.isFile()) {
                return file;
            }
        }
        return null;
    }

    /** The directory the running jar lives in, or {@code null} when it cannot be worked out. */
    private static File jarDirectory() {
        try {
            CodeSource source = TrustStoreSupport.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return null;
            }
            URL location = source.getLocation();
            File file = new File(location.toURI());
            if (file.isDirectory()) {
                return file;                    // running from target/classes
            }
            return file.getParentFile();       // running from the jar
        } catch (URISyntaxException | IllegalArgumentException e) {
            return null;
        }
    }

    /** A system property first, then an environment variable. */
    private static String property(String systemProperty, String environmentVariable) {
        String value = System.getProperty(systemProperty);
        if (value == null || value.isBlank()) {
            value = System.getenv(environmentVariable);
        }
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
