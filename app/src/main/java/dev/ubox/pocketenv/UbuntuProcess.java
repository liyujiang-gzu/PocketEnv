package dev.ubox.pocketenv;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * 在 guest 环境中执行命令的统一入口。
 *
 * <p>封装 proot 进程构建与环境变量注入，替代 Proot.run 与 TerminalService.startSsh
 * 中各自手写的 ProcessBuilder 逻辑。
 */
final class UbuntuProcess {
  private UbuntuProcess() {}

  /** 非交互地在 guest 里执行一段 bash 脚本，stdout/stderr 合并返回。 */
  static String runScript(Context context, String script, long timeoutMs)
      throws IOException, InterruptedException {
    Proot proot = newProot(context);
    return proot.run(script, timeoutMs);
  }

  /** 构建一个后台长驻进程（如 sshd），输出重定向到指定日志文件。 */
  static Process startDetached(Context context, String[] guestCommand, File logFile)
      throws IOException {
    Proot proot = newProot(context);
    ProcessBuilder builder =
        new ProcessBuilder(proot.argv(guestCommand))
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.to(logFile))
            .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
    applyEnvironment(builder, proot);
    return builder.start();
  }

  static Proot newProot(Context context) throws IOException {
    Rootfs rootfs = new Rootfs(context);
    rootfs.refreshGuestFiles(context);
    Proot proot = new Proot(context, rootfs);
    proot.prepare();
    return proot;
  }

  /** 把 proot 的 KEY=VALUE 环境数组注入 ProcessBuilder。 */
  static void applyEnvironment(ProcessBuilder builder, Proot proot) {
    builder.environment().clear();
    for (String entry : proot.environment()) {
      int split = entry.indexOf('=');
      builder.environment().put(entry.substring(0, split), entry.substring(split + 1));
    }
  }

  static long timeoutMs(long seconds) {
    return TimeUnit.SECONDS.toMillis(seconds);
  }
}
