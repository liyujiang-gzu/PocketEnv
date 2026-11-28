package dev.ubox.pocketenv.runtime;

import android.content.Context;
import android.content.SharedPreferences;
import dev.ubox.pocketenv.model.Distro;

/**
 * 当前选中的发行版：持久化到 SharedPreferences，各运行时入口统一从这里取，
 * 保证 MainActivity、TerminalService、SSH、SAF 提供者、后台脚本看到同一个 rootfs。
 *
 * <p>首次安装时由 MainActivity 写入；之后启动直接读取，未写入时默认 Ubuntu。
 */
public final class ActiveDistro {
  private static final String PREFS = "ubox";
  private static final String KEY = "active_distro";

  private ActiveDistro() {}

  /** 读取当前选中的发行版，未设置时默认 Ubuntu 24。 */
  public static Distro get(Context context) {
    String id = prefs(context).getString(KEY, null);
    Distro distro = id == null ? null : Distro.byId(id);
    return distro != null ? distro : Distro.UBUNTU_24;
  }

  /** 记录当前选中的发行版（首次安装或切换默认启动发行版时调用）。 */
  public static void set(Context context, Distro distro) {
    prefs(context).edit().putString(KEY, distro.id).apply();
  }

  private static SharedPreferences prefs(Context context) {
    return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }
}
