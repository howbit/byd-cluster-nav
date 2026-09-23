package com.byd.clusternav.ui;
import android.app.Activity;
import android.os.*;
import android.graphics.*;
import android.view.*;

/** Does not itself switch hardware; useful to distinguish app and display routing failures. */
public final class PatternActivity extends Activity {
    private final Handler handler=new Handler();
    private final Runnable stop=()->finish();
    public void onCreate(Bundle state){super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON|WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(new View(this){private final Paint paint=new Paint(3);protected void onDraw(Canvas c){super.onDraw(c);int[] colors={Color.RED,Color.GREEN,Color.BLUE,Color.WHITE,Color.YELLOW,Color.CYAN};float w=getWidth()/6f;for(int i=0;i<6;i++){paint.setColor(colors[i]);c.drawRect(i*w,0,(i+1)*w,getHeight(),paint);}paint.setColor(Color.BLACK);c.drawRect(0,0,getWidth(),getHeight()*.3f,paint);paint.setColor(Color.WHITE);paint.setTextSize(Math.max(24,getHeight()*.055f));c.drawText("ClusterNav 2.6  |  Display "+getWindowManager().getDefaultDisplay().getDisplayId(),24,getHeight()*.10f,paint);c.drawText("仅用于驻车链路测试 / 自动退出",24,getHeight()*.21f,paint);}});
        handler.postDelayed(stop,55000);
    }
    protected void onDestroy(){handler.removeCallbacks(stop);super.onDestroy();}
}
