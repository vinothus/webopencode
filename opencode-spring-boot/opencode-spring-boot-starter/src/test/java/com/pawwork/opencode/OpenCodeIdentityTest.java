package com.pawwork.opencode;

import com.pawwork.opencode.client.OpenCodeIdentity;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gateway only serves ids shaped like the ones its own client mints, so the shapes themselves
 * are part of the contract — these assertions mirror the Android reference client's comments.
 */
class OpenCodeIdentityTest {

    /** {@code ses_} + 26 lowercase hex characters. */
    private static final Pattern SESSION = Pattern.compile("^ses_[0-9a-f]{26}$");

    /** {@code msg_} + 12 hex + 14 alphanumeric. */
    private static final Pattern REQUEST = Pattern.compile("^msg_[0-9a-f]{12}[a-z0-9]{14}$");

    @Test
    void sessionIdMatchesTheShapeTheGatewayAccepts() {
        OpenCodeIdentity identity = new OpenCodeIdentity("my-stable-seed");
        assertThat(identity.sessionId()).matches(SESSION);
    }

    @Test
    void sessionIdIsDeterministicForTheSameSeed() {
        String first = new OpenCodeIdentity("seed-a").sessionId();
        String second = new OpenCodeIdentity("seed-a").sessionId();
        String other = new OpenCodeIdentity("seed-b").sessionId();

        assertThat(first).isEqualTo(second);
        assertThat(other).isNotEqualTo(first);
    }

    @Test
    void blankSeedStillProducesAValidSession() {
        assertThat(new OpenCodeIdentity(null).sessionId()).matches(SESSION);
        assertThat(new OpenCodeIdentity("   ").sessionId()).matches(SESSION);
    }

    @Test
    void requestIdsMatchTheCliShapeAndAreUnique() {
        OpenCodeIdentity identity = new OpenCodeIdentity("seed");
        Set<String> seen = new HashSet<>();

        for (int i = 0; i < 100; i++) {
            String id = identity.requestId();
            assertThat(id).matches(REQUEST);
            assertThat(seen.add(id)).as("request ids must not collide").isTrue();
        }
    }
}
