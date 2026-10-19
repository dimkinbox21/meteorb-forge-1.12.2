import java.io.File;
import java.io.FileOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Extracts Forge's own event classes so their signatures can be read. */
public final class ForgeEvents {
    private ForgeEvents() {
    }

    public static void main(String[] args) throws Exception {
        ZipFile z = new ZipFile(args[0]);
        String dest = args[1];
        String[] names = {
            "net/minecraftforge/client/event/GuiScreenEvent.class",
            "net/minecraftforge/client/event/GuiScreenEvent$KeyboardInputEvent.class",
            "net/minecraftforge/client/event/GuiScreenEvent$KeyboardInputEvent$Pre.class",
        };
        for (String n : names) {
            ZipEntry e = z.getEntry(n);
            if (e == null) {
                System.out.println("absent: " + n);
                continue;
            }
            File f = new File(dest, n.replace('/', File.separatorChar));
            f.getParentFile().mkdirs();
            FileOutputStream out = new FileOutputStream(f);
            out.write(readAll(z.getInputStream(e)));
            out.close();
            System.out.println("wrote: " + f);
        }
        z.close();
    }

    private static byte[] readAll(java.io.InputStream in) throws Exception {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) {
            o.write(b, 0, n);
        }
        in.close();
        return o.toByteArray();
    }
}
