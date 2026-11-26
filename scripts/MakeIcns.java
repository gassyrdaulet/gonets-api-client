import javax.imageio.ImageIO;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;

/** Собирает build/icon.icns (иконка для macOS) из build/icon.png: java scripts/MakeIcns.java */
public class MakeIcns {
    public static void main(String[] a) throws Exception {
        BufferedImage src = ImageIO.read(new File("build/icon.png"));
        int[] sizes = {128, 256, 512};
        String[] types = {"ic07", "ic08", "ic09"};
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(body);
        for (int i = 0; i < sizes.length; i++) {
            BufferedImage img = new BufferedImage(sizes[i], sizes[i], BufferedImage.TYPE_INT_ARGB);
            var g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, sizes[i], sizes[i], null);
            g.dispose();
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(img, "png", png);
            out.writeBytes(types[i]);
            out.writeInt(png.size() + 8);
            out.write(png.toByteArray());
        }
        try (DataOutputStream f = new DataOutputStream(new java.io.FileOutputStream("build/icon.icns"))) {
            f.writeBytes("icns");
            f.writeInt(body.size() + 8);
            f.write(body.toByteArray());
        }
        System.out.println("build/icon.icns: " + new File("build/icon.icns").length() + " bytes");
    }
}
