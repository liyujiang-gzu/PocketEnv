package dev.ubox.app;

import android.app.Activity;
import android.util.Log;
import android.widget.Toast;

/** 「后台线程执行 → 主线程回调」的统一模板，替代散落各处的裸 new Thread。 */
final class Async {
  private static final String TAG = "UbuntuBox";

  private Async() {}

  /**
   * 在后台线程执行任务，结束后在 UI 线程回调。
   * 异常不会静默：记录日志并交给 onError（默认 Toast 提示）。
   *
   * @param task      后台任务，返回结果
   * @param onDone    UI 线程回调，成功时执行
   * @param onError   UI 线程回调，失败时执行；null 时默认 Toast「操作失败」
   */
  static <T> void run(Activity activity, String threadName, Task<T> task, Callback<T> onDone,
      Callback<Exception> onError) {
    new Thread(
        () -> {
          T result;
          try {
            result = task.run();
          } catch (Exception error) {
            Log.e(TAG, threadName + " failed", error);
            Callback<Exception> handler =
                onError != null ? onError : e -> Toast.makeText(
                    activity, "操作失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            activity.runOnUiThread(() -> handler.run(error));
            return;
          }
          activity.runOnUiThread(() -> onDone.run(result));
        },
        threadName)
        .start();
  }

  /** 无返回值的便捷重载。 */
  static void run(Activity activity, String threadName, BlockingTask task, Runnable onDone,
      Callback<Exception> onError) {
    run(activity, threadName,
        () -> {
          task.run();
          return null;
        },
        ignored -> onDone.run(),
        onError);
  }

  @FunctionalInterface
  interface Task<T> {
    T run() throws Exception;
  }

  @FunctionalInterface
  interface BlockingTask {
    void run() throws Exception;
  }

  @FunctionalInterface
  interface Callback<T> {
    void run(T value);
  }
}
