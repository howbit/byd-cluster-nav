package com.byd.automap.extra;
public final class MeterActivity extends android.app.Activity {
    public void onCreate(android.os.Bundle state){super.onCreate(state);
        android.widget.TextView view=new android.widget.TextView(this);view.setText("模拟地图 / Display "+getWindowManager().getDefaultDisplay().getDisplayId()+" / "+getIntent().getIntExtra("meterType",-1));
        view.setBackgroundColor(0xff125030);view.setTextColor(-1);view.setTextSize(26);setContentView(view);
    }
}
