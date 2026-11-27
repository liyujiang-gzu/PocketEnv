package dev.ubox.pocketenv;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.Toast;

/**
 * 一类软件源（APT/NPM/PyPI/GO）的可选项与切换逻辑。
 *
 * <p>四套源的「描述 / 选择弹窗 / 应用」行为完全同构，收敛到这里后，
 * 新增一类源只需声明一个 {@code MirrorOption[]} 数组，不再复制三个方法。
 */
final class MirrorOption {
  final String id;
  final String label;
  /** 展示用的第二行文字（域名或 URL）。 */
  final String detail;
  /** 切换脚本参数（ubox-mirror 等）。 */
  final String value;
  /** 切换脚本名：mirror / npm-registry / pypi-mirror / goproxy。 */
  final String script;

  MirrorOption(String id, String label, String detail, String value, String script) {
    this.id = id;
    this.label = label;
    this.detail = detail;
    this.value = value;
    this.script = script;
  }

  /** 弹窗条目：名称 + 域名两行。 */
  String dialogLabel() {
    return label + "\n" + detail;
  }

  /**
   * 弹出选择框并应用所选源。
   *
   * @param title     弹窗标题，如「选择 APT 源」
   * @param options   可选源列表
   * @param suffix    成功提示的补充说明（可为空串），如「，下次安装时会自动 apt update」
   * @param onApplying 选中后、切换开始前的回调（调用方把对应行设为「切换中…」）
   * @param onChanged 切换结束后（无论成败）的刷新回调
   */
  static void choose(Activity activity, String title, MirrorOption[] options, String suffix,
      Runnable onApplying, Runnable onChanged) {
    String[] labels = new String[options.length];
    for (int i = 0; i < options.length; i++) labels[i] = options[i].dialogLabel();
    new AlertDialog.Builder(activity)
        .setTitle(title)
        .setItems(labels, (dialog, which) ->
            apply(activity, options[which], suffix, onApplying, onChanged))
        .show();
  }

  private static void apply(Activity activity, MirrorOption option, String suffix,
      Runnable onApplying, Runnable onChanged) {
    onApplying.run();
    Async.run(activity, "settings-mirror-" + option.id,
        () -> UbuntuProcess.runScript(activity, "ubox-" + option.script + " " + option.id,
            UbuntuProcess.timeoutMs(15)),
        () -> {
          Toast.makeText(activity, "已切换到" + option.label + suffix, Toast.LENGTH_LONG).show();
          onChanged.run();
        },
        error -> {
          Toast.makeText(activity, "切换失败：" + error.getMessage(), Toast.LENGTH_LONG)
              .show();
          onChanged.run();
        });
  }

  /** 按包含关系匹配选项（域名型源，如 APT），未匹配时返回原值。 */
  static String describe(MirrorOption[] options, String value) {
    if (value.isEmpty()) return "未知";
    for (MirrorOption option : options)
      if (value.contains(option.value)) return option.label + " · " + option.detail;
    return value;
  }

  /** 按前缀匹配（URL 型源），未配置时返回 fallback 文案。 */
  static String describeByPrefix(MirrorOption[] options, String value, String emptyText) {
    if (value.isEmpty()) return emptyText;
    for (MirrorOption option : options)
      if (value.startsWith(option.value)) return option.label + " · " + option.detail;
    return value;
  }
}
