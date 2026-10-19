import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Derives the MCP-named Minecraft jar from the raw obfuscated one.
 *
 * <p>javac cannot compile against the jar the game loads: its member names are
 * obfuscated (aW, m()), and Forge's own Searge names (field_70122_E) are just
 * as far from the readable names a mod is written in. So the jar is rewritten
 * twice in one pass - obfuscated to Searge via joined.srg, then Searge to MCP
 * via the mapping CSVs - producing what ForgeGradle's deobfMcMCP step produced,
 * which is what this project compiled against all along.
 *
 * <p>Both halves are renamed: the class entries too, not only their members. A
 * raw 1.8 jar stores most classes under obfuscated entry names (bsu.class), and
 * without renaming those javac cannot resolve net.minecraft.block.Block at all.
 *
 * <p>Resolution is owner-aware, deliberately. Matching a member by its bare
 * name selects one MCP name belonging to several classes - "log" exists on
 * seven different owners - and picking one at random turns a logger call into
 * a field read. So a class's members are only ever resolved in the context of
 * that class.
 *
 * <p>Usage: java DeriveMcpJar &lt;raw.jar&gt; &lt;joined.srg&gt;
 * &lt;fields.csv&gt; &lt;methods.csv&gt; &lt;out.jar&gt;
 */
public final class DeriveMcpJar implements Opcodes {

    /** obfuscated class name -> readable class name. */
    private static final Map<String, String> classMap = new HashMap<>();

    /**
     * The same nested-name rule the class visitor uses, for archive entries.
     *
     * <p>An entry named {@code Outer$Inner} maps its outer prefix and keeps the
     * suffix, which is what makes {@code bhc$a} land as
     * {@code RayTraceResult$a} instead of staying unreadable.
     */
    private static String mapNameGlobal(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        String target = classMap.get(name);
        if (target != null) {
            return target;
        }
        int cut = name.indexOf('$');
        if (cut > 0) {
            String mapped = classMap.get(name.substring(0, cut));
            if (mapped != null) {
                return mapped + name.substring(cut);
            }
        }
        return null;
    }

    /** (owner, name) -> readable owner + Searge name, for fields. */
    private static final Map<String, String[]> fieldMap = new HashMap<>();

    /** (owner, name, desc) -> readable owner + Searge name, for methods. */
    private static final Map<String, String[]> methodMap = new HashMap<>();

    /** Searge name -> MCP name, from the CSVs. */
    private static final Map<String, String> csvFields = new HashMap<>();
    private static final Map<String, String> csvMethods = new HashMap<>();

    private DeriveMcpJar() {
    }

    public static void main(String[] args) throws Exception {
        File src = new File(args[0]);
        File out = new File(args[4]);

        loadSrg(new File(args[1]));
        loadCsv(new File(args[2]), csvFields);
        loadCsv(new File(args[3]), csvMethods);
        System.out.printf("srg: %d classes, %d fields, %d methods%n",
                classMap.size(), fieldMap.size(), methodMap.size());
        System.out.printf("csv: %d fields, %d methods%n",
                csvFields.size(), csvMethods.size());

        ZipOutputStream zos = new ZipOutputStream(
                new java.io.FileOutputStream(out));
        int renamed = 0, copied = 0;
        try (ZipFile z = new ZipFile(src)) {
            java.util.Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry ze = en.nextElement();
                byte[] data = readAll(z.getInputStream(ze));
                String name = ze.getName();

                // Nested entries (Outer$Inner) are separate archive entries in
                // this jar, not riding along with their outer class: leaving
                // them obfuscated leaves javac unable to resolve the type. In
                // 1.8 that cost a fallback to name() everywhere; the entry is
                // renamed here instead, so the class compiles like any other.
                if (!name.endsWith(".class")) {
                    zos.putNextEntry(new ZipEntry(name));
                    zos.write(data);
                    zos.closeEntry();
                    copied++;
                    continue;
                }
                String obf = name.substring(0, name.length() - ".class".length());
                String readable = mapNameGlobal(obf);
                if (readable == null) {
                    zos.putNextEntry(new ZipEntry(name));
                    zos.write(data);
                    zos.closeEntry();
                    copied++;
                    continue;
                }

                data = rename(data, obf, readable);
                ZipEntry ne = new ZipEntry(readable + ".class");
                zos.putNextEntry(ne);
                zos.write(data);
                zos.closeEntry();
                renamed++;
            }
        }
        zos.close();
        System.out.printf("renamed %d classes, copied %d -> %s%n",
                renamed, copied, out);
    }

    /**
     * Renames a class file: its own name, its members, and every reference.
     *
     * Three visitors in a chain: the outer one maps the class name, the middle
     * one resolves that class's members to MCP names, and the innermost
     * rewrites the field and method instructions in method bodies.
     */
    static byte[] rename(byte[] data, String obf, String readable) {
        ClassReader cr = new ClassReader(data);
        ClassWriter cw = new ClassWriter(0);
        Chain chain = new Chain(new MemberRenamer(cw, obf), obf, readable);
        cr.accept(chain, 0);
        return cw.toByteArray();
    }

    /** Maps the class's own name, and names typed as it. */
    static final class Chain extends ClassVisitor {
        private final String obf;
        private final String readable;

        Chain(ClassVisitor cv, String obf, String readable) {
            super(ASM5, cv);
            this.obf = obf;
            this.readable = readable;
        }

        @Override
        public void visit(int version, int access, String name, String sig,
                          String superName, String[] interfaces) {
            name = mapName(name);
            superName = mapName(superName);
            if (interfaces != null) {
                String[] mapped = new String[interfaces.length];
                for (int i = 0; i < interfaces.length; i++) {
                    mapped[i] = mapName(interfaces[i]);
                }
                interfaces = mapped;
            }
            super.visit(version, access, name, DeriveMcpJar.mapSig(sig),
                    superName, interfaces);
        }

        private String mapName(String name) {
            if (name == null || name.isEmpty()) {
                return name;
            }
            // Exact match, or the obfuscated name as a prefix of a nested name.
            String target = classMap.get(name);
            if (target != null) {
                return target;
            }
            int cut = name.indexOf('$');
            if (cut > 0) {
                String outer = name.substring(0, cut);
                String mapped = classMap.get(outer);
                if (mapped != null) {
                    return mapped + name.substring(cut);
                }
            }
            return this.readable.equals(name) ? this.readable : name;
        }
    }

    /** Resolves one class's members to MCP names. */
    static final class MemberRenamer extends ClassVisitor {
        private final String owner;

        MemberRenamer(ClassVisitor cv, String owner) {
            super(ASM5, cv);
            this.owner = owner;
        }

        /** Searge name for a member of THIS class, as MCP, or null. */
        private String[] mcp(String key) {
            String[] hit;
            if (fieldMap.containsKey(key)) {
                hit = fieldMap.get(key);
            } else {
                hit = methodMap.get(key);
            }
            if (hit == null) {
                return null;
            }
            String searge = hit[1];
            String mcp;
            if (searge.startsWith("field_")) {
                mcp = csvFields.get(searge);
            } else if (searge.startsWith("func_")) {
                mcp = csvMethods.get(searge);
            } else {
                mcp = null;
            }
            if (mcp == null || mcp.isEmpty()) {
                return null;
            }
            return new String[]{hit[0], mcp};
        }

        @Override
        public FieldVisitor visitField(int access, String name, String desc,
                                       String sig, Object value) {
            String[] hit = mcp(owner + "/" + name);
            if (hit != null) {
                name = hit[1];
            }
            // Field types too: "Lcim;" has to become the readable type.
            return super.visitField(access, name, DeriveMcpJar.mapDesc(desc), sig,
                    value);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc,
                                         String sig, String[] exceptions) {
            String key = owner + "/" + name + " " + desc;
            String[] hit = mcp(key);
            if (hit != null) {
                name = hit[1];
            }
            // Parameter and return types, and the signature if present.
            desc = DeriveMcpJar.mapDesc(desc);
            sig = DeriveMcpJar.mapSig(sig);
            if (exceptions != null) {
                String[] mapped = new String[exceptions.length];
                for (int i = 0; i < exceptions.length; i++) {
                    mapped[i] = DeriveMcpJar.mapType(exceptions[i]);
                }
                exceptions = mapped;
            }
            return new BodyRenamer(
                    super.visitMethod(access, name, desc, sig, exceptions),
                    this);
        }
    }

    /** Maps one internal class name, obfuscated to readable where known. */
    static String mapType(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        String target = classMap.get(name);
        if (target != null) {
            return target;
        }
        int cut = name.indexOf('$');
        if (cut > 0) {
            String mapped = classMap.get(name.substring(0, cut));
            if (mapped != null) {
                return mapped + name.substring(cut);
            }
        }
        return name;
    }

    /**
     * Maps every class name inside a JVM descriptor.
     *
     * "(Lcim;Lcim;)V" -> "(Lnet/minecraft/util/MovementInput;...)V". Only the
     * object types are of interest, so the scan is naive but complete: it takes
     * each "L...;" run and maps the name inside.
     */
    static String mapDesc(String desc) {
        if (desc == null || desc.indexOf('L') < 0) {
            return desc;
        }
        StringBuilder sb = new StringBuilder(desc.length() + 32);
        int i = 0;
        while (i < desc.length()) {
            char c = desc.charAt(i);
            if (c == 'L') {
                int end = desc.indexOf(';', i);
                if (end < 0) {
                    sb.append(desc.substring(i));
                    break;
                }
                sb.append('L').append(mapType(desc.substring(i + 1, end)))
                        .append(';');
                i = end + 1;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    /** Maps the class names inside a generic signature, if there is one. */
    static String mapSig(String sig) {
        if (sig == null) {
            return null;
        }
        String out = sig;
        for (Map.Entry<String, String> e : classMap.entrySet()) {
            out = out.replace("L" + e.getKey() + ";", "L" + e.getValue() + ";")
                    .replace("L" + e.getKey() + "<", "L" + e.getValue() + "<");
        }
        return out;
    }

    /** Rewrites field and method instructions inside method bodies. */
    static final class BodyRenamer extends MethodVisitor {
        private final MemberRenamer owner;

        BodyRenamer(MethodVisitor mv, MemberRenamer owner) {
            super(ASM5, mv);
            this.owner = owner;
        }


        @Override
        public void visitMethodInsn(int opcode, String ownerName, String name,
                                    String desc, boolean itf) {
            String[] hit = owner.mcp(ownerName + "/" + name + " " + desc);
            if (hit != null) {
                ownerName = hit[0];
                name = hit[1];
            }
            // The descriptor carries types that still name obfuscated classes
            // - "(Lcim;)V" for a MovementInput argument - and javac resolves
            // those, so they have to be renamed too.
            desc = DeriveMcpJar.mapDesc(desc);
            super.visitMethodInsn(opcode, ownerName, name, desc, itf);
        }

        @Override
        public void visitFieldInsn(int opcode, String ownerName, String name,
                                   String desc) {
            String[] hit = owner.mcp(ownerName + "/" + name);
            if (hit != null) {
                ownerName = hit[0];
                name = hit[1];
            }
            desc = DeriveMcpJar.mapDesc(desc);
            super.visitFieldInsn(opcode, ownerName, name, desc);
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            // NEW / ANEWARRAY / CHECKCAST / INSTANCEOF: the class being named.
            super.visitTypeInsn(opcode, DeriveMcpJar.mapType(type));
        }

        @Override
        public void visitLdcInsn(Object cst) {
            // A class literal (World.class) is a Type, not a String.
            if (cst instanceof org.objectweb.asm.Type) {
                org.objectweb.asm.Type t = (org.objectweb.asm.Type) cst;
                String mapped = DeriveMcpJar.mapType(t.getInternalName());
                if (!mapped.equals(t.getInternalName())) {
                    cst = org.objectweb.asm.Type.getObjectType(mapped);
                }
            }
            super.visitLdcInsn(cst);
        }
    }

    /**
     * Loads joined.srg. Line formats:
     *
     * <pre>
     *     CL: &lt;obf&gt; &lt;readable&gt;
     *     FD: &lt;obf&gt;/&lt;name&gt; &lt;readable&gt;/&lt;searge&gt;
     *     MD: &lt;obf&gt;/&lt;name&gt; &lt;obfDesc&gt; &lt;readable&gt;/&lt;searge&gt; &lt;desc&gt;
     * </pre>
     */
    static void loadSrg(File f) throws IOException {
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("CL: ")) {
                    String[] p = line.split(" ");
                    if (p.length == 3) {
                        classMap.put(p[1], p[2]);
                    }
                } else if (line.startsWith("FD: ")) {
                    String[] p = line.split(" ");
                    if (p.length == 3) {
                        int c = p[1].lastIndexOf('/');
                        int d = p[2].lastIndexOf('/');
                        if (c > 0 && d > 0) {
                            fieldMap.put(p[1].substring(0, c) + "/"
                                            + p[1].substring(c + 1),
                                    new String[]{p[2].substring(0, d),
                                            p[2].substring(d + 1)});
                        }
                    }
                } else if (line.startsWith("MD: ")) {
                    String[] p = line.split(" ");
                    if (p.length == 5) {
                        int c = p[1].lastIndexOf('/');
                        int d = p[3].lastIndexOf('/');
                        if (c > 0 && d > 0) {
                            methodMap.put(p[1].substring(0, c) + "/"
                                            + p[1].substring(c + 1) + " " + p[2],
                                    new String[]{p[3].substring(0, d),
                                            p[3].substring(d + 1)});
                        }
                    }
                }
            }
        }
    }

    /** Loads a mapping CSV: searge,name,side,desc. */
    static void loadCsv(File f, Map<String, String> into) throws IOException {
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String header = r.readLine();
            if (header == null) {
                return;
            }
            String line;
            while ((line = r.readLine()) != null) {
                int c1 = line.indexOf(',');
                int c2 = line.indexOf(',', c1 + 1);
                if (c1 > 0 && c2 > c1) {
                    String searge = line.substring(0, c1);
                    String name = line.substring(c1 + 1, c2);
                    if (!name.isEmpty()) {
                        into.put(searge, name);
                    }
                }
            }
        }
    }

    static byte[] readAll(java.io.InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        in.close();
        return out.toByteArray();
    }
}
