# ISPSender

[TransferProxy](https://github.com/YvanMazy/TransferProxy) 1.2.1+용 ASN/국가 기반 Minecraft 전송 플러그인입니다. Java 17 이상이 필요합니다.

## 1.1 기능

- ASN, 국가, **기본 서버, fallback 서버 모두 여러 개 등록**할 수 있습니다. 중복 항목은 가중치로 사용합니다.
- 모든 서버의 서비스 포트에 **10초마다 TCP 연결 확인**을 보내고, 살아 있는 서버 안에서 무작위로 분산합니다.
- ASN → 국가 코드 → 국가 이름 → 기본 서버 → fallback 순으로 살아 있는 서버를 찾습니다. 모든 경로가 죽었거나 fallback이 꺼져 있으면 설정한 메시지로 접속을 거절합니다.
- IPinfo Lite MMDB를 **매일 00:00에 한 번** 갱신합니다. 기본 시간대는 `Asia/Seoul`입니다. 같은 날 재시작하면 캐시를 재사용하고, 꺼져 있는 동안 날짜가 바뀌었으면 시작할 때 누락 갱신합니다.
- 한국어 웹패널에서 서버 상태, 전체 설정, 라우팅, 플레이어 접속 기록을 관리합니다. 밝은 배경과 간결한 카드로 구성한 Apple 스타일의 반응형 UI입니다.
- 웹패널은 비밀번호와 loopback/Tailscale 인터페이스로 보호합니다. 외부 CDN이나 분석 스크립트를 사용하지 않습니다.

## 설치 · 빌드

```sh
./gradlew test shadowJar
```

`build/libs/ISPSender-1.1.1.jar`를 프록시의 `plugins/`에 넣고 시작하면 `plugins/ISPSender/config.yml`이 생성됩니다. IPinfo 토큰과 실제 서버 주소를 설정하고 재시작하세요. 기존 1.0 설정도 읽을 수 있으며 새 항목은 기본값을 사용합니다.

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

서버 값은 단일 문자열 또는 YAML 목록을 사용합니다. 포트를 생략하면 25565입니다. ASN 키는 `4766`, `AS4766` 모두 지원하고 국가 코드는 대소문자를 구분하지 않습니다. IPv6 서버는 `[2001:db8::1]:25565` 형식을 사용합니다.

첫 상태 확인 전, 확인에 실패한 서버, 마지막 확인으로부터 30초가 지난 서버는 전송 대상에서 제외합니다. 복구된 서버는 다음 성공한 확인부터 다시 사용합니다. TCP 확인은 포트가 연결을 수락하는지를 확인하며 게임 내부 상태까지 검사하지는 않습니다. 확인 사이에 발생한 장애를 감지하기까지 최대 약 10초가 걸릴 수 있습니다.

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

- 테마: 우측 상단에서 자동(운영체제 설정), 라이트, 다크를 선택합니다. 선택은 브라우저에 저장되며 로그인 화면과 모든 관리 화면에 적용됩니다.

- 대시보드: 서버별 상태·지연 시간, DB 다운로드 날짜/다음 갱신, fallback 토글, 최근 접속.
- 서버·라우팅: 기본/fallback 풀, ASN/국가 규칙 추가·수정·삭제.
- 접속 기록: 날짜별 100건 페이지, 현재 페이지 검색, 선택한 날짜의 전체 JSON 내보내기.
- 설정: 토큰, DB, 시간대, 상태 확인 제한 시간, 메시지, 로그 보관 기간, 웹패널 설정 전체 편집.

라우팅, fallback, 메시지, 로그와 다운로드 설정은 즉시 반영됩니다. 웹 리스너·비밀번호 및 `database-file`은 저장 후 프록시를 재시작해야 적용되며 패널에 안내가 표시됩니다. 토큰/비밀번호 입력 칸은 다시 표시하지 않고 비워 두면 기존 값을 유지합니다. 환경변수는 프로세스를 재시작해 변경하세요.

세션은 12시간 후 만료되며 HttpOnly/SameSite 쿠키, CSRF 토큰, Origin 검사, 로그인 시도 제한으로 보호합니다. HTTPS 로그인에서는 Secure 쿠키를 설정합니다. 설정은 검증 후 파일을 원자적으로 교체하며 콘솔/웹 저장은 같은 경로를 사용합니다. 저장 시 YAML 주석과 서식은 정규화될 수 있습니다.

## 접속 기록

```yaml
log-lookups: true
connection-log-retention-days: 30
health-check-timeout-millis: 2000
no-server-message: "<red>No server is available for your connection."
```

`plugins/ISPSender/connection-logs/YYYY-MM-DD.jsonl`에 시각, 플레이어, 실제 IP, ASN, 국가, 대상 서버, 규칙, 전송/거절 결과를 비동기로 기록합니다. 보관 기간은 1–365일이며 기본 30일입니다. 프록시는 전송 패킷을 보내는 역할이므로 `transferred`는 목적지에서의 최종 로그인 성공을 의미하지 않습니다. 이 프록시에 대한 접속 기록이며 목적지 서버의 게임 내 활동은 수집하지 않습니다.

`log-lookups`는 콘솔 출력만 제어합니다. 파일 기록은 항상 남기며, 현재 프로세스의 최근 500건은 대시보드에, 재시작 전 기록을 포함한 전체 보관 기록은 날짜별 기록 탭에 표시합니다. 오래된 파일은 날짜가 바뀐 후 다음 기록을 쓸 때 정리합니다. 디스크가 쓰기 불가하거나 기록 큐가 가득 차면 경고를 출력하고 해당 파일 기록을 건너뜁니다.

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

기본 풀 분산, 10초 주기의 장애 감지, fallback 전송, 콘솔 on/off 영속화, 전체 장애 시 거절, 복구, 같은 날 재시작, 누락 날짜의 다운로드, 재시작 후 기록 조회까지 실제 Minecraft 프로토콜로 검증합니다. `--keep`을 붙이면 검증 후 테스트 패널을 열어 둘 수 있습니다. 테스트 전용 비밀번호는 스크립트에 있으며 운영용으로 사용하지 않습니다.
