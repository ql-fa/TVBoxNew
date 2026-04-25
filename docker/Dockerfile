# 使用 Ubuntu 22.04 作为基础镜像
FROM ubuntu:22.04

# 设置非交互模式，避免安装过程中的确认弹窗
ENV DEBIAN_FRONTEND=noninteractive

# 1. 启用 i386 架构并安装基础依赖
# 必须先执行 dpkg --add-architecture i386 才能安装 :i386 软件包
RUN dpkg --add-architecture i386 && \
    apt-get update && \
    apt-get install -y \
    openjdk-8-jdk \
    wget \
    unzip \
    git \
    python3 \
    build-essential \
    # 安装构建 armeabi-v7a 交叉编译所需的 32 位兼容库
    libc6:i386 \
    libncurses5:i386 \
    libstdc++6:i386 \
    lib32z1 \
    libbz2-1.0:i386 \
    && apt-get clean

# 2. 设置环境变量
ENV JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV PATH=${PATH}:${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin:${ANDROID_SDK_ROOT}/platform-tools

# 3. 下载并安装 Android 命令行工具 (Command Line Tools)
RUN mkdir -p ${ANDROID_SDK_ROOT}/cmdline-tools && \
    wget https://dl.google.com/android/repository/commandlinetools-linux-7583922_latest.zip -O /tmp/tools.zip && \
    unzip /tmp/tools.zip -d ${ANDROID_SDK_ROOT}/cmdline-tools && \
    mv ${ANDROID_SDK_ROOT}/cmdline-tools/cmdline-tools ${ANDROID_SDK_ROOT}/cmdline-tools/latest && \
    rm /tmp/tools.zip

# 4. 接受 SDK 许可协议并安装特定版本的构建工具
# 根据项目 build.gradle 配置：compileSdkVersion 30
RUN yes | sdkmanager --licenses && \
    sdkmanager "platform-tools" \
               "platforms;android-30" \
               "build-tools;30.0.3" \
               "ndk-bundle"

# 5. 设置工作目录
WORKDIR /workspace

# 默认进入 bash 环境
CMD ["/bin/bash"]

