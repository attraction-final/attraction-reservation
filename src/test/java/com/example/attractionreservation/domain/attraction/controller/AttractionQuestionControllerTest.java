package com.example.attractionreservation.domain.attraction.controller;

import com.example.attractionreservation.domain.attraction.dto.AttractionCandidate;
import com.example.attractionreservation.domain.attraction.dto.CreateAttractionQuestionResponse;
import com.example.attractionreservation.domain.attraction.entity.enums.QuestionIntent;
import com.example.attractionreservation.domain.attraction.service.AttractionQuestionService;
import com.example.attractionreservation.domain.auth.jwt.JwtProvider;
import com.example.attractionreservation.domain.auth.security.JwtAccessDeniedHandler;
import com.example.attractionreservation.domain.auth.security.JwtAuthenticationEntryPoint;
import com.example.attractionreservation.domain.user.repository.UserRepository;
import com.example.attractionreservation.global.config.SecurityConfig;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 톱다운 1단계: API 계약(경로·인증·검증·응답 형태)을 고정한다.
// 서비스는 가짜로 두고, 분기 판단은 AttractionQuestionServiceTest가 따로 검증한다.
@WebMvcTest(AttractionQuestionController.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class
})
@EnableWebSecurity
class AttractionQuestionControllerTest {

    private static final String URL = "/api/attractions/questions";
    private static final int MAX_QUESTION_LENGTH = 200;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AttractionQuestionService attractionQuestionService;

    // 보안 필터가 필요로 하는 빈 (AdminAttractionControllerTest와 동일)
    @MockitoBean
    private JwtProvider jwtProvider;

    @MockitoBean
    private UserRepository userRepository;

    @Test
    @DisplayName("시나리오 1: 답할 수 있는 질문은 200과 ANSWERED, 어트랙션 정보를 돌려준다")
    void createAttractionQuestion_answered_returnsOk() throws Exception {
        // given
        String question = "모니모러쉬 키제한 있어?";
        String answer = "[모니모 러쉬] 키 130cm 이상이면 혼자 탈 수 있습니다. 키 195cm를 넘으면 탈 수 없습니다.";
        when(attractionQuestionService.answer(question))
                .thenReturn(CreateAttractionQuestionResponse.answered(
                        QuestionIntent.HEIGHT_LIMIT, 2L, "모니모 러쉬", answer));

        // when & then
        ask(question)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.type").value("ANSWERED"))
                .andExpect(jsonPath("$.data.intent").value("HEIGHT_LIMIT"))
                .andExpect(jsonPath("$.data.attractionId").value(2))
                .andExpect(jsonPath("$.data.attractionName").value("모니모 러쉬"))
                .andExpect(jsonPath("$.data.answer").value(answer))
                .andExpect(jsonPath("$.data.candidates").isEmpty());
    }

    @Test
    @DisplayName("시나리오 6: 되묻기도 정상 흐름이라 200으로 후보 목록을 돌려준다")
    void createAttractionQuestion_needClarification_returnsCandidates() throws Exception {
        // given
        String question = "롤러코스터 키 제한 알려줘";
        when(attractionQuestionService.answer(question))
                .thenReturn(CreateAttractionQuestionResponse.needClarification(
                        "어떤 어트랙션을 말씀하시는지 확실하지 않습니다.",
                        List.of(new AttractionCandidate(9L, "롤링 엑스 트레인"))));

        // when & then
        ask(question)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("NEED_CLARIFICATION"))
                .andExpect(jsonPath("$.data.attractionId").isEmpty())
                .andExpect(jsonPath("$.data.candidates[0].attractionId").value(9))
                .andExpect(jsonPath("$.data.candidates[0].name").value("롤링 엑스 트레인"));
    }

    @ParameterizedTest(name = "입력: \"{0}\"")
    @NullSource
    @ValueSource(strings = {"", "   "})
    @DisplayName("시나리오 11: 빈 질문은 400이고 Jev까지 가지 않는다")
    void createAttractionQuestion_blank_returnsBadRequest(String question) throws Exception {
        ask(question)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.question").value("질문을 입력해 주세요."));

        verifyNoInteractions(attractionQuestionService);
    }

    @Test
    @DisplayName("시나리오 12: 200자를 넘는 질문은 400이고 Jev까지 가지 않는다")
    void createAttractionQuestion_tooLong_returnsBadRequest() throws Exception {
        ask("가".repeat(MAX_QUESTION_LENGTH + 1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.question").value("질문은 200자 이하로 입력해 주세요."));

        verifyNoInteractions(attractionQuestionService);
    }

    @Test
    @DisplayName("시나리오 12 경계값: 정확히 200자인 질문은 통과한다")
    void createAttractionQuestion_maxLength_passes() throws Exception {
        // given
        String question = "가".repeat(MAX_QUESTION_LENGTH);
        when(attractionQuestionService.answer(question))
                .thenReturn(CreateAttractionQuestionResponse.unsupported("안내"));

        // when & then
        ask(question).andExpect(status().isOk());

        verify(attractionQuestionService).answer(question);
    }

    @Test
    @DisplayName("시나리오 13: 로그인하지 않으면 401이고 유료 API를 호출하지 않는다")
    void createAttractionQuestion_unauthenticated_returnsUnauthorized() throws Exception {
        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyOf("모니모러쉬 키제한 있어?")))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(attractionQuestionService);
    }

    @Test
    @DisplayName("시나리오 14·15: Jev를 쓸 수 없으면 503과 재시도 안내를 돌려준다")
    void createAttractionQuestion_jevUnavailable_returnsServiceUnavailable() throws Exception {
        // given
        String question = "모니모러쉬 키제한 있어?";
        when(attractionQuestionService.answer(question))
                .thenThrow(new BusinessException(ErrorCode.ATTRACTION_QUESTION_UNAVAILABLE));

        // when & then
        ask(question)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message")
                        .value("지금은 어트랙션 질문에 답할 수 없습니다. 잠시 후 다시 시도해 주세요."));
    }

    //헬퍼

    private ResultActions ask(String question) throws Exception {
        return mockMvc.perform(post(URL)
                .with(authenticatedVisitor())
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyOf(question)));
    }

    private String bodyOf(String question) {
        return question == null ? "{}" : "{\"question\":\"%s\"}".formatted(question);
    }

    private RequestPostProcessor authenticatedVisitor() {
        return authentication(new UsernamePasswordAuthenticationToken(
                1L,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        ));
    }
}