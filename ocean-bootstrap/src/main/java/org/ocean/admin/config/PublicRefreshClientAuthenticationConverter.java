package org.ocean.admin.config;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationConverter;

/** 识别公共客户端的 refresh-token 与 token-revocation 请求。 */
final class PublicRefreshClientAuthenticationConverter implements AuthenticationConverter {

    static final String AUTHENTICATION_MARKER =
            PublicRefreshClientAuthenticationConverter.class.getName() + ".authorized-request";

    @Override
    public Authentication convert(HttpServletRequest request) {
        boolean refreshRequest = AuthorizationGrantType.REFRESH_TOKEN.getValue()
                .equals(request.getParameter(OAuth2ParameterNames.GRANT_TYPE))
                && exactlyOne(request, OAuth2ParameterNames.REFRESH_TOKEN);
        boolean revocationRequest = request.getRequestURI().endsWith("/oauth2/revoke")
                && exactlyOne(request, OAuth2ParameterNames.TOKEN);
        if ((!refreshRequest && !revocationRequest)
                || !exactlyOne(request, OAuth2ParameterNames.CLIENT_ID)
                || request.getParameter(OAuth2ParameterNames.CLIENT_SECRET) != null) {
            return null;
        }
        return new OAuth2ClientAuthenticationToken(
                request.getParameter(OAuth2ParameterNames.CLIENT_ID),
                ClientAuthenticationMethod.NONE, null,
                Map.of(AUTHENTICATION_MARKER, true));
    }

    private static boolean exactlyOne(HttpServletRequest request, String parameter) {
        String[] values = request.getParameterValues(parameter);
        return values != null && values.length == 1 && values[0] != null && !values[0].isBlank();
    }
}
