package org.ocean.admin.config;

import java.util.LinkedHashSet;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/** 将标准 scope、平台角色和业务权限统一映射为 Spring Security authorities。 */
final class OceanJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        LinkedHashSet<org.springframework.security.core.GrantedAuthority> authorities =
                new LinkedHashSet<>(scopes.convert(jwt));
        add(jwt, "roles", "ROLE_", authorities);
        add(jwt, "permissions", "", authorities);
        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }

    private static void add(
            Jwt jwt,
            String claim,
            String prefix,
            LinkedHashSet<org.springframework.security.core.GrantedAuthority> authorities) {
        for (String value : jwt.getClaimAsStringList(claim) == null
                ? java.util.List.<String>of() : jwt.getClaimAsStringList(claim)) {
            if (value != null && !value.isBlank()) {
                authorities.add(new SimpleGrantedAuthority(prefix + value));
            }
        }
    }
}
