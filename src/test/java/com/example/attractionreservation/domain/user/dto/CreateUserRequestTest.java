
package com.example.attractionreservation.domain.user.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CreateUserRequestTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        validatorFactory.close();
    }

    @Test
    @DisplayName("72바이트 이하 비밀번호는 바이트 제한 검증을 통과한다")
    void passwordWithin72Bytes_passesByteLimitValidation() {
        // given
        CreateUserRequest request = createRequest("가".repeat(24));

        // when
        boolean result = request.isPasswordWithinByteLimit();

        // then
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("72바이트를 초과하는 비밀번호는 바이트 제한 검증에 실패한다")
    void passwordOver72Bytes_failsByteLimitValidation() {
        // given: 한글 24자(72바이트) + 영문 1자(1바이트)
        CreateUserRequest request = createRequest("가".repeat(24) + "a");

        // when
        boolean result = request.isPasswordWithinByteLimit();

        // then
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("비밀번호가 72바이트를 초과하면 Bean Validation 오류가 발생한다")
    void passwordOver72Bytes_hasValidationViolation() {
        // given
        CreateUserRequest request = createRequest("가".repeat(24) + "a");

        // when
        var violations = validator.validate(request);

        // then
        assertThat(violations)
                .anySatisfy(violation ->
                        assertThat(violation.getMessage())
                                .isEqualTo("비밀번호는 UTF-8 기준 72바이트 이하여야 합니다.")
                );
    }

    private CreateUserRequest createRequest(String password) {
        return new CreateUserRequest(
                "test@example.com",
                password,
                "테스트유저"
        );
    }
}
