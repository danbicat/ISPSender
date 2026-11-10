# ISPSender

[TransferProxy](https://github.com/YvanMazy/TransferProxy) 1.2.1+용 ASN/국가 기반 Minecraft 전송 플러그인입니다. Java 17 이상이 필요합니다.

## 1.2 기능

- ASN, 국가, **기본 서버, fallback 서버 모두 여러 개 등록**할 수 있습니다. 중복 항목은 가중치로 사용합니다.
- 서버마다 **10초마다 상태를 확인**합니다. 기본값은 Minecraft 상태 요청(`minecraft`)이며, 포트 연결만 보려면 `tcp`를 사용합니다. 연속 실패 횟수가 `health-failure-threshold`(기본 2회)에 이를 때까지는 서버를 유지합니다.
- 도메인 주소는 **SRV 레코드(`_minecraft._tcp.<도메인>`)를 따릅니다**. 전송 패킷에는 SRV가 가리키는 호스트와 포트를 담습니다. 자세한 규칙은 [SRV 연결 대상](#srv-연결-대상)을 보세요.
- ASN → 국가 코드 → 국가 이름 → 기본 서버 → fallback 순으로 살아 있는 서버를 찾습니다. 모든 경로가 죽었거나 fallback이 꺼져 있으면 설정한 메시지로 접속을 거절합니다.
- 서버 다운·복구와 접속 거절을 **Discord 웹훅으로 알릴 수 있습니다**. 기본값은 꺼짐입니다.
- IPinfo Lite MMDB를 **매일 00:00에 한 번** 갱신합니다. 기본 시간대는 `Asia/Seoul`입니다. 같은 날 재시작하면 캐시를 재사용하고, 꺼져 있는 동안 날짜가 바뀌었으면 시작할 때 누락 갱신합니다.
- 한국어 웹패널에서 개요, 서버·라우팅, 시뮬레이터, 통계, 접속 기록, 설정을 관리합니다. macOS 시스템 설정과 비슷한 구성이며 자동/라이트/다크 테마를 지원합니다.
- 웹패널은 비밀번호와 loopback/Tailscale 인터페이스로 보호합니다. 외부 CDN이나 분석 스크립트를 사용하지 않습니다.

## 설치 · 빌드

```sh
./gradlew test shadowJar
```

`build/libs/ISPSender-1.2.0.jar`를 프록시의 `plugins/`에 넣고 시작하면 `plugins/ISPSender/config.yml`이 생성됩니다. IPinfo 토큰과 실제 서버 주소를 설정하고 재시작하세요. 기존 1.1 설정도 그대로 읽으며 새 항목은 기본값을 사용합니다.

## 라우팅

```yaml
token: "YOUR_IPINFO_TOKEN" # IPINFO_TOKEN 환경변수가 우선합니다.

asn-servers:
  "AS4766":
    - "kt1.example.com:25565"
    - "kt2.example.com:25565"
country-servers:
  "KR": ["kr1.example.com:25565", "kr2.example.com:25565"]

default-server:
  - "global1.example.com:25565"
  - "global2.example.com:25565"

fallback-enabled: true
fallback-servers:
  - "backup1.example.com:25565"
  - "backup2.example.com:25565"
```

서버 값은 단일 문자열 또는 YAML 목록을 사용합니다. 포트를 생략하면 25565입니다. ASN 키는 `4766`, `AS4766` 모두 지원하고 국가 코드는 대소문자를 구분하지 않습니다. IPv6 서버는 `[2001:db8::1]:25565` 형식을 사용합니다. 도메인 이름에 포트를 생략하거나 `:25565`를 쓰면 SRV 규칙을 따릅니다.

## 상태 확인

`health-check-mode`는 `minecraft`(기본) 또는 `tcp`입니다.

- `minecraft`: TCP 연결 뒤 Minecraft 상태 요청을 보내고, 응답이 Minecraft 상태 패킷으로 시작하는지 확인합니다. 포트만 열려 있고 게임 응답이 없는 서버는 실패로 봅니다. 상태 JSON 전체는 읽지 않습니다.
- `tcp`: 포트가 연결을 받는지만 확인합니다. Minecraft가 아닌 대상에 사용하세요.

확인은 10초마다 하며, 각 확인은 `health-check-timeout-millis`(100–5000, 기본 2000) 안에 끝납니다. 연속 실패가 `health-failure-threshold`(1–10, 기본 2)에 이르면 서버를 전송 대상에서 제외합니다. 그 전까지는 살아 있는 상태를 유지하고, 한 번 성공하면 실패 횟수가 0으로 돌아갑니다.

```yaml
health-check-mode: "minecraft"
health-check-timeout-millis: 2000
health-failure-threshold: 2
```

실패 사유는 웹패널과 Discord 알림에 다음과 같이 표시됩니다.

| 사유 | 의미 |
|---|---|
| DNS 조회 실패 | 주소(SRV 대상 포함)를 찾을 수 없음 |
| 연결 거부 | 포트가 닫혀 있음 |
| 시간 초과 | 제한 시간 안에 응답이 없음 |
| 네트워크 도달 불가 | 경로가 없거나 기타 네트워크 오류 |
| Minecraft 응답 없음 | 연결은 되지만 상태 응답이 올바르지 않음 |

첫 확인 전이거나, 마지막 확인으로부터 30초가 지났거나, 실패 한도에 도달한 서버는 전송 대상에서 제외합니다. 확인 사이에 발생한 장애를 감지하기까지 최대 약 10초가 걸릴 수 있습니다.

## SRV 연결 대상

- 대상이 **포트 25565이고 IP 주소가 아닌 도메인 이름**이면 `_minecraft._tcp.<도메인>` SRV 레코드를 조회합니다. 일반 Minecraft 클라이언트와 같은 규칙입니다.
- 조회는 60초마다 한 번 합니다. 조회가 실패하면(시간 초과 등) 마지막으로 알려진 결과를 유지하고 다음 확인에서 다시 조회합니다. 레코드가 없으면(NXDOMAIN) SRV를 쓰지 않습니다.
- 여러 레코드가 있으면 우선순위(priority)가 가장 낮은 것, 그다음 가중치(weight)가 가장 큰 것, 마지막으로 `대상:포트` 문자열 순서가 앞선 것을 고릅니다. 같은 입력이면 항상 같은 대상을 고르므로 상태 확인과 전송이 같은 주소를 씁니다.
- 대상이 `.`이거나 잘못된 이름, 범위를 벗어난 포트이면 그 레코드는 무시합니다.
- SRV가 없으면 설정한 도메인을 그대로 씁니다. A/AAAA와 CNAME 주소 변환은 운영체제의 DNS가 처리합니다.
- 전송 패킷에는 SRV 대상의 호스트와 포트를 담습니다. 플레이어 접속 스레드는 DNS를 조회하지 않고, 상태 확인 스레드가 저장한 결과만 읽습니다.

예를 들어 `default-server: "mc.example.com"`에 `_minecraft._tcp.mc.example.com` SRV 레코드(대상 `game1.example.net`, 포트 25565)가 있으면 플레이어는 `game1.example.net:25565`로 전송됩니다. 웹패널의 **주소 확인**에서 SRV 기록, CNAME, 주소, 실제 연결 대상, 연결 가능 여부, Minecraft 응답을 한 번에 볼 수 있습니다.

## Discord 알림

```yaml
discord-alerts-enabled: true
discord-webhook-url: "https://discord.com/api/webhooks/<id>/<token>"
```

`ISPSENDER_DISCORD_WEBHOOK` 환경변수가 설정되어 있으면 그 값이 우선합니다. 웹패널은 저장된 URL을 다시 보여 주지 않으며, 입력 칸을 비워 두면 저장된 값을 유지합니다. 그래서 빈 값으로는 URL을 지울 수 없고, 알림을 끄려면 `discord-alerts-enabled: false`로 설정합니다. 형식이 맞지 않는 URL은 웹패널에서 저장되지 않습니다. 설정 파일에 잘못된 값이 있으면 경고를 남기고 알림만 건너뛰며, 시작과 라우팅에는 영향을 주지 않습니다.

- 서버 다운, 서버 복구, 사용 가능한 경로가 없어 접속이 거절된 경우를 알립니다. 접속 거절 알림은 5분에 한 번만 보냅니다.
- 이벤트는 2초 동안 모아 한 메시지로 보냅니다. 한 메시지에는 최대 20줄을 넣고 나머지는 `외 N건`으로 표시합니다.
- 메시지는 누구도 멘션하지 않습니다. 웹훅 URL은 로그에 남기지 않으며, 실패하면 HTTP 상태 코드만 기록합니다.
- 알림 대기열이 가득 차면 이벤트를 버립니다. 알림 때문에 플레이어 접속이나 상태 확인이 기다리지 않습니다.

콘솔 명령:

```text
fallback on
fallback off
fallback status
ispsender reload
```

`fallback on/off`는 즉시 적용하고 `config.yml`에도 저장합니다. `ispsender reload`는 수동으로 편집한 설정을 다시 읽습니다. 잘못된 설정은 적용하지 않습니다. 사용자 정의 터미널 구현을 쓰는 프록시는 기본 `BaseTerminalExecutor` 명령 등록 API를 지원해야 하며, 지원하지 않으면 웹패널이나 설정 파일을 사용하세요.

## 일일 DB 갱신

```yaml
database-url: "https://ipinfo.io/data/ipinfo_lite.mmdb?token={token}"
database-file: "ipinfo_lite.mmdb"
database-timezone: "Asia/Seoul"
connect-timeout-seconds: 15
read-timeout-seconds: 180
```

성공한 날짜를 `ipinfo_lite.mmdb.download-date`에 기록합니다. 예를 들어 1일 00시에 받은 뒤 23시에 종료하고 2일 02시에 실행하면 2일 DB를 시작 시 받습니다. 2일에 다시 시작하면 또 받지 않습니다. 기존 DB의 날짜 기록이 없거나 DB가 없거나 손상되었으면 시작 시 받습니다.

기존 DB는 즉시 메모리에 읽고 다운로드는 별도 스레드에서 수행합니다. 새 DB가 검증된 경우에만 기존 파일을 교체하고 메모리에서 교체합니다. 실패하면 기존 DB를 유지하며 5분 후 재시도합니다. DB가 없더라도 기본/fallback 라우팅은 동작합니다. 이전 `update-on-start`, `update-interval-minutes` 키는 호환을 위해 받지만 일일 정책이 우선하여 더 이상 실행 주기를 바꾸지 않습니다.

## 웹패널: Tailscale + 비밀번호

권장 구성은 **loopback + Tailscale Serve HTTPS**입니다. 프록시가 실행되는 서버에서 설정하세요.

```yaml
web-enabled: true
web-bind: "127.0.0.1"
web-port: 8080
web-password: "충분히-긴-고유한-관리자-비밀번호"
```

`ISPSENDER_WEB_PASSWORD` 환경변수로 비밀번호를 지정할 수도 있습니다. 환경변수가 설정 파일보다 우선하며 최소 12자입니다. 비밀번호를 정한 뒤 프록시를 재시작하고 다음 명령을 실행합니다.

```sh
tailscale serve --bg http://127.0.0.1:8080
```

[Tailscale Serve 공식 문서](https://tailscale.com/docs/reference/tailscale-cli/serve)에 따라 표시되는 tailnet 전용 HTTPS 주소로 접속한 뒤 비밀번호로 로그인하세요. Tailscale ACL/grants로 관리자 기기만 접근할 수 있게 제한할 수 있습니다. **공개 인터넷용 Funnel은 사용하지 마세요.**

직접 접속하려면 `web-bind`를 해당 서버에 실제 할당된 Tailscale IP로 바꾸고 `http://<tailscale-ip>:8080`으로 접속할 수 있습니다. `0.0.0.0`, `::`, 공인 IP, 일반 LAN 주소는 거부합니다. Tailscale 범위는 [공식 예약 주소](https://tailscale.com/docs/reference/reserved-ip-addresses)의 `100.64.0.0/10`, `fd7a:115c:a1e0::/48`을 사용합니다. 전달된 `X-Forwarded-For`는 접근 제어에 사용하지 않습니다. loopback 접근도 항상 비밀번호가 필요합니다.

웹패널 기능:

- 개요: 서버별 상태, 지연 시간, SRV 대상, 실패 횟수, fallback 토글, DB 다운로드 날짜와 다음 갱신, 최근 접속.
- 서버 · 라우팅: 주소 확인(SRV 기록, CNAME, 주소, 실제 연결 대상, 연결 가능 여부, Minecraft 응답, 지연), 기본/fallback 풀, ASN·국가 규칙 추가·수정·삭제.
- 시뮬레이터: IP 주소를 넣으면 규칙이 적용되는 순서, 선택된 규칙, 살아 있는 서버 수, 최종 전송 대상을 보여 줍니다. IP 주소만 받으며 DNS를 조회하지 않습니다.
- 통계: 오늘, 최근 7일, 30일의 전송·거절 건수와 거절률, 날짜별 추이, 상위 ASN·국가·서버.
- 접속 기록: 날짜별 100건 페이지. 검색어(최대 100자)는 서버가 선택한 날짜 전체에서 찾고, 선택한 날짜의 전체 JSON 내보내기를 제공합니다.
- 설정: 화면 테마, 상태 확인 방식과 제한 시간, 실패 한도, 접속 기록 보관 기간, Discord 알림, 토큰·DB, 웹패널 설정.

테마는 자동(운영체제 설정), 라이트, 다크 중에서 고르며 브라우저에 저장됩니다.

라우팅, fallback, 메시지, 로그, 다운로드, 상태 확인과 Discord 알림 설정은 즉시 반영됩니다. 웹 리스너·비밀번호 및 `database-file`은 저장 후 프록시를 재시작해야 적용되며 패널에 안내가 표시됩니다. 토큰, 비밀번호, Discord 웹훅 URL 입력 칸은 다시 표시하지 않으며, 비워 두면 기존 값을 유지합니다. 환경변수는 프로세스를 재시작해 변경하세요.

세션은 12시간 후 만료되며 HttpOnly/SameSite 쿠키, CSRF 토큰, Origin 검사, 로그인 시도 제한으로 보호합니다. HTTPS 로그인에서는 Secure 쿠키를 설정합니다. 설정은 검증 후 파일을 원자적으로 교체하며 콘솔/웹 저장은 같은 경로를 사용합니다. 저장 시 YAML 주석과 서식은 정규화될 수 있습니다.

## 접속 기록

```yaml
log-lookups: true
connection-log-retention-days: 30
health-check-timeout-millis: 2000
no-server-message: "<red>No server is available for your connection."
```

`plugins/ISPSender/connection-logs/YYYY-MM-DD.jsonl`에 시각, 플레이어, 실제 IP, ASN, 국가, 대상 서버, 규칙, 전송/거절 결과를 비동기로 기록합니다. 보관 기간은 1–365일이며 기본 30일입니다. 프록시는 전송 패킷을 보내는 역할이므로 `transferred`는 목적지에서의 최종 로그인 성공을 의미하지 않습니다. 이 프록시에 대한 접속 기록이며 목적지 서버의 게임 내 활동은 수집하지 않습니다.

`log-lookups`는 콘솔 출력만 제어합니다. 파일 기록은 항상 남기며, 현재 프로세스의 최근 500건은 대시보드에, 재시작 전 기록을 포함한 전체 보관 기록은 날짜별 기록 탭에 표시합니다. 날짜별 검색과 통계는 서버에서 파일을 한 줄씩 읽어 계산하므로 페이지 크기와 무관하게 메모리를 많이 쓰지 않습니다. 오래된 파일은 날짜가 바뀐 후 다음 기록을 쓸 때 정리합니다. 디스크가 쓰기 불가하거나 기록 큐가 가득 차면 경고를 출력하고 해당 파일 기록을 건너뜁니다.

## 기타

- TransferProxy의 PROXY protocol 실제 IP 조회 경로를 유지합니다.
- READY 이벤트에서 직접 전송하는 다른 플러그인과 함께 설치하지 마세요.
- IPinfo Lite DB는 [CC BY-SA 4.0](https://ipinfo.io/lite)이며 배포 JAR에 포함하지 않고 런타임에 받습니다.
- 테스트의 작은 합성 MMDB는 MaxMind의 Apache 2.0 테스트 데이터입니다. 테스트 리소스에만 포함됩니다.

## 재현 가능한 실제 프록시 검증

단위/HTTP 통합 테스트 외에, 패키징한 JAR와 공식 TransferProxy 배포본을 함께 실행하는 검증 스크립트를 제공합니다. Python 3.9+ 표준 라이브러리만 사용하며 loopback 포트와 합성 DB로 테스트합니다.

```sh
python3 scripts/smoke_test.py \
  --java /path/to/java17/bin/java \
  --proxy /path/to/TransferProxy-1.2.1.jar \
  --workdir /path/to/new-empty-test-directory
```

기본 풀 분산, 10초 주기의 장애 감지, fallback 전송, 콘솔 on/off 영속화, 전체 장애 시 거절, 복구, SRV 전용 도메인 전송, 같은 날 재시작, 누락 날짜의 다운로드, 재시작 후 기록 조회까지 실제 Minecraft 프로토콜로 검증합니다. SRV 검증을 위해 스크립트가 루프백 UDP DNS 서버를 띄우고 `-Dispsender.dns.server`로 프록시에 연결합니다. `--keep`을 붙이면 검증 후 테스트 패널을 열어 둘 수 있습니다. 테스트 전용 비밀번호는 스크립트에 있으며 운영용으로 사용하지 않습니다.
