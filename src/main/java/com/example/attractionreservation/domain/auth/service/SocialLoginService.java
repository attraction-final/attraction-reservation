package com.example.attractionreservation.domain.auth.service;

import com.example.attractionreservation.domain.auth.dto.SocialUserInfo;
import com.example.attractionreservation.domain.auth.repository.SocialLoginCodeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SocialLoginService {

    private final SocialMemberResolver socialMemberResolver;
    private final SocialLoginCodeRepository loginCodeRepository;

    public String login(SocialUserInfo info) {
        // 회원 저장 트랜잭션이 완료된 뒤에만 로그인 코드를 발급한다.
        Long userId = socialMemberResolver.resolve(info);
        return loginCodeRepository.issue(userId);
    }
}