package com.interviewagent.config;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.proc.SingleKeyJWSKeySelector;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import com.interviewagent.common.RestAuthenticationEntryPoint;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, RestAuthenticationEntryPoint entryPoint) throws Exception {
        return http.csrf(csrf -> csrf.disable()).cors(cors -> {})
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(entryPoint))
            .authorizeHttpRequests(auth -> auth.requestMatchers("/actuator/health", "/internal/agent/**").permitAll().anyRequest().authenticated())
            .oauth2ResourceServer(oauth -> oauth.authenticationEntryPoint(entryPoint).jwt(jwt -> {})).build();
    }

    @Bean
    JwtDecoder jwtDecoder(
        @Value("${app.supabase.url}") String supabaseUrl,
        @Value("${app.supabase.jwt-secret:}") String jwtSecret,
        @Value("${app.supabase.jwt-public-jwk.kid:}") String keyId,
        @Value("${app.supabase.jwt-public-jwk.x:}") String keyX,
        @Value("${app.supabase.jwt-public-jwk.y:}") String keyY
    ) throws Exception {
        String issuer = supabaseUrl + "/auth/v1";
        NimbusJwtDecoder decoder;
        if (!keyX.isBlank() && !keyY.isBlank()) {
            var publicKey = new ECKey.Builder(Curve.P_256, new Base64URL(keyX), new Base64URL(keyY)).keyID(keyId).build().toECPublicKey();
            var processor = new DefaultJWTProcessor<SecurityContext>();
            processor.setJWSKeySelector(new SingleKeyJWSKeySelector<>(JWSAlgorithm.ES256, publicKey));
            decoder = new NimbusJwtDecoder(processor);
        } else if (!jwtSecret.isBlank()) {
            decoder = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(jwtSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256")).macAlgorithm(MacAlgorithm.HS256).build();
        } else {
            decoder = NimbusJwtDecoder.withJwkSetUri(issuer + "/.well-known/jwks.json").build();
        }
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        return decoder;
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${app.cors.allowed-origin}") String allowedOrigin) {
        var config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(allowedOrigin));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
