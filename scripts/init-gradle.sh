#!/usr/bin/env bash
set -e

GRADLE_VER="8.7"
WRAPPER_DIR="gradle/wrapper"
WRAPPER_PROPS="$WRAPPER_DIR/gradle-wrapper.properties"

echo "🧱 Initializing Gradle Wrapper (v$GRADLE_VER)"

# 전역 gradle 비활성화 (CI 환경 대비)
if command -v gradle >/dev/null 2>&1; then
  echo "⚙️  Disabling system gradle for safety..."
  sudo mv /usr/bin/gradle /usr/bin/gradle.disabled 2>/dev/null || true
fi

# Wrapper 설정 생성
mkdir -p "$WRAPPER_DIR"
cat > "$WRAPPER_PROPS" <<EOF
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-${GRADLE_VER}-bin.zip
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
EOF

# gradlew 생성 (경량 런처)
echo '#!/bin/sh' > gradlew
echo "exec java -Xmx64m -cp \"gradle-tmp/gradle-${GRADLE_VER}/lib/gradle-launcher-${GRADLE_VER}.jar\" org.gradle.launcher.GradleMain \"\$@\"" >> gradlew
chmod +x gradlew

# Gradle 바이너리 다운로드
echo "⬇️  Downloading Gradle $GRADLE_VER..."
curl -sLO "https://services.gradle.org/distributions/gradle-${GRADLE_VER}-bin.zip"
unzip -q "gradle-${GRADLE_VER}-bin.zip" -d gradle-tmp
rm "gradle-${GRADLE_VER}-bin.zip"

# 버전 확인
./gradlew -v | head -n 3
echo "✅ Gradle Wrapper initialized successfully."
