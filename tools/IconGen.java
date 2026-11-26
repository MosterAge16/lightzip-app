import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;

/** 生成各密度启动图标：渐变圆角方块 + 拉链图案 */
public class IconGen {
    public static void main(String[] args) throws Exception {
        File res = new File(args[0]);
        Object[][] specs = {
                {48, "mdpi"}, {72, "hdpi"}, {96, "xhdpi"},
                {144, "xxhdpi"}, {192, "xxxhdpi"},
        };
        for (Object[] spec : specs) {
            int size = (Integer) spec[0];
            String dpi = (String) spec[1];
            BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            float r = size * 0.22f;
            RoundRectangle2D rr = new RoundRectangle2D.Float(0, 0, size, size, r, r);
            g.setPaint(new GradientPaint(0, 0, new Color(0x1E88E5), size, size, new Color(0x26C6DA)));
            g.fill(rr);
            g.setClip(rr);
            // 拉链：中轴两排交错齿 + 底部拉片
            float cx = size / 2f;
            float tooth = size * 0.08f;
            float top = size * 0.14f;
            int n = 7;
            g.setColor(Color.WHITE);
            for (int i = 0; i < n; i++) {
                float y = top + i * tooth * 1.55f;
                float w = tooth * 1.7f, h = tooth * 0.9f;
                g.fillRoundRect((int) (cx - w - tooth * 0.12f), (int) y,
                        (int) w, (int) h, (int) h, (int) h);
                g.fillRoundRect((int) (cx + tooth * 0.12f), (int) y,
                        (int) w, (int) h, (int) h, (int) h);
            }
            // 拉链收尾三角
            float ye = top + n * tooth * 1.55f;
            g.fillRoundRect((int) (cx - tooth * 0.35f), (int) ye,
                    (int) (tooth * 0.7f), (int) (tooth * 1.6f), 4, 4);
            // 拉片圆环
            g.setStroke(new BasicStroke(size * 0.035f));
            g.drawOval((int) (cx - tooth * 0.85f), (int) (ye + tooth * 1.9f),
                    (int) (tooth * 1.7f), (int) (tooth * 1.7f));
            g.dispose();
            File dir = new File(res, "mipmap-" + dpi);
            dir.mkdirs();
            ImageIO.write(img, "png", new File(dir, "ic_launcher.png"));
            System.out.println("ic_launcher " + size + "px -> " + dir);
        }
    }
}
