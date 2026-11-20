import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Puts pictures side by side, each scaled to the same height, for a look by eye:
 * `java tools/SideBySide.java <out.png> <height> <a.png> <b.png> ...` (plain Java, no build needed).
 */
public class SideBySide {

    public static void main(String[] args) throws Exception {
        int height = Integer.parseInt(args[1]);
        int count = args.length - 2;
        BufferedImage out = new BufferedImage(height * count, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, out.getWidth(), height);
        for (int i = 0; i < count; i++) g.drawImage(ImageIO.read(new File(args[i + 2])), i * height, 0, height, height, null);
        g.dispose();
        ImageIO.write(out, "png", new File(args[0]));
    }
}
