import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * MCP -> Searge reobfuscator for the compiled MeteorB classes.
 *
 * Forge loads mods against the runtime jar, where MC members carry their
 * Searge names (func_71410_x, field_71439_g). A mod compiled against MCP
 * names and shipped unchanged fails on the first call with
 * NoSuchMethodError / NoSuchFieldError. This is ForgeGradle's reobf step.
 *
 * The map comes from dist/reobf-map.txt, built by BuildReobfMap from the two
 * jars: it is keyed by owner + descriptor, which is the only way to get the
 * right SRG name. The bare-name approach matched MCP CSV rows without their
 * owner, and since one MCP name belongs to many classes (seven SRG names
 * exist for "thePlayer") it renamed thePlayer to a container's field.
 *
 * Descriptor matching is not decoration: it is what stops posX and posZ
 * (same MCP name, different classes) from crossing over, and it keeps a
 * differently-typed same-named field from being renamed at all.
 */
public class Reobf implements Opcodes {

    /** Only net/minecraft members are ever renamed. */
    static final String NET_MINECRAFT = "net/minecraft/";

    /** (owner, mcpName, desc) -> srgName. */
    static Map<String, String> map = new HashMap<String, String>();

    /** class -> superclass, so an override's real owner can be found. */
    static Map<String, String> supers = new HashMap<String, String>();

    /** Filled by the class writer loop: superclass of the class being renamed. */
    static String currentSuper = null;

    /**
     * Reads the superclass chain from the DERIVED jar, not the raw one.
     *
     * The raw jar is obfuscated, so its entries key the chain by "wv" and
     * "cio", while Reobf looks up readable owners ("net/minecraft/entity/
     * Entity") - the two never meet and the ancestor walk is always empty,
     * which is why inherited members such as EntityPlayerSP.worldObj shipped
     * with their MCP names and crashed the first tick with NoSuchFieldError.
     */
    static void loadSupers(File derivedJar) throws Exception {
        try (ZipFile z = new ZipFile(derivedJar)) {
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry ze = en.nextElement();
                if (!ze.getName().endsWith(".class")) {
                    continue;
                }
                ClassNode probe = new ClassNode();
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                InputStream in = z.getInputStream(ze);
                int n;
                while ((n = in.read(chunk)) > 0) {
                    buf.write(chunk, 0, n);
                }
                in.close();
                new ClassReader(buf.toByteArray()).accept(probe, 0);
                supers.put(probe.name.replace('.', '/'), probe.superName);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        File src = new File(args[0]);
        File out = new File(args[1]);
        load(args[2]);
        // args[3] is the DERIVED jar (readable class names), whose superclass
        // chain lets an inherited member be found under the owner's ancestors.
        // The old CSV fallback is deliberately gone: it matched a bare MCP name
        // with no owner, which is what turned a correct lookup into a wrong one.
        if (args.length > 3 && new File(args[3]).isFile()) {
            loadSupers(new File(args[3]));
        }
        System.out.printf("loaded %d owner-aware, %d csv-only entries%n",
                map.size(), csvOnly.size());

        ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out));
        int classes = 0, renamed = 0;
        for (File f : allClasses(src)) {
            byte[] data = Files.readAllBytes(f.toPath());
            ClassReader cr = new ClassReader(data);
            // The class's own superclass, so an override declared here but
            // belonging to a vanilla parent can still be mapped. ClassNode is
            // the only place ASM exposes it - ClassReader has no getSupername.
            ClassNode probe = new ClassNode();
            cr.accept(probe, 0);
            currentSuper = probe.superName;
            supers.put(probe.name, currentSuper);
            // A fresh writer, not new ClassWriter(cr, 0): the latter copies
            // the reader's constant pool and carries the original names.
            ClassWriter cw = new ClassWriter(0);
            Rv rv = new Rv(cw);
            // EXPAND_FRAMES: the visitor rebuilds frames and rejects ASM's
            // compacted default form.
            cr.accept(rv, ClassReader.EXPAND_FRAMES);
            zos.putNextEntry(new ZipEntry(arc(src, f)));
            zos.write(cw.toByteArray());
            zos.closeEntry();
            classes++;
            renamed += rv.renamed;
        }
        zos.close();
        System.out.printf("reobfuscated %d classes, %d member renames -> %s%n",
                classes, renamed, out);
    }

    static String arc(File root, File f) {
        return root.toPath().relativize(f.toPath()).toString().replace('\\', '/');
    }

    /** key helper: the JVM identifies a member by all three parts. */
    static String key(String owner, String name, String desc) {
        return owner + "." + name + " " + desc;
    }

    /**
     * Owner-aware lookup, then the CSV fallback for names that resolve to
     * exactly one SRG name. Returns null when the member is not an MC member
     * or the name is ambiguous.
     */
    /**
     * Field lookup.
     *
     * The map's field rows have an empty descriptor - joined.srg records no
     * field types - so the empty-desc key is tried after the exact one. That
     * fallback is safe ONLY for fields: EnumFacing has both a method and a
     * field whose MCP name is "name", so applying the same fallback to methods
     * turned Enum.name() into field_176757_j() and crashed class
     * initialisation with NoSuchMethodError.
     */
    static String resolveField(String owner, String name, String desc) {
        String srg = map.get(key(owner, name, desc));
        if (srg != null) {
            return srg;
        }
        if (desc != null && !desc.isEmpty()) {
            srg = map.get(key(owner, name, ""));
            if (srg != null) {
                return srg;
            }
        }
        for (String cur = supers.get(owner); cur != null;
             cur = supers.get(cur)) {
            srg = map.get(key(cur, name, desc));
            if (srg != null) {
                return srg;
            }
            if (desc != null && !desc.isEmpty()) {
                srg = map.get(key(cur, name, ""));
                if (srg != null) {
                    return srg;
                }
            }
        }
        return null;
    }

    /**
     * Method lookup: the descriptor is part of the key and is never dropped.
     * Overloads share a name, and a same-named field must not be matched.
     */
    static String resolveMethod(String owner, String name, String desc) {
        String srg = map.get(key(owner, name, desc));
        if (srg != null) {
            return srg;
        }
        // A member declared on a superclass is read through the subclass, so
        // the owner's own chain is walked - worldObj on Entity, reached via
        // EntityPlayerSP, is the case this exists for.
        for (String cur = supers.get(owner); cur != null;
             cur = supers.get(cur)) {
            srg = map.get(key(cur, name, desc));
            if (srg != null) {
                return srg;
            }
        }
        return null;
    }

    static class Rv extends ClassVisitor {
        int renamed = 0;

        /** Name of the class being visited, for owner-scoped lookups. */
        String self;

        Rv(ClassVisitor cv) {
            super(ASM5, cv);
        }

        @Override
        public void visit(int version, int access, String name, String sig,
                          String superName, String[] interfaces) {
            this.self = name;
            super.visit(version, access, name, sig, superName, interfaces);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc,
                                         String sig, String[] exceptions) {
            // Renaming the declaration is what an override needs: the JVM looks
            // up GuiScreen.func_73863_a by name, so the subclass's own method
            // must carry the Searge name too, or the screen never draws.
            String srg = resolveMethod(self, name, desc);
            if (srg != null) {
                name = srg;
                renamed++;
            }
            return new Mv(super.visitMethod(access, name, desc, sig, exceptions));
        }

        class Mv extends MethodVisitor {
            Mv(MethodVisitor mv) {
                super(ASM5, mv);
            }

            @Override
            public void visitFieldInsn(int opcode, String owner, String name,
                                       String desc) {
                String srg = resolveField(owner, name, desc);
                if (srg != null) {
                    renamed++;
                    name = srg;
                }
                super.visitFieldInsn(opcode, owner, name, desc);
            }

            @Override
            public void visitMethodInsn(int opcode, String owner, String name,
                                        String desc, boolean itf) {
                String srg = resolveMethod(owner, name, desc);
                if (srg != null) {
                    renamed++;
                    name = srg;
                }
                super.visitMethodInsn(opcode, owner, name, desc, itf);
            }
        }
    }

    static List<File> allClasses(File root) {
        List<File> found = new ArrayList<File>();
        Deque<File> stack = new ArrayDeque<File>();
        stack.push(root);
        while (!stack.isEmpty()) {
            File f = stack.pop();
            if (f.isDirectory()) {
                File[] kids = f.listFiles();
                if (kids != null) {
                    for (File k : kids) {
                        stack.push(k);
                    }
                }
            } else if (f.getName().endsWith(".class")) {
                found.add(f);
            }
        }
        Collections.sort(found);
        return found;
    }

    /** Rows: kind \t owner \t mcpName \t srgName \t desc */
    static void load(String path) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(path), "UTF-8"));
        String line;
        while ((line = r.readLine()) != null) {
            String[] p = line.split("\t", -1);
            if (p.length < 5) {
                continue;
            }
            if (p[0].equals("M") || p[0].equals("F")) {
                // ASM reports owners with slashes; the map stores them with
                // dots. Normalise here or nothing ever matches.
                map.put(p[1].replace('.', '/') + "." + p[2] + " " + p[4], p[3]);
            }
        }
        r.close();
    }

    /**
     * Fallback for members the positional pairing in BuildReobfMap lost.
     *
     * Pairing walks both jars' member lists in lockstep and drops the tail
     * when they diverge, so a handful of members never make it into the map
     * (Minecraft.getMinecraft is one). The CSV covers those - but only names
     * that map to exactly one SRG, since a name shared by several classes
     * (thePlayer) resolves to different SRG names per owner and cannot be
     * disambiguated without one.
     */
    static void loadCsvFallback(String mcpZip) throws Exception {
        ZipFile z = new ZipFile(mcpZip);
        addCsv(z, "methods.csv", "func_");
        addCsv(z, "fields.csv", "field_");
        z.close();
    }

    static void addCsv(ZipFile z, String entry, String prefix)
            throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(
                z.getInputStream(z.getEntry(entry)), "UTF-8"));
        String line;
        while ((line = r.readLine()) != null) {
            String[] p = line.split(",", -1);
            if (p.length < 2 || !p[0].startsWith(prefix)) {
                continue;
            }
            // Key by mcp name only; consult() must enforce uniqueness.
            String prev = csvOnly.get(p[1]);
            if (prev == null) {
                csvOnly.put(p[1], p[0]);
            } else if (!prev.equals(p[0])) {
                csvOnly.put(p[1], ""); // ambiguous -> never use
            }
        }
        r.close();
    }

    /** mcp name -> srg name, "" when ambiguous. */
    static Map<String, String> csvOnly = new HashMap<String, String>();
}
