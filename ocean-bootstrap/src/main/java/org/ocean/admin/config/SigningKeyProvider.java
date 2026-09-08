package org.ocean.admin.config;

import com.nimbusds.jose.jwk.RSAKey;

/** 按运行环境提供当前 JWT 签名密钥。 */
public interface SigningKeyProvider {

    RSAKey signingKey();
}
