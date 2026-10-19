# 패키지 구조와 역할 분리

- 계층형 패키지만 두기보다 도메인별 패키지 안에서 계층을 구분하는 구조를 사용한다.
- 실제 프로젝트 구조를 우선하고, 아래는 예시로만 취급한다.

```text
com.project
├── domain
│   ├── user/{controller,service,repository,entity,dto}
│   ├── attraction/{controller,service,repository,entity,dto}
│   └── reservation/{controller,service,repository,entity,dto}
└── global/{config,exception,common}
```

- Controller: HTTP 요청·응답 처리
- Service: 비즈니스 로직 및 트랜잭션 경계
- Repository: 데이터 조회·저장
- Entity: 영속화되는 도메인 객체
- DTO: API 요청·응답 객체
- 각 클래스는 해당 책임에 맞는 계층에 둔다.
