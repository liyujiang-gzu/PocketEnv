package dev.ubox.pocketenv.model;

/**
 * 可安装的发行版：rootfs 从远程下载（外置），三系统各自独立 rootfs 与启动。
 *
 * <p>纯数据，无 Android 依赖，可单元测试。URL 列表按国内镜像优先、官方兜底排列，
 * 下载器按顺序回退。压缩格式与包管理器决定解压方式与 guest 层行为。
 */
public enum Distro {
  UBUNTU_24(
      "ubuntu",
      "Ubuntu 24.04",
      "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/"
          + "ubuntu-base-24.04.5-base-arm64.tar.gz",
      "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/"
          + "ubuntu-base-24.04.5-base-arm64.tar.gz",
      "a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2",
      Compress.GZIP, Pkg.APT),

  DEBIAN_13(
      "debian",
      "Debian 13",
      "https://mirrors.tuna.tsinghua.edu.cn/lxc-images/images/debian/trixie/arm64/default/"
          + "20261118_05:24/rootfs.tar.xz",
      "https://mirrors.ustc.edu.cn/lxc-images/images/debian/trixie/arm64/default/"
          + "20261118_05:24/rootfs.tar.xz",
      "a9ae0ee8503cfa8787d21d35892ec3f74f1c457f570367d92ba090e8f263628c",
      Compress.XZ, Pkg.APT),

  ALPINE_3(
      "alpine",
      "Alpine 3.22",
      "https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.22/releases/aarch64/"
          + "alpine-minirootfs-3.22.8-aarch64.tar.gz",
      "https://dl-cdn.alpinelinux.org/alpine/v3.22/releases/aarch64/"
          + "alpine-minirootfs-3.22.8-aarch64.tar.gz",
      "36091efacd083c1cf5a8c837272530bff4fc72db1fbfdef559f52de0d890bca4",
      Compress.GZIP, Pkg.APK);

  /** tarball 压缩格式：决定解压用的流。 */
  public enum Compress { GZIP, XZ }

  /** 包管理器家族：决定 guest 层工具安装/软件源是否可用。 */
  public enum Pkg { APT, APK }

  public final String id, title, primaryUrl, fallbackUrl, sha256;
  public final Compress compress;
  public final Pkg pkg;

  Distro(String id, String title, String primaryUrl, String fallbackUrl, String sha256,
      Compress compress, Pkg pkg) {
    this.id = id;
    this.title = title;
    this.primaryUrl = primaryUrl;
    this.fallbackUrl = fallbackUrl;
    this.sha256 = sha256;
    this.compress = compress;
    this.pkg = pkg;
  }

  /** 该发行版默认登录 shell 的绝对路径（Alpine 无 bash，用 ash）。 */
  public String shell() {
    return pkg == Pkg.APK ? "/bin/ash" : "/bin/bash";
  }

  /** 按 id 反查，未匹配返回 null。 */
  public static Distro byId(String id) {
    for (Distro d : values()) if (d.id.equals(id)) return d;
    return null;
  }

  /** 下载 URL 列表（镜像优先、官方兜底）。 */
  public String[] urls() {
    return new String[] {primaryUrl, fallbackUrl};
  }
}
