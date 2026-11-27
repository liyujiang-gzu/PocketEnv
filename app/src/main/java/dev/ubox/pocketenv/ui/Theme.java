package dev.ubox.pocketenv.ui;

/** 应用配色常量。UI 类统一从这里取值，避免颜色字面量散落。 */
public final class Theme {
  private Theme() {}

  /** 背景（最深）。 */
  public static final int BACKGROUND = 0xFF121212;
  /** 工具栏背景。 */
  public static final int SURFACE = 0xFF1E1E1E;
  /** Ubuntu 橙，主操作色。 */
  public static final int ACCENT = 0xFFE95420;
  /** 已安装/已授权的绿色。 */
  public static final int OK = 0xFF81C784;
  /** 停止/卸载的红色。 */
  public static final int DANGER = 0xFFE57373;
  /** 正文白色。 */
  public static final int TEXT = 0xFFFFFFFF;
  /** 辅助文字灰色。 */
  public static final int TEXT_DIM = 0xFF9E9E9E;
}
