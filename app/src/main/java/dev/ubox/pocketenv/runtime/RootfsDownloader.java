package dev.ubox.pocketenv.runtime;

import android.content.Context;
import dev.ubox.pocketenv.model.Distro;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * 从远程下载发行版 rootfs tarball：逐个下载源尝试，解析或下载失败自动换下一个；
 * 下载完成后做 SHA256 校验，不匹配则删除并报错，绝不把损坏的 rootfs 解压进环境。
 *
 * <p>rootfs 外置（不打进 APK），首次安装某发行版时按需下载一次，缓存到应用私有目录，
 * 解压成功后即删除 tarball 释放空间。
 *
 * <p>除发行版内置的镜像/官方源外，还支持用户输入的绝对下载地址：这类地址的 SHA256
 * 事先未知，故跳过校验和比对，改为信任用户显式提供的来源。
 */
public final class RootfsDownloader {
  /** 下载进度回调：done/total 为当前已下载字节数与 tarball 总大小。 */
  public interface Progress {
    void update(long done, long total);
  }

  private static final int CONNECT_TIMEOUT_MS = 15_000;
  private static final int READ_TIMEOUT_MS = 30_000;

  private final Context context;
  private final Distro distro;

  public RootfsDownloader(Context context, Distro distro) {
    this.context = context.getApplicationContext();
    this.distro = distro;
  }

  /**
   * 下载并校验 rootfs tarball，返回本地文件。
   *
   * <p>逐个源解析（动态源在此抓最新版本）并下载到 .part 临时文件，全部完成且校验通过后
   * 才重命名为最终文件；中途失败或校验不通过则换下一个源。所有源均不可达时抛
   * {@link IOException}。
   *
   * @param customUrl 用户输入的绝对下载地址；非空时优先使用（跳过 SHA256 校验），
   *                  失败仍回退到内置源
   */
  public File download(String customUrl, Progress progress) throws IOException {
    File cache = new File(context.getCacheDir(), distro.id + "-rootfs.tarball");
    File partial = new File(cache.getPath() + ".part");
    IOException lastError = null;

    for (Attempt attempt : attempts(customUrl)) {
      try {
        downloadOne(attempt.url, partial, progress);
        if (attempt.sha256 != null) verify(partial, attempt.sha256);
        // 校验通过才落定为最终 tarball；下次解压失败重试时不必重新下载
        if (cache.exists()) cache.delete();
        if (!partial.renameTo(cache)) throw new IOException("无法保存下载的 rootfs");
        return cache;
      } catch (IOException error) {
        lastError = error;
        partial.delete(); // 换下一个源重新下，不残留半截数据
      }
    }
    throw new IOException("下载 " + distro.title + " rootfs 失败："
        + (lastError != null ? lastError.getMessage() : "所有下载源均不可达"));
  }

  /** 组装待尝试的下载源：用户自定义地址优先，其后是发行版内置源逐个解析。 */
  private List<Attempt> attempts(String customUrl) {
    List<Attempt> list = new ArrayList<>();
    if (customUrl != null && !customUrl.trim().isEmpty()) {
      // 用户显式提供的地址，SHA256 未知，跳过校验和比对
      list.add(new Attempt(customUrl.trim(), null));
    }
    for (Distro.Source source : distro.sources) {
      try {
        Distro.Resolved resolved = source.resolve();
        list.add(new Attempt(resolved.url, resolved.sha256));
      } catch (IOException unresolved) {
        // 该源当前不可用（如镜像滚动、网络不通），跳过换下一个
      }
    }
    return list;
  }

  private static final class Attempt {
    final String url, sha256;

    Attempt(String url, String sha256) {
      this.url = url;
      this.sha256 = sha256;
    }
  }

  /** 下载单个地址到 partial 文件，回调已下载字节数。 */
  private void downloadOne(String url, File partial, Progress progress) throws IOException {
    HttpURLConnection connection = open(url);
    try {
      int code = connection.getResponseCode();
      if (code != HttpURLConnection.HTTP_OK)
        throw new IOException("HTTP " + code + " 来自 " + host(url));
      long total = connection.getContentLengthLong();
      try (InputStream in = new java.io.BufferedInputStream(connection.getInputStream(), 1 << 16);
          OutputStream out = new FileOutputStream(partial)) {
        byte[] buffer = new byte[1 << 16];
        long done = 0;
        long lastReport = 0;
        int n;
        while ((n = in.read(buffer)) != -1) {
          out.write(buffer, 0, n);
          done += n;
          if (progress != null && done - lastReport > 256 * 1024) {
            lastReport = done;
            progress.update(done, total);
          }
        }
        if (total > 0 && done != total)
          throw new IOException("下载不完整：" + done + "/" + total + " 字节");
      }
    } finally {
      connection.disconnect();
    }
  }

  private static HttpURLConnection open(String url) throws IOException {
    HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
    connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
    connection.setReadTimeout(READ_TIMEOUT_MS);
    connection.setInstanceFollowRedirects(true);
    connection.setRequestProperty("User-Agent", "PocketEnv-rootfs");
    return connection;
  }

  /** 校验已下载文件的整体 SHA256 与预期值一致。 */
  private static void verify(File file, String expected) throws IOException {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IOException("缺少 SHA-256");
    }
    try (InputStream in = new FileInputStream(file)) {
      byte[] buffer = new byte[1 << 16];
      int n;
      while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
    }
    StringBuilder hex = new StringBuilder();
    for (byte b : digest.digest()) hex.append(String.format("%02x", b));
    if (!hex.toString().equalsIgnoreCase(expected))
      throw new IOException("SHA256 校验失败（下载源可能被篡改或下载不完整）");
  }

  private static String host(String url) {
    try {
      return new URL(url).getHost();
    } catch (IOException error) {
      return url;
    }
  }
}
