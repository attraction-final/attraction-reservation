---
description: 코드 변경사항 리뷰
argument-hint: [file-path]
allowed-tools: Read, Grep, Glob, Bash(git diff:*)
---

## 리뷰 대상

!`git diff --name-only HEAD~1`

## 변경 내역

!`git diff HEAD~1`

## 리뷰 체크리스트

1. 코드 품질 및 가독성
2. 보안 취약점
3. 성능 영향
4. 테스트 커버리지
5. 문서화 완성도

$ARGUMENTS 파일에 집중해서 검토해주세요.