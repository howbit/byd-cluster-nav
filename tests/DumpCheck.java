import com.byd.clusternav.core.WindowEvidence;
import java.nio.file.*;
public final class DumpCheck {
    public static void main(String[] args)throws Exception {
        String dump=new String(Files.readAllBytes(Paths.get(args[0])),"UTF-8");
        int id=Integer.parseInt(args[1]);
        if(!WindowEvidence.hasSurfaceOnDisplay(dump,"com.byd.clusternav.ui.PatternActivity",id))throw new AssertionError("Real Android window not detected");
        if(WindowEvidence.hasSurfaceOnDisplay(dump,"com.byd.clusternav.ui.PatternActivity",0))throw new AssertionError("Wrong display accepted");
        System.out.println("PASS actual Android 10 window dump, display="+id);
    }
}
