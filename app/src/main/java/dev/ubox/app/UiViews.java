package dev.ubox.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 设置页与许可页共用的行/文本构造，避免视觉规范在两处漂移。 */
final class UiViews {
  private UiViews() {}

  static LinearLayout row(Context context, String title, View action) {
    LinearLayout row = new LinearLayout(context);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(0, dp(context, 10), 0, dp(context, 10));
    TypedValue ripple = new TypedValue();
    context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
    row.setBackgroundResource(ripple.resourceId);

    LinearLayout texts = new LinearLayout(context);
    texts.setOrientation(LinearLayout.VERTICAL);
    texts.addView(text(context, title, 16, Color.WHITE));
    texts.addView(text(context, "", 13, 0xFF9E9E9E));
    row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
    if (action != null) row.addView(action);
    return row;
  }

  static TextView subtitle(LinearLayout row) {
    return (TextView) ((LinearLayout) row.getChildAt(0)).getChildAt(1);
  }

  static TextView text(Context context, String value, int sp, int color) {
    TextView view = new TextView(context);
    view.setText(value);
    view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
    view.setTextColor(color);
    view.setTypeface(Typeface.DEFAULT);
    return view;
  }

  static int dp(Context context, int value) {
    return (int) (value * context.getResources().getDisplayMetrics().density);
  }
}
