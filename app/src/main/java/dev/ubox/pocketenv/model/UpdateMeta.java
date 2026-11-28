package dev.ubox.pocketenv.model;

/** 远端版本元数据（来自仓库根目录 update.json）。
 *
 *  <p>纯数据 + 纯函数，无 Android 依赖，便于单元测试。JSON 解析（org.json）在
 *  {@code SettingsActivity} 完成，本类只承载解析结果并判定是否有新版本。
 */
public final class UpdateMeta {
  private final int versionCode;
  private final String versionName;
  private final String releaseNotes;
  private final String releaseUrlGitee;
  private final String releaseUrlGithub;

  public UpdateMeta(int versionCode, String versionName, String releaseNotes,
      String releaseUrlGitee, String releaseUrlGithub) {
    this.versionCode = versionCode;
    this.versionName = versionName;
    this.releaseNotes = releaseNotes;
    this.releaseUrlGitee = releaseUrlGitee;
    this.releaseUrlGithub = releaseUrlGithub;
  }

  /** 远端 versionCode 高于本地时判定有新版本。 */
  public boolean isNewer(int localVersionCode) {
    return versionCode > localVersionCode;
  }

  public int versionCode() {
    return versionCode;
  }

  public String versionName() {
    return versionName;
  }

  public String releaseNotes() {
    return releaseNotes;
  }

  public String releaseUrlGitee() {
    return releaseUrlGitee;
  }

  public String releaseUrlGithub() {
    return releaseUrlGithub;
  }
}
