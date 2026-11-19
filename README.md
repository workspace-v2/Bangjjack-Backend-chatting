# 방짝 Backend - 채팅 고도화

기숙사 룸메이트 매칭 서비스 **방짝**의 1:1 채팅 백엔드를 다중 서버·장애 상황 기준으로 다시 설계하고, 개선 전후를 같은 조건에서 측정한 레포입니다.

- **원본**: 캡스톤 디자인 프로젝트 (2026.03 ~ 2026.06, 팀 5명 중 백엔드 2명) — [Bangjjack/Bangjjack-Backend](https://github.com/Bangjjack/Bangjjack-Backend)

## 기술 스택

| 구분 | 사용 기술 |
|---|---|
| Backend | Java 21, Spring Boot 4.0, Spring Data JPA, QueryDSL, WebSocket, Spring Security(OAuth2, JWT) |
| Data | MySQL, Redis(Redisson Pub/Sub), Flyway |
| Infra | AWS ECS, ALB, ECR, Terraform, GitHub Actions |
| 관측·테스트 | OpenTelemetry, Tempo, Prometheus, Grafana, k6, AWS FIS |

## 아키텍처

<img width="1000" height="auto" alt="image" src="https://github.com/user-attachments/assets/2ff77aa2-c4c4-417f-a409-5f12ef936631" />

- 각 서버는 자기에게 붙은 WebSocket 세션만 로컬에서 관리하고, 이벤트는 Redis Pub/Sub으로 모든 서버에 전달합니다.
- Pub/Sub은 유실될 수 있다는 전제로, **DB를 원본 데이터로 두고 빠진 메시지·구독을 DB에서 복구**합니다.

## 주요 개선 기능

- 1:1 채팅방 생성·메시지 전송·읽음 처리 (WebSocket)
- 다중 서버 환경의 실시간 전달 (Redis Pub/Sub)
- Redis 장애 시 누락 메시지·구독 복구 (채팅방별 seq 동기화, Transactional Outbox)
- 부하·장애 측정 환경 분리: `main`(개선 전 기준) / `dev`(개선 후)
