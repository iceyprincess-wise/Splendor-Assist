{ pkgs, ... }: {
  channel = "stable-24.05";

  packages = [
    pkgs.jdk21
    pkgs.gradle
    pkgs.android-tools
    pkgs.kotlin
  ];

  env = {
    JAVA_HOME = "${pkgs.jdk21}";
    ANDROID_HOME = "${pkgs.android-tools}";
  };

  idx = {
    extensions = [
      "vscjava.vscode-java-pack"
      "fwcd.kotlin"
    ];

    previews = {
      enable = true;
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
