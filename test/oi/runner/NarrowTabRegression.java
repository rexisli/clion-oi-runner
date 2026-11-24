package oi.runner;
import com.intellij.ide.ui.laf.darcula.ui.DarculaTabbedPaneUI;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import javax.swing.*;

public class NarrowTabRegression {
    static void layout(Container c) {
        c.doLayout();
        for (Component child : c.getComponents()) if (child instanceof Container sub) layout(sub);
    }
    static BufferedImage render(JTabbedPane tabs) {
        BufferedImage image = new BufferedImage(387, 140, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        tabs.paint(g);
        g.dispose();
        return image;
    }
    static void check(boolean darcula) throws Exception {
        JTabbedPane tabs = new JTabbedPane(JTabbedPane.TOP, JTabbedPane.SCROLL_TAB_LAYOUT);
        tabs.setSize(387, 140);
        if (darcula) tabs.setUI(new DarculaTabbedPaneUI());
        for (int i=1; i<=8; i++) tabs.addTab("expand" + i, new JPanel());
        tabs.setSize(387, 140);
        layout(tabs);
        for (int i : new int[]{3,4}) {
            tabs.setSelectedIndex(i);
            if (i==4) tabs.setTitleAt(i, "expand5 AC");
            layout(tabs);
            Rectangle r=tabs.getBoundsAt(i);
            BufferedImage original=render(tabs);
            ImageIO.write(original,"png",new File(".tmp-test/tabs-"+darcula+"-"+i+".png"));
            // Only change the selected title's ink, keeping geometry, underline and arrows identical.
            tabs.setForegroundAt(i, Color.MAGENTA);
            BufferedImage recolored=render(tabs);
            int pixels=0;
            for (int y=Math.max(0,r.y+2); y<Math.min(140,r.y+r.height-2); y++)
                for (int x=Math.max(0,r.x); x<Math.min(387,r.x+r.width); x++)
                    if (original.getRGB(x,y)!=recolored.getRGB(x,y)) pixels++;
            if (pixels<20) throw new AssertionError(tabs.getTitleAt(i)+" ink pixels="+pixels+" bounds="+r);
            System.out.println(tabs.getUI().getClass().getSimpleName()+" "+tabs.getTitleAt(i)+" ink pixels="+pixels);
        }
    }
    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {try {check(args.length>0);} catch(Exception e){throw new RuntimeException(e);}});
    }
}
