# ShortFree Android v1.0.4

YouTube Shorts와 Instagram Reels를 열면 첫 영상 하나를 허용합니다. 플레이어에서 다음 영상으로 이동한 것으로 확인되면 뒤로 가기로 플레이어를 닫고 **차단되었습니다**를 표시합니다. 릴스에서는 페이지 스크롤 신호를 받자마자 화면을 가리고, 차단이 확정되면 ShortFree 차단 화면을 전면에 띄워 Instagram을 백그라운드로 보냅니다. **이전 화면으로 돌아가기**를 누르면 차단 화면이 닫힙니다. 앱별 차단 설정은 기기에 저장됩니다.

이 프로젝트는 개인용 테스트 단계입니다. 최소 설치 버전은 Android 8.0(API 26)입니다. 차단 처리에는 인터넷 연결이 필요하지 않습니다.

설치 문제, 차단 동작, 개선 제안은 공개 이슈로 받습니다. GitHub 계정으로 로그인하면 작성할 수 있습니다. **[피드백 남기기](https://github.com/hohoboxhappy/ShortFree-Android/issues/new?template=feedback.yml)**

## 갤럭시에서 설치

1. 이 저장소의 **Releases** 탭에서 테스트 릴리스의 `ShortFree-v1.0.4-release.apk`를 내려받습니다. 같은 릴리스에 첨부된 `.sha256` 파일로 APK의 체크섬을 확인할 수 있습니다. 갤럭시의 **내 파일 → 다운로드**에서 APK를 열어 설치합니다.
2. ShortFree를 열고 접근성 안내를 읽은 뒤 **동의하고 접근성 설정 열기**를 누릅니다.
3. 기기의 접근성 설정에서 **설치된 앱 → ShortFree**를 열어 서비스를 켭니다. 메뉴 이름은 One UI 버전에 따라 다를 수 있습니다.
4. ShortFree로 돌아와 **서비스 실행 중**과 앱별 스위치를 확인합니다. 설치된 앱 버전은 화면 상단 **ShortFree** 제목 아래에서 확인할 수 있습니다. 사용 중지는 같은 접근성 설정에서 할 수 있습니다.

### 설치 중 Play Protect 경고가 뜨는 경우

Play Protect가 앱 검사를 권장하면 **검사**를 진행하고 기기의 안내를 따릅니다. 접근성처럼 민감한 권한을 요청하는 외부 설치 앱은 기기와 지역에 따라 설치가 차단될 수 있습니다. **설치 차단이 표시되면 Play Protect를 켜 둔 상태에서**, 경고 문구와 Android·One UI 버전을 저장소에 알려주세요. Google은 차단 사유에 따른 개발자 확인 및 이의 제기 절차를 안내합니다. [Google Play Protect 경고 안내](https://developers.google.com/android/play-protect/warning-dev-guidance)

### 설치 후 ShortFree 접근성 항목이 회색인 경우

Android 13 이상에서는 외부에서 설치한 앱의 접근성 설정을 제한할 수 있습니다. **‘앱의 액세스가 거부됨’**이 뜨면, 이 저장소에서 받은 ShortFree APK인지 확인한 뒤 **설정 → 애플리케이션 → ShortFree → 오른쪽 위 ⋮ → 제한된 설정 허용**을 선택합니다. 그런 다음 **설정 → 접근성 → 설치된 앱 → ShortFree**에서 사용 스위치를 켭니다. 허용 항목이 보이지 않거나 접근성 항목이 계속 회색이면, 팝업 문구와 Android·One UI 버전을 알려주세요. [Google Android 제한된 설정 안내](https://support.google.com/android/answer/12623953?hl=ko)

### 디버그 빌드에서 배포용 APK로 바꾸는 경우

안드로이드는 같은 앱을 업데이트할 때 기존 설치와 새 APK의 서명 인증서를 비교합니다. 기존 디버그 설치와 배포용 APK의 서명이 다르면 기존 앱을 제거하고 배포용 APK를 새로 설치해야 합니다. 이 경우 기존 ShortFree 설정이 지워지며, 접근성 서비스도 다시 활성화해야 합니다. [Android 앱 업데이트 조건](https://developer.android.com/google/play/app-updates)

영상 전환은 Android 접근성 이벤트를 받아 판정하므로 다음 영상이 잠시 보일 수 있습니다. YouTube와 Instagram 앱 버전에 따라 영상 전환이 누락되거나 차단 화면 뒤에 소리가 이어질 수 있습니다.

### 릴스 스와이프 사전 차단 (실험)

ShortFree에서 Instagram Reels를 켠 상태로 **릴스 스와이프 사전 차단 (실험)**을 켜면 됩니다. 기본값은 꺼짐입니다. 릴스 페이저를 확인하면 투명 입력 창이 영상 영역의 위쪽 스와이프를 받아 버립니다. 같은 릴스 창과 영역을 다시 확인한 뒤 다른 터치를 안드로이드 제스처로 전달합니다. 전달한 탭으로 댓글이 열리면 입력 창을 해제해 댓글 UI가 직접 스크롤을 받게 합니다.

일반 터치를 전달할 때는 입력 창을 잠시 해제합니다. 이 짧은 구간이나 아직 릴스 화면을 인식하지 못한 상태에서는 기존 전환 감지가 대신 작동합니다. 실험 옵션은 Android 접근성 서비스의 제스처 전달 기능을 사용하며, 터치 좌표를 저장하거나 전송하지 않습니다. [Android 제스처 전달 문서](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#dispatchGesture(android.accessibilityservice.GestureDescription,android.accessibilityservice.AccessibilityService.GestureResultCallback,android.os.Handler))

## PC에서 빌드

JDK 17과 Android SDK 플랫폼 35, 빌드 도구 35.0.0을 사용합니다. Android Studio에 이 폴더를 열거나, PowerShell에서 `JAVA_HOME`과 `ANDROID_HOME`을 설정한 뒤 Gradle 래퍼를 실행합니다.

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
.\gradlew.bat :app:assembleDebugAndroidTest :app:assembleRelease
```

디버그 APK는 `app/build/outputs/apk/debug/app-debug.apk`에 생성됩니다. 기기에서 UI 테스트를 실행하려면 테스트용 기기나 에뮬레이터를 연결하고 `.\gradlew.bat :app:connectedDebugAndroidTest`를 실행합니다.

설정과 권한 안내는 Compose, 저장은 DataStore를 사용합니다. 접근성 서비스는 창 전환을 확인하고 YouTube와 Instagram의 대상 UI만 분석합니다. 화면 감지기와 세션 상태 머신은 분리돼 있으며, 작성자·캡션에서 계산하는 비교값은 시청 세션 동안 메모리에만 남습니다. 네트워크와 화면 녹화 권한을 요청하지 않습니다. [Android 접근성 서비스 가이드](https://developer.android.com/guide/topics/ui/accessibility/service)

[제3자 소프트웨어 라이선스 고지](THIRD_PARTY_NOTICES.txt)에 배포 APK에 들어가는 라이브러리와 Gradle 래퍼를 정리했습니다.

## PC 검증 결과

- 배포 APK 서명과 권한 목록 검증
- 영상 전환, 세션 처리 및 스와이프 판정 단위 테스트 37개 통과
- 접근성 동의 화면, 앱별 스위치와 오버레이 테스트 코드 컴파일 및 테스트 APK 생성

UI 테스트 실행과 YouTube·Instagram의 실제 차단 동작은 기기 검증 항목에 포함합니다.

## 갤럭시에서 확인할 동작

설치한 YouTube·Instagram의 앱 버전과 Android·One UI 버전을 함께 기록합니다.

| 확인 항목 | 기대 동작 |
| --- | --- |
| 검색 결과나 Shorts 탭, Instagram 프로필에서 숏폼 열기 | 첫 영상 허용 |
| 반복 재생, 좋아요, 댓글 열기와 목록 스크롤 | 차단 화면이 뜨지 않음 |
| 다음 영상으로 스와이프 | 차단 화면 표시 및 배경 오디오 정지 |
| 쇼츠를 열고 바로 또는 2~3초 뒤에 첫 스와이프 | 두 경우 모두 첫 전환에서 차단 화면 표시 |
| 로딩 표시가 있는 쇼츠에서 첫 스와이프 | 로딩 중 받은 페이저 이동 신호를 이용해 첫 전환에서 차단 |
| 릴스 실험 옵션을 켜고 영상 영역에서 위로 스와이프 | 첫 영상이 계속 재생되고 다음 영상이 나타나지 않음 |
| 릴스 실험 옵션을 켜고 댓글을 열어 스크롤 | 댓글이 열리고 목록을 인스타에서 스크롤 가능 |
| 릴스의 다음 영상으로 조금 밀었다가 원래 영상으로 복귀 | 임시 화면이 사라지고 첫 영상 계속 재생 |
| 일반 YouTube 영상과 Instagram 게시물·스토리·DM | 차단 화면이 뜨지 않음 |
| 차단 화면에서 돌아가기 또는 홈으로 이동 | 차단 화면이 사라지고 일반 내비게이션 이용 가능 |
| 플레이어를 닫고 다른 숏폼을 직접 열기 | 새 첫 영상 허용 |
| 댓글을 열어둔 채 앱 전환, 잠금·해제, 회전 | 차단 화면이 다른 앱에 남지 않고 댓글을 새 영상으로 판정하지 않음 |
| 앱별 스위치 끄기, 접근성 서비스 끄기 및 다시 켜기 | 설정대로 각각 멈추고 서비스 재시작 시 세션 초기화 |
| 같은 작성자·캡션 영상, 빠른 연속 스와이프 | 전환 감지 성공·실패를 기록 |

명세의 감지율, 오탐, 표시 지연과 오디오 정지 목표는 갤럭시 실기기 시험으로 확인합니다.

### 선택 진단

디버그 APK에서만 구조 로그를 켤 수 있습니다. 권한에 동의하고 서비스가 켜진 상태에서, 사용자가 직접 준비한 ADB 연결로 다음 명령을 실행합니다.

```powershell
adb shell run-as io.shortfree mkdir -p files
adb shell run-as io.shortfree touch files/inspect-ui
adb logcat -s 'ShortFreeStructure:D' '*:S'
```

진단에는 대상 앱의 뷰 리소스 ID, 화면 좌표, 화면 구분, 페이저 인덱스만 기록됩니다. 진단을 끄려면 `adb shell run-as io.shortfree rm files/inspect-ui`를 실행합니다.
