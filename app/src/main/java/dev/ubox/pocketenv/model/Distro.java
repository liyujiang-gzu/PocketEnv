package dev.ubox.pocketenv.model;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 可安装的发行版：rootfs 从远程下载（外置），三系统各自独立 rootfs 与启动。
 *
 * <p>每个发行版有若干下载源（国内镜像优先、官方兜底）。下载源分两类：
 * {@linkplain Source#url() 固定 URL + 固定 SHA256}（Ubuntu/Alpine 官方 base tarball，
 * 版本随发版固定），以及 {@linkplain ResolvedSource 动态解析源}（Debian LXC 镜像按构建
 * 时间戳滚动，无 latest 软链接，需运行时抓目录列表取最新一版并读它的 SHA256SUMS）。
 */
public enum Distro {
  UBUNTU_24(
      "ubuntu", "Ubuntu 24.04", Pkg.APT, Compress.GZIP, "/bin/bash",
      new Source[] {
        new Source(
            "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/"
                + "ubuntu-base-24.04.5-base-arm64.tar.gz",
            "a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2"),
        new Source(
            "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/"
                + "ubuntu-base-24.04.5-base-arm64.tar.gz",
            "a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2"),
      }),

  DEBIAN_13(
      "debian", "Debian 13", Pkg.APT, Compress.XZ, "/bin/bash",
      new Source[] {
        new ResolvedSource(
            "https://mirrors.tuna.tsinghua.edu.cn/lxc-images/images/debian/trixie/arm64/default/"),
        new ResolvedSource("https://images.linuxcontainers.org/images/debian/trixie/arm64/default/"),
      }),

  ALPINE_3(
      "alpine", "Alpine 3.22", Pkg.APK, Compress.GZIP, "/bin/ash",
      new Source[] {
        new Source(
            "https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.22/releases/aarch64/"
                + "alpine-minirootfs-3.22.8-aarch64.tar.gz",
            "36091efacd083c1cf5a8c837272530bff4fc72db1fbfdef559f52de0d890bca4"),
        new Source(
            "https://dl-cdn.alpinelinux.org/alpine/v3.22/releases/aarch64/"
                + "alpine-minirootfs-3.22.8-aarch64.tar.gz",
            "36091efacd083c1cf5a8c837272530bff4fc72db1fbfdef559f52de0d890bca4"),
      });

  /** tarball 压缩格式：决定解压用的流。 */
  public enum Compress { GZIP, XZ }

  /** 包管理器家族：决定 guest 层工具安装/软件源是否可用。 */
  public enum Pkg { APT, APK }

  public final String id, title, shell;
  public final Pkg pkg;
  public final Compress compress;
  public final Source[] sources;

  Distro(String id, String title, Pkg pkg, Compress compress, String shell, Source[] sources) {
    this.id = id;
    this.title = title;
    this.pkg = pkg;
    this.compress = compress;
    this.shell = shell;
    this.sources = sources;
  }

  /** 按 id 反查，未匹配返回 null。 */
  public static Distro byId(String id) {
    for (Distro d : values()) if (d.id.equals(id)) return d;
    return null;
  }

  /** 一个下载源：固定 URL + 预期的 SHA256（发布时确定，版本固定）。 */
  public static class Source {
    public final String url;
    public final String sha256;

    public Source(String url, String sha256) {
      this.url = url;
      this.sha256 = sha256;
    }

    /** 固定源的 URL 与校验和在编译期已知，直接返回自身。 */
    public Resolved resolve() throws IOException {
      return new Resolved(url, sha256);
    }
  }

  /**
   * 动态解析源：Debian LXC 镜像的 trixie 目录按构建时间戳（如 20261118_05:24）滚动，
   * 没有 latest 软链接。解析时抓目录列表取最新一版，再读该目录的 SHA256SUMS 得到当前
   * rootfs 的校验和——这样镜像滚动后无需改代码，也不必硬编码每个日期对应的哈希。
   */
  public static final class ResolvedSource extends Source {
    private static final Pattern ENTRY = Pattern.compile("href=\"(\\d{8}_\\d{2}%3A\\d{2})/?\"");
    private static final String FILE = "rootfs.tar.xz";

    public ResolvedSource(String base) {
      super(base, null);
    }

    /** 解析出当前最新一版的 rootfs URL 与其 SHA256；任一步失败抛 IOException 触发换源。 */
    @Override
    public Resolved resolve() throws IOException {
      String latest = latestEntry(url);
      String dir = url + latest + "/";
      return new Resolved(dir + FILE, readChecksum(dir + "SHA256SUMS", FILE));
    }

    /** 抓目录列表 HTML，取字典序最大的时间戳目录（时间戳格式天然可排序）。 */
    private static String latestEntry(String base) throws IOException {
      String html = get(base);
      Matcher matcher = ENTRY.matcher(html);
      String latest = null;
      while (matcher.find()) {
        String entry = matcher.group(1);
        if (latest == null || entry.compareTo(latest) > 0) latest = entry;
      }
      if (latest == null) throw new IOException("目录里没有可用的构建版本 " + base);
      return latest;
    }

    /** 读 SHA256SUMS，返回目标文件名对应的校验和。 */
    private static String readChecksum(String sumsUrl, String fileName) throws IOException {
      for (String line : get(sumsUrl).split("\n")) {
        String trimmed = line.trim();
        int split = trimmed.indexOf(' ');
        if (split != 64) continue; // SHA256 十六进制固定 64 位，后面跟空格和文件名
        if (trimmed.substring(split + 1).trim().equals(fileName))
          return trimmed.substring(0, split).toLowerCase();
      }
      throw new IOException("SHA256SUMS 里没有 " + fileName + " " + sumsUrl);
    }

    private static String get(String url) throws IOException {
      HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
      connection.setConnectTimeout(15_000);
      connection.setReadTimeout(30_000);
      connection.setInstanceFollowRedirects(true);
      connection.setRequestProperty("User-Agent", "PocketEnv-rootfs");
      try {
        int code = connection.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + code + " " + url);
        try (InputStream in = connection.getInputStream();
            BufferedReader reader =
                new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
          StringBuilder text = new StringBuilder();
          char[] buffer = new char[1 << 14];
          int n;
          while ((n = reader.read(buffer)) != -1) text.append(buffer, 0, n);
          return text.toString();
        }
      } finally {
        connection.disconnect();
      }
    }
  }

  /** 解析后的下载地址 + 校验和，供下载器直接使用。 */
  public static final class Resolved {
    public final String url, sha256;

    public Resolved(String url, String sha256) {
      this.url = url;
      this.sha256 = sha256;
    }
  }

  /** 该发行版默认登录 shell 的绝对路径（Alpine 无 bash，用 ash）。 */
  public String shell() {
    return shell;
  }
}
