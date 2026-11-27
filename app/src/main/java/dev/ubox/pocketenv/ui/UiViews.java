package dev.ubox.pocketenv.ui;

import dev.ubox.pocketenv.R;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 设置页与许可页共用的行/文本构造，避免视觉规范在两处漂移。 */
public final class UiViews {
  private UiViews() {}

  public static LinearLayout row(Context context, String title, View action) {
    LinearLayout row = new LinearLayout(context);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(0, dp(context, 10), 0, dp(context, 10));
    TypedValue ripple = new TypedValue();
    context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
    row.setBackgroundResource(ripple.resourceId);

    LinearLayout texts = new LinearLayout(context);
    texts.setOrientation(LinearLayout.VERTICAL);
    texts.addView(text(context, title, 16, Theme.TEXT));
    TextView subtitle = text(context, "", 13, Theme.TEXT_DIM);
    // 打 tag 供 subtitle() 查找，避免依赖子视图位置（结构变化时静默错位）
    subtitle.setTag(R.id.tag_subtitle, Boolean.TRUE);
    texts.addView(subtitle);
    row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
    if (action != null) row.addView(action);
    return row;
  }

  public static TextView subtitle(LinearLayout row) {
    LinearLayout texts = (LinearLayout) row.getChildAt(0);
    for (int i = 0; i < texts.getChildCount(); i++) {
      View child = texts.getChildAt(i);
      if (child.getTag(R.id.tag_subtitle) != null) return (TextView) child;
    }
    throw new IllegalStateException("row 缺少 subtitle 视图");
  }

  public static TextView text(Context context, String value, int sp, int color) {
    TextView view = new TextView(context);
    view.setText(value);
    view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
    view.setTextColor(color);
    view.setTypeface(Typeface.DEFAULT);
    return view;
  }

  public static int dp(Context context, int value) {
    return (int) (value * context.getResources().getDisplayMetrics().density);
  }
}
