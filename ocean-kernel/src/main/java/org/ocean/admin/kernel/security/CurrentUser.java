package org.ocean.admin.kernel.security;

import java.util.Set;
import java.util.UUID;

public record CurrentUser(
        UUID userId,
        String username,
        UUID sessionId,
        String platformCode,
        Set<String> roles,
        Set<String> permissions
) {
}

