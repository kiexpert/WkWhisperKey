# 🧩 WkWhisperKey  
### Whisper-Based AI Keyboard — by Will Kim (윌김)

---

> “속삭임만으로 조용히 명령하고 입력하는 세상의 첫 인터페이스”

**WkWhisperKey**는 성대 진동 없이도 동작하는 **속삭임 기반 IME** 입니다.  
로컬(on-device) Whisper STT 엔진과 IMF 파형 전처리, Bitwise Sort Set 기반 저지연 인퍼런스를 결합하여  
조용한 환경에서도 고정밀 입력을 실현합니다.

---

## ⚙️ 주요 구성

| 구성 | 설명 |
|------|------|
| `core/` | C++ 코어 (IMF, MelSpectrogram, TensorRT/CoreML 추론) |
| `android/` | Kotlin IME 앱 + NDK 연동 |
| `ios/` | SwiftUI Keyboard Extension (CoreML 기반) |
| `desktop/` | CLI 및 GUI 디버그 툴 |
| `sdk/` | Python / Kotlin / Swift SDK 래퍼 |
| `assets/` | Whisper 모델 파일 및 노이즈 샘플 |

---

## 🧠 주요 기능

- IMF + Pre-emphasis Filter 로 속삭임 신호 강조  
- Noise-Augmented Training 지원  
- TensorRT INT8 가속 및 NNAPI 백엔드 호환  
- Command / Text / DeepToHyde 대화 모드 전환  
- 완전 오프라인 실행 (프라이버시 보호)

---

## 🧩 Android 빌드 가이드

```bash
cd android
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/WkWhisperKey-debug.apk
