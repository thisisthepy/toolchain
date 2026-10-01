# PROJECT — toolchain

Kotlin Multiplatform 앱의 Python 부분을 Gradle `python { }` 블록으로 선언하게 해 주는 Gradle 플러그인,
그리고 Gradle 없이 쓰는 CLI `tcl`. 실제 작업(의존성 해석, 번들링)은 `pypackpack` 에 위임한다.

## 사양의 출처

| 무엇 | 어디 |
|---|---|
| 목표 DSL (사용자 작성) | 메인 체크아웃 루트의 `(플러그인예시)build.gradle.kts` — 추적되지 않는 파일, worktree 에는 없다 |
| 플러그인 체크리스트 | GitHub 이슈 `thisisthepy/toolchain#2` |
| toolchain-lite | GitHub 이슈 `thisisthepy/toolchain#1` (`tcl install pythonx-compose`) |
| 실행 가능한 부분집합 | `usage-example/build.gradle.kts` |
| 의도 / 계약 | `docs/INTENT.md` / `docs/SPEC.md` |

## 현재 상태 (2026-10-02, 코드와 테스트를 읽고 판정)

**구현됨 (테스트 있음)**
- `compileSdk` 버전 문자열 파싱 (alpha / rc / normal 채널)
- `platforms { }` → `pypackpack` 타깃 트리플 매핑, Kotlin 타깃 대조 경고, 최소 SDK 전달.
  `androidArm32`, `androidX86` 은 명시적으로 거부
- `debug` / `release` 빌드 타입, `-Ppython.buildType`, 변형 × 빌드 타입 태스크 그래프
- `compileLevel`: `instant` 만 지원, 나머지는 해당 변형의 태스크에서만 실패
- `commonMain` 의 `srcDirs` / `metaDirs` / `libDirs` 전달
- `implementation` / `integration` 의존성 → `uv add` (`pypackpack` 백엔드)
- `pypackpack` `ResourceBundler` 로 번들링
- 스테이징 복사 규칙과 플랫폼별 변형 선택
- `tcl install <package>`

**부분**
- 플러그인 적용 자체, `packagePython` zip, jar / APK 로의 스테이징 연결 — 이 저장소에 테스트 없음
- iOS: 스테이징만 되고 Xcode 프로젝트에 연결되지 않음
- 핫 리로드: Android 전용 `adb push` + 브로드캐스트. `serverHost`, `cert` 는 검증만
- 코드 푸시: 검증과 안내 태스크만, 업로드 없음
- 의존성이 소스셋별로 구분되지 않음 (전부 하나의 목록으로 설치)

**계획 (선언만 있거나 없음)**
- `bytecode` / `native` / `mixed` 컴파일 레벨 (`pypackpack` 쪽 선행 필요)
- `embedLevel` 의미, `useCodeMinifier`, `excludeMetaclass`, `buildFeatures`, `projectFlavors`,
  `defaultConfig { pip { } }`, `integration()` 의 `KLIBDEPENS` 검사, `compileSdk` 로 인터프리터 선택

## 구조

```
toolchain/       Gradle 플러그인  (dsl/, bundle/, dependency/, hotreload/)
tcl/             toolchain-lite CLI
usage-example/   플러그인을 적용한 Compose Multiplatform 앱 + python/ (pypackpack 패키지)
docs/            INTENT.md, SPEC.md, locale/, guide/ (GitHub Pages)
```

## 빌드와 테스트

전제: `~/.m2` 에 `packpack:0.1.0` (pypackpack 에서 `publishToMavenLocal`), `PATH` 에 `uv`, 네트워크.

```bash
./gradlew :toolchain:test --rerun --console=plain > .tmp/toolchain-test.log 2>&1; echo "EXIT=$?"
./gradlew :tcl:test --rerun --console=plain > .tmp/tcl-test.log 2>&1; echo "EXIT=$?"
./gradlew :toolchain:publishToMavenLocal     # usage-example 빌드 전에 반드시
python3 docs/guide/check_guide.py            # 가이드 검사
```

Python 테스트는 없다. 루트 `pyproject.toml` 이 가리키는 Python 패키지도 존재하지 않는다.

## 결정 사항

- **위임**: 의존성은 `BackendInterface.create(BackendType.UV)`, 번들은
  `BundlerInterface.create(BundleType.RESOURCE)`. 둘 다 `workingDir` 를 명시적으로 받는 백엔드 계층을
  호출한다 (`user.dir` 전역 상태 회피).
- **조용한 무시 금지**: 아무것도 읽지 않는 DSL 값은 거부하거나 SPEC 에 `planned` 로 남긴다.
- **변형별 실패**: 지원되지 않는 `compileLevel` 은 그 변형의 태스크만 실패시킨다.
- **스테이징은 `build/` 아래**: `src/` 에 생성물을 만들지 않는다.

## 열린 질문

1. 서버에 없는 `compileSdk` 버전을 자동 빌드할 것인가 — 예시 파일은 "자동으로 빌드 시도",
   이슈 #2 는 "do not support automatic build for new python release". 서로 어긋난다.
2. 플랫폼 DSL 모양 — 예시 파일의 최상위 `android("android") { }` + `listOf(...)` 인가, 코드의
   `platforms { android { variants(...) } }` 인가.
3. `pip { repositories { central / local / jit } }` 이름 — 예시 파일 철자를 따를 것인가.
4. 예시 파일에 없는 `localLibraryPath`, `-Ppython.buildType`, `adb` 기반 핫 리로드를 유지할 것인가.
5. 참조되지 않는 코드(`PythonMultiplatformPlugin.kt`, `reslover.kt`, `decompileKotlinMeta.kt`,
   `PythonLocalLoader.kt`, `DependencyType.kt`, `FrozenPackConfig` 등)와 빈 `pyproject.toml` 을
   지울 것인가.
