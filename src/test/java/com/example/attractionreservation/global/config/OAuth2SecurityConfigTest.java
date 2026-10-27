package com.example.attractionreservation.global.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.util.UriComponentsBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringJUnitWebConfig({
        OAuth2SecurityConfig.class,
        OAuth2SecurityConfigTest.TestConfig.class
})
@ActiveProfiles("oauth")
class OAuth2SecurityConfigTest {

    @Autowired
    private WebApplicationContext context;

    @ParameterizedTest
    @CsvSource({
            "kakao, https://kauth.kakao.com/oauth/authorize",
            "google, https://accounts.google.com/o/oauth2/v2/auth"
    })
    @DisplayName("인증 없이 제공자 인증 화면으로 이동하고 인증 요청을 세션에 저장한다")
    void start_redirectsAndStoresAuthorizationRequest(
            String registrationId,
            String authorizationUri
    ) throws Exception {
        MockMvc mockMvc = webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        String path = "/oauth2/authorization/" + registrationId;

        var result = mockMvc.perform(get(path).servletPath(path))
                .andExpect(status().isFound())
                .andReturn();

        String location = result.getResponse().getHeader("Location");
        assertThat(location).isNotNull();

        var uri = UriComponentsBuilder.fromUriString(location).build();
        var parameters = uri.getQueryParams();

        assertThat(location).startsWith(authorizationUri + "?");
        assertThat(parameters.getFirst("client_id"))
                .isEqualTo("test-" + registrationId);
        assertThat(parameters.getFirst("response_type")).isEqualTo("code");
        assertThat(parameters.getFirst("state")).isNotBlank();

        // 인증 요청에 저장한 state와 콜백 주소가 리다이렉트 값과 같은지 확인한다.
        var repository = new HttpSessionOAuth2AuthorizationRequestRepository();
        var request = repository.loadAuthorizationRequest(
                authorizationResponseRequest(
                        parameters.getFirst("state"),
                        result.getRequest().getSession(false)
                )
        );

        assertThat(request).isNotNull();
        assertThat(request.getState()).isEqualTo(
                java.net.URLDecoder.decode(
                        parameters.getFirst("state"),
                        java.nio.charset.StandardCharsets.UTF_8
                )
        );
        assertThat(request.getRedirectUri()).isEqualTo(
                "http://localhost/login/oauth2/code/" + registrationId
        );
    }

    private org.springframework.mock.web.MockHttpServletRequest
    authorizationResponseRequest(
            String encodedState,
            jakarta.servlet.http.HttpSession session
    ) {
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setSession(session);
        request.setParameter(
                "state",
                java.net.URLDecoder.decode(
                        encodedState,
                        java.nio.charset.StandardCharsets.UTF_8
                )
        );
        return request;
    }

    @Configuration
    @EnableWebSecurity
    static class TestConfig {

        @Bean
        ClientRegistrationRepository clientRegistrationRepository() {
            // 외부 서버에는 접속하지 않으며 실제 키 대신 테스트 전용 값을 사용한다.
            return new InMemoryClientRegistrationRepository(
                    registration(
                            "kakao",
                            "https://kauth.kakao.com/oauth/authorize"
                    ),
                    registration(
                            "google",
                            "https://accounts.google.com/o/oauth2/v2/auth"
                    )
            );
        }

        private ClientRegistration registration(
                String registrationId,
                String authorizationUri
        ) {
            return ClientRegistration.withRegistrationId(registrationId)
                    .clientId("test-" + registrationId)
                    .clientSecret("test-secret")
                    .clientAuthenticationMethod(
                            ClientAuthenticationMethod.CLIENT_SECRET_POST
                    )
                    .authorizationGrantType(
                            AuthorizationGrantType.AUTHORIZATION_CODE
                    )
                    .redirectUri(
                            "{baseUrl}/login/oauth2/code/{registrationId}"
                    )
                    .authorizationUri(authorizationUri)
                    .tokenUri("https://example.com/token")
                    .build();
        }
    }
}