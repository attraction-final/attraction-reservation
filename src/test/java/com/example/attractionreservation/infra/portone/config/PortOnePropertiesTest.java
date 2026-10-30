package com.example.attractionreservation.infra.portone.config;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PortOnePropertiesTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    @Test
    @DisplayName("처리 중 응답 재시도 대기 시간이 음수면 설정 검증에 실패한다.")
    void cancelOutstandingRetryDelay_negative_ValidationFails() {
        //given
        PortOneProperties properties = new PortOneProperties();
        properties.setCancelOutstandingRetryDelay(Duration.ofMillis(-1));

        //when
        Set<ConstraintViolation<PortOneProperties>> violations =
                validator.validateProperty(properties, "cancelOutstandingRetryDelay");

        //then
        assertThat(violations).isNotEmpty();
    }

    @Test
    @DisplayName("처리 중 응답 재시도 대기 시간이 0이면 대기 없이 재시도하도록 허용한다.")
    void cancelOutstandingRetryDelay_zero_ValidationPasses() {
        //given
        PortOneProperties properties = new PortOneProperties();
        properties.setCancelOutstandingRetryDelay(Duration.ZERO);

        //when
        Set<ConstraintViolation<PortOneProperties>> violations =
                validator.validateProperty(properties, "cancelOutstandingRetryDelay");

        //then
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("처리 중 응답 재시도 대기 시간이 10초를 넘으면 멈춘 행 재점유 기준을 넘을 수 있으므로 설정 검증에 실패한다.")
    void cancelOutstandingRetryDelay_overMax_ValidationFails() {
        //given
        PortOneProperties properties = new PortOneProperties();
        properties.setCancelOutstandingRetryDelay(Duration.ofSeconds(10).plusMillis(1));

        //when
        Set<ConstraintViolation<PortOneProperties>> violations =
                validator.validateProperty(properties, "cancelOutstandingRetryDelay");

        //then
        assertThat(violations).isNotEmpty();
    }

    @Test
    @DisplayName("처리 중 응답 재시도 대기 시간이 상한인 10초면 허용한다.")
    void cancelOutstandingRetryDelay_max_ValidationPasses() {
        //given
        PortOneProperties properties = new PortOneProperties();
        properties.setCancelOutstandingRetryDelay(Duration.ofSeconds(10));

        //when
        Set<ConstraintViolation<PortOneProperties>> violations =
                validator.validateProperty(properties, "cancelOutstandingRetryDelay");

        //then
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("처리 중 응답 재시도 대기 시간을 설정하지 않으면 기본값 1초를 쓴다.")
    void cancelOutstandingRetryDelay_notSet_DefaultOneSecond() {
        //given & when
        PortOneProperties properties = new PortOneProperties();

        //then
        assertThat(properties.getCancelOutstandingRetryDelay()).isEqualTo(Duration.ofSeconds(1));
        assertThat(validator.validateProperty(properties, "cancelOutstandingRetryDelay")).isEmpty();
    }
}
