package oi.runner;
import com.intellij.ide.ui.laf.darcula.ui.DarculaTabbedPaneUI;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import javax.swing.*;
public class VerdictColorRegression {
    static void check() throws Exception {
        for(int s: new int[]{1,2}) {
        JTabbedPane t = new JTabbedPane(JTabbedPane.TOP,JTabbedPane.SCROLL_TAB_LAYOUT);
        t.setSize(387,140);
        for(int i=0;i<8;i++) t.addTab("case"+i,new JPanel());
        OiCasePanel.showVerdictTitle(t,1,"expand4",OiJudge.Verdict.AC);
        OiCasePanel.showVerdictTitle(t,2,"expand5",OiJudge.Verdict.WA);
            t.setSelectedIndex(s); t.setUI(new DarculaTabbedPaneUI()); NarrowTabRegression.layout(t);
            BufferedImage image=NarrowTabRegression.render(t);
            ImageIO.write(image,"png",new File(".tmp-test/verdict154-"+s+".png"));
            for(int i=0;i<3;i++) {
                Rectangle r=t.getBoundsAt(i); int green=0,red=0;
                for(int x=Math.max(0,r.x);x<Math.min(387,r.x+r.width);x++) for(int y=r.y;y<r.y+r.height;y++) {
                    int rgb=image.getRGB(x,y)&0xffffff;
                    if(rgb==0x27ae60)green++; if(rgb==0xe74c3c)red++;
                }
                System.out.println("selected="+s+" tab="+i+" green="+green+" red="+red);
                assert (i==1)==(green>0) && (i==2)==(red>0);
            }
        }
    }
    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(()->{try {check();}catch(Exception e){throw new RuntimeException(e);}});
    }
}
