package com.byd.automap;
public final class MainActivity extends android.app.Activity {
    public void onCreate(android.os.Bundle state){super.onCreate(state);
        startService(new android.content.Intent(this,com.byd.automap.service.PushService.class));
        android.widget.TextView view=new android.widget.TextView(this);view.setText("模拟高德：仅用于本地回归测试");view.setTextSize(28);setContentView(view);
    }
}
