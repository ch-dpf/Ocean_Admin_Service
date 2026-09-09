package org.ocean.admin.platform.identity;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.ocean.admin.kernel.security.CurrentUser;
import org.ocean.admin.platform.api.CurrentUserAccessor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/** 从已验证 JWT 构造平台公共当前用户上下文。 */
@Component
public class SecurityCurrentUserAccessor implements CurrentUserAccessor {

    @Override
    public CurrentUser requiredCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)
                || !authentication.isAuthenticated()) {
            throw new IllegalStateException("An authenticated JWT principal is required");
        }
        Jwt jwt = jwtAuthentication.getToken();
        return new CurrentUser(
                uuidClaim(jwt, "user_id"),
                jwt.getSubject(),
                uuidClaim(jwt, "sid"),
                jwt.getClaimAsString("platform_code"),
                strings(jwt, "roles"),
                strings(jwt, "permissions"));
    }

    private static UUID uuidClaim(Jwt jwt, String name) {
        String value = jwt.getClaimAsString(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required JWT claim is missing: " + name);
        }
        return UUID.fromString(value);
    }

    private static Set<String> strings(Jwt jwt, String name) {
        List<String> values = jwt.getClaimAsStringList(name);
        return values == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(values));
    }
}
