{ pkgs, ... }:
let
  # The real Android SDK for this repo: platform 34, build-tools 34.0.0,
  # NDK 25.1.8937393 and CMake 3.22.1 — the exact toolchain the build proves.
  # (android-tools alone is only adb/fastboot; it is NOT an SDK.)
  androidSdk = pkgs.androidenv.composeAndroidPackages {
    platformVersions = [ "34" ];
    buildToolsVersions = [ "34.0.0" ];
    includeNDK = true;
    ndkVersions = [ "25.1.8937393" ];
    cmakeVersions = [ "3.22.1" ];
  }.androidsdk;
in
{
  channel = "stable-24.05";

  packages = [
    pkgs.jdk17
    pkgs.android-tools
    pkgs.kotlin
    androidSdk
  ];

  env = {
    JAVA_HOME = "${pkgs.jdk17}";
    ANDROID_HOME = "${androidSdk}";
    ANDROID_SDK_ROOT = "${androidSdk}";
    SPLENDOR_NDK_ROOT = "${androidSdk}/ndk/25.1.8937393";
  };

  idx = {
    extensions = [
      "vscjava.vscode-java-pack"
      "fwcd.kotlin"
    ];

    previews = {
      enable = false;
      previews = {};
    };

    workspace = {
      onCreate = {
        gradle-init = "./gradlew --version";
      };

      onStart = {
        gradle-check = "java -version && ./gradlew -v";
      };
    };
  };
}
