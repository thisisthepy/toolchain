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

## 현재 상태 (2026-10-03, 코드와 테스트를 읽고 판정)

**구현됨 (테스트 있음)**
- `compileSdk` 버전 문자열 파싱 (alpha / rc / normal 채널), 그리고 이름 상수 `PY3_14_7`·`PY3_13_0`
  (python-multiplatform 이 런타임을 제공하는 버전만). 제공되지 않는 버전 문자열은 `buildPython…` 에서 이유와 함께 실패
- `python { }` 최상위의 `android("android") { }` / `ios { }` / `desktop()` 과 변형 호출
  (`androidArm64()` 등) → `pypackpack` 타깃 트리플 매핑, Kotlin 타깃 대조 경고, 최소 SDK 전달.
  안드로이드는 CPython 공식 지원(PEP 738)대로 arm64·x86_64 만 — `androidArm32()`/`androidX86()` 은
  이유를 밝히는 컴파일 오류
- `debug` / `release` 빌드 타입, `-Ppython.buildType`, 변형 × 빌드 타입 태스크 그래프
- `projectFlavors { create("free") }` → 변형 × 플레이버 × 빌드 타입(`buildPythonAndroidArm64FreeDebug`),
  스테이징은 `-Ppython.flavor`(기본: 첫 플레이버). 플랫폼 변형 없이 선언하면 `buildPython` 이 이유와 함께 실패
- `compileLevel`: `instant` 만 지원, 나머지는 해당 변형의 태스크에서만 실패
- `commonMain` 의 `srcDirs` / `metaDirs` / `libDirs` 전달
- `implementation` / `integration` 의존성 → `uv add` (`pypackpack` 백엔드)
- `defaultConfig { pip { autoUpdate; repositories { central / local } } }` → `uv add` 옵션
  (`--default-index`·`--index` / `--find-links` / `--upgrade`). `jit` 는 설치 태스크에서만 이유와 함께 거부
- `pypackpack` `ResourceBundler` 로 번들링
- 플러그인 적용: `python` 확장과 모든 태스크 등록 (`PythonPluginApplyTest`)
- `packagePython` zip: 파일명·위치·항목, 번들 디렉터리가 없으면 실패 (`AssemblePythonPackageTaskTest`)
- 스테이징 복사 규칙과 플랫폼별 변형 선택
- 데스크톱 jar 로의 스테이징 연결: `desktopProcessResources` (`PythonPluginAttachmentTest`)
- `tcl install <package>`

**부분**
- APK 로의 스테이징 연결: `android.sourceSets.main.assets` 등록은 실제 AGP 로 테스트됨
  (`PythonPluginAttachmentTest`), `preBuild`/`merge*Assets` 의존성은 Android SDK 가 있어야 생기므로 테스트 없음
- iOS: 스테이징만 되고 Xcode 프로젝트에 연결되지 않음
- 핫 리로드: Android 전용 `adb push` + 브로드캐스트. `serverHost`, `cert` 는 검증만
- 코드 푸시: 검증과 안내 태스크만, 업로드 없음
- 의존성이 소스셋별로 구분되지 않음 (전부 하나의 목록으로 설치)

**계획 (선언만 있거나 없음)**
- `bytecode` / `native` / `mixed` 컴파일 레벨 (`pypackpack` 쪽 선행 필요)
- `embedLevel` 의미, `useCodeMinifier`, `excludeMetaclass`, `buildFeatures`,
  `versionCode`/`versionName`, `pip { jit }`, `integration()` 의 `KLIBDEPENS` 검사,
  `compileSdk` 로 인터프리터 선택

## 마일스톤 (2026-10-03 확정, GitHub 마일스톤과 연결)

"실제로 쓸 수 있는 수준" = 예시 빌드 파일(32비트 안드로이드 제외)로 Android(arm64·x86_64)·iOS·데스크톱 앱에
Python 코드와 의존성이 실려 빌드·실행되는 상태. 기한을 맞추려고 품질 기준은 낮추지 않고 범위를 줄인다.

| 마일스톤 | 기한 | 범위 | 완료 기준 | 근거 |
|---|---|---|---|---|
| M1 DSL = 예시 파일 | 10-17 | 플랫폼(#7 완료), pip(#9), `compileSdk` 상수(#10), `defaultConfig` 버전(#11), `buildFeatures`(#12), `projectFlavors`(#13), 플러그인 적용·zip·jar/APK 테스트(#14) | 예시 파일의 모든 속성이 컴파일되고, 읽히거나 좁게 거부됨. `usage-example` 이 그 모양으로 빌드됨 | 이슈 7개, 외부 의존 없음 |
| M2 실제 페이로드 | 11-07 | `bytecode`(#15), 소스셋·타깃별 의존성(#16), `embedLevel`(#17), `compileSdk` 로 인터프리터 선택(#18), `KLIBDEPENS`(#19) | 각 변형의 번들에 그 타깃의 wheel 이 들어가고, bytecode 변형은 `.pyc` 를 실음 | pypackpack M2(타깃별 실제 설치)에 의존 |
| M3 쓸 수 있는 수준 | 11-30 | iOS 페이로드 연결(#20), TypedPython 검사 태스크(#21), 세 플랫폼 실행(#22) | 예시 앱이 Android 에뮬레이터·iOS 시뮬레이터·데스크톱에서 Python 과 의존성을 실행 | python-multiplatform 런타임 로딩에 의존 |

11-30 이후로 넘김: `native`/`mixed`(pypackpack Cython 슬롯은 pypackpack M3, 나머지 컴파일러는 빈 껍데기),
`useCodeMinifier`/`excludeMetaclass`(pypackpack 에 minifier 없음), 코드 푸시 업로드와 HTTPS 핫 리로드 서버
(지금은 `adb` 로 동작), 서버에 없는 CPython 자동 빌드(빌드 파이프라인 필요), `pip { jit }`(레시피 빌드 필요).

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

1. ~~서버에 없는 `compileSdk` 버전 자동 빌드~~ — **결정(2026-10-03)**: 문자열로 주면 서버에 없을 때
   자동 빌드, `PY3_11_9_ALPHA` 같은 Enum 으로 주면 서버에 있는 버전만. (구현은 계획)
2. ~~플랫폼 DSL 모양~~ — **결정(2026-10-03)**: 예시 파일 모양(최상위 `android("android") { }` +
   `listOf(...)`). 안드로이드 변형은 CPython 공식 지원(arm64·x86_64)만. #7
3. ~~`pip { repositories { central / local / jit } }` 이름~~ — **결정(2026-10-03)**: 예시 파일 철자.
4. 예시 파일에 없는 `localLibraryPath`, `-Ppython.buildType`, `adb` 기반 핫 리로드를 유지할 것인가.
5. 참조되지 않는 코드(`PythonMultiplatformPlugin.kt`, `reslover.kt`, `decompileKotlinMeta.kt`,
   `PythonLocalLoader.kt`, `DependencyType.kt`, `FrozenPackConfig` 등)와 빈 `pyproject.toml` 을
   지울 것인가.
